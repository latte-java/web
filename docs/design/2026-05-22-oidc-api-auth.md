# OIDC API Authentication

Design for API authentication in the Latte Web OIDC subsystem. Adds token-based authentication for API clients (which hold their own tokens) alongside the existing cookie-based browser flow, plus a delegated authorization interface that decides whether a validated token may call a given API.

This builds on the OIDC subsystem described in `2026-04-27-oidc.md`. Terminology, package layout, cookie/`Tools` helpers, and the `Tools.CURRENT_JWT` ScopedValue are inherited from that document.

## Motivation

The existing `Authenticated` / `JWTAuthenticated` middlewares serve browser sessions: tokens live in cookies, refresh re-sets cookies, and gating is role-based via `HasAnyRole` / `HasAllRoles`. API clients are different:

- They hold their own access and refresh tokens and present them on each request (typically headers), not cookies.
- Validity must be checked against the IdP in real time (revocation), which a local JWKS decode cannot do.
- Authorization is per-API and application-specific — there can be hundreds of endpoints under a common prefix (`/api`), each with its own access rules.

This design adds an authentication middleware that validates tokens via RFC 7662 token introspection (with reactive refresh), and a separate, layered authorization middleware that delegates the access decision to an application-supplied interface.

Introspection is chosen over the OIDC UserInfo endpoint because it supports **both** token kinds an API may receive: user-delegated tokens (authorization_code / refresh) **and** machine-to-machine tokens (`client_credentials`). UserInfo only describes an end user, so it fails or is meaningless for `client_credentials` tokens; introspection's `active` flag is authoritative for any token type. Resource-server token validation is an OAuth2 concern (RFC 7662), not an OIDC-Core one — UserInfo is the user-claims endpoint, not a token-validation endpoint.

## Scope

- A `TokenExtractor` / `TokenWriter` pair (pluggable, with header-based defaults) for getting tokens off the request and refreshed tokens back onto the response.
- An `APIAuthenticated` middleware: extract → introspect → (reactive refresh) → decode to JWT → bind → write refreshed tokens.
- An `APIAuthorizer` functional interface and an `APIAuthorized` middleware that delegates the access decision to it.
- `OIDCConfig` additions: `introspectionEndpoint` (discoverable), `apiTokenExtractor`, `apiTokenWriter`.
- Factory methods on `OIDC<U>`: `apiAuthenticated()` and `apiAuthorized(APIAuthorizer)`.

Out of scope: introspection-response caching, opaque (non-JWT) access tokens, concurrent-refresh coordination, configurable header-name knobs (achieved by supplying a custom extractor/writer).

## Package layout (additions)

```
org.lattejava.web.oidc                       (exported)
├── APIAuthenticated                         (implements Middleware; package-private constructor)
├── APIAuthorized                            (implements Middleware; package-private constructor)
├── APIAuthorizer                            (@FunctionalInterface)
├── TokenExtractor                           (@FunctionalInterface)
├── TokenWriter                              (@FunctionalInterface)
└── Tokens                                   (record)

org.lattejava.web.oidc.internal              (not exported)
├── TokenValidator                           (extended: + introspect(token) → IntrospectionResult)
└── Tools                                    (extended: + shared refresh helper)
```

`APIAuthenticated` and `APIAuthorized` follow the existing convention: public types with package-private constructors, built through factory methods on `OIDC<U>`. No new internal type is introduced — introspection is added to the existing `TokenValidator`, and the refresh logic already living in `Authenticated` is factored down into `Tools` so both flows share it.

## New public types

### `Tokens`

```java
public record Tokens(String accessToken, String refreshToken, String idToken, Long expiresIn) {}
```

Any field may be null. This is the single token-bundle record used throughout the OIDC subsystem:

- the result of a refresh exchange (`Tools.refresh` returns it, or `null` on failure — replacing the former `RefreshResult`);
- the value a `TokenExtractor` pulls off a request and a `TokenWriter` writes back (replacing the former two-field `TokenPair`);
- the return of the test fixture's `login` helper (replacing the former nested `OIDCTestFixture.Tokens`).

Consolidating these into one record removes the earlier naming concern: with the nested `OIDCTestFixture.Tokens` gone, a single module-exported `org.lattejava.web.oidc.Tokens` is unambiguous. The `idToken` and `expiresIn` fields are unused by the API flow (the default `TokenWriter` writes only access/refresh) but are carried so the cookie flow and the fixture can use the same type.

### `TokenExtractor`

```java
@FunctionalInterface
public interface TokenExtractor {
  Tokens extract(HTTPRequest req);
}
```

**Default** (used when none is configured), provided as the nested `TokenExtractor.Default` class: access token from the `Authorization: Bearer <token>` header; refresh token from the `X-Refresh-Token` request header. A missing header yields a null in the corresponding `Tokens` slot.

### `TokenWriter`

```java
@FunctionalInterface
public interface TokenWriter {
  void write(HTTPRequest req, HTTPResponse res, Tokens tokens);
}
```

Called only after a successful refresh, with the newly issued tokens. **Default**, provided as the nested `TokenWriter.Default` class: writes the new access token to the `X-Access-Token` response header and, when present, the rotated refresh token to the `X-Refresh-Token` response header (a null refresh token in `Tokens` is skipped, since the existing one remains valid). Symmetric with the default `TokenExtractor`.

### `APIAuthorizer`

```java
@FunctionalInterface
public interface APIAuthorizer {
  boolean authorize(HTTPRequest req, JWT jwt);
}
```

Application-supplied. Receives the validated, decoded access-token JWT and the current request, returns `true` to allow or `false` to deny (`403`). It receives the request so it can make per-endpoint decisions (path/method/scope), which is why authorization is delegated rather than baked into role checks. The `APIAuthorized` middleware that invokes it plays the same role as `HasAnyRole` — it does not authenticate; it assumes a bound JWT is present.

## `OIDCConfig` additions

Three new fields on the record and Builder:

```java
URI introspectionEndpoint,   // RFC 7662 endpoint; discoverable via "introspection_endpoint"; explicit override wins
TokenExtractor apiTokenExtractor,  // default: Authorization: Bearer + X-Refresh-Token request header
TokenWriter apiTokenWriter         // default: X-Access-Token / X-Refresh-Token response headers
```

Validation:

- `introspectionEndpoint`, when set (explicitly or via discovery), must pass `Tools.requireSecureURI` (HTTPS, or `http://` loopback).
- Discovery (`fillIn()`) populates `introspectionEndpoint` from the discovery document's `introspection_endpoint` **when the configured value is null** — exactly like the other discovered endpoints. An explicit value is never overwritten. This is the same `fillIn()` block that already resolves authorize/token/userinfo/jwks/logout, so it is a one-line addition.
- `introspectionEndpoint` is **not** required by `build()` — it is only needed when API auth is used. `apiAuthenticated()` documents that it requires `introspectionEndpoint`; calling it without one configured throws `IllegalStateException`. (Rationale: a config used only for browser flows should not be forced to carry an introspection endpoint.)
- `apiTokenExtractor` / `apiTokenWriter` default to the header-based implementations described above and are never null.

Defaults baked into the Builder:

```java
private TokenExtractor apiTokenExtractor = new TokenExtractor.Default();  // Authorization: Bearer + X-Refresh-Token
private TokenWriter    apiTokenWriter    = new TokenWriter.Default();     // X-Access-Token / X-Refresh-Token headers
private URI            introspectionEndpoint;
```

## Factory methods on `OIDC<U>`

```java
/** API authentication: install once at the common API prefix (e.g. /api). Requires introspectionEndpoint. */
public APIAuthenticated apiAuthenticated() {
  return new APIAuthenticated(config, jwks);
}

/** API authorization: install per sub-API (and optionally as a baseline at the API prefix). Layered/additive. */
public APIAuthorized apiAuthorized(APIAuthorizer authorizer) {
  return new APIAuthorized(authorizer);
}
```

## `APIAuthenticated` — data flow

`APIAuthenticated` holds the `OIDCConfig` and `JWKS` and constructs a `TokenValidator` (the same internal type the cookie flow uses, now extended with introspection). It reuses `TokenValidator.introspect` for the validity gate, the shared `Tools` refresh helper for the refresh grant, and `JWT.decode(token, jwks, ...)` for the local decode.

1. `Tokens tokens = config.apiTokenExtractor().extract(req)`. If `tokens.accessToken()` is null → `unauthorized` (`401`); return.
2. **Introspect** the access token via `tokenValidator.introspect(tokens.accessToken())`, which returns an `IntrospectionResult` (see below):
   - `NetworkError` (thrown exception or `5xx`) → `res.setStatus(503)`; return (no token changes).
   - `Active` → the supplied access token is valid; go to step 4 with it (no write-back).
   - `Inactive` → reactive refresh (step 3).
3. **Reactive refresh** (via the shared `Tools` refresh helper):
   - If `tokens.refreshToken()` is null → `401`; return. (Machine-to-machine `client_credentials` tokens normally carry no refresh token, so an expired one lands here as `401`; the client re-obtains a token via `client_credentials` itself — that is not this middleware's job.)
   - The helper performs `Tools.postToken(config, {grant_type=refresh_token, refresh_token=<refreshToken>})` (HTTP Basic auth) and parses the response.
   - Thrown exception, non-2xx, unparseable body, or missing `access_token` → `401`; return.
   - On success it yields the new `access_token` (required) and `refresh_token` (optional). The refreshed access token came directly from the IdP, so it is **not** re-introspected.
   - `config.apiTokenWriter().write(req, res, refreshed)` — the `Tokens` returned by `Tools.refresh`.
   - Continue to step 4 with the new access token.
4. **Decode** the access token (original or refreshed) as a JWT against JWKS via `JWT.decode(token, jwks, ...)`. This both yields the claims and re-checks signature/`exp`. Decode failure → `401`; return. No audience check is performed here — audience/scope enforcement belongs to the `APIAuthorizer`.
5. **Bind and continue:** `ScopedValue.where(Tools.CURRENT_JWT, jwt).call(() -> { chain.next(req, res); return null; })`, so `OIDC.jwt()` / `OIDC.optionalJWT()` / `oidc.user()` work inside downstream handlers.

The `unauthorized` path here sets the HTTP status only (no redirect, no cookie clearing) — API clients receive status codes. Unlike `JWTAuthenticated`, no cookies are cleared, because this flow does not use cookies by default.

### `TokenValidator.introspect`

Added to the existing `TokenValidator`, reusing its `Tools.HTTP` client and `Tools.MAPPER`:

```java
public IntrospectionResult introspect(String token) { ... }

public sealed interface IntrospectionResult
    permits IntrospectionResult.Active, IntrospectionResult.Inactive, IntrospectionResult.NetworkError {
  record Active()       implements IntrospectionResult {}
  record Inactive()     implements IntrospectionResult {}
  record NetworkError() implements IntrospectionResult {}
}
```

`introspect` does `POST config.introspectionEndpoint()` with HTTP Basic auth (`clientId:clientSecret`) and form body `token=<token>&token_type_hint=access_token`, then maps: `200` with `active=true` → `Active`; `200` with `active` false/absent → `Inactive`; any non-2xx that isn't `5xx` → `Inactive`; `5xx` or thrown exception → `NetworkError`. This three-state shape mirrors the existing `Result` sealed type so the middleware switch reads the same way. Introspection is intentionally a pure validity gate — its response claims are ignored; claims come from the JWT decode in step 4.

### Introspection: validity gate vs. claims source

Introspection answers "is this token still active per the IdP" (the real-time revocation check a local decode can't provide). The local JWKS decode answers "what does the token say." Splitting these keeps a single, IdP-authoritative validity gate while sourcing claims from the JWT — and makes the initial path and the refresh path converge: both produce a `JWT` for the `APIAuthorizer`, even though only the initial path introspects.

## `APIAuthorized` — data flow

`APIAuthorized` holds a single `APIAuthorizer`. It is installed downstream of `apiAuthenticated()` (additively, anywhere along the `/api` prefix chain).

1. If `Tools.CURRENT_JWT` is not bound → `res.setStatus(401)`; return. (Configuration error: `apiAuthenticated()` did not run upstream. Fail closed, mirroring `HasAnyRole`.)
2. `boolean allowed = authorizer.authorize(req, Tools.CURRENT_JWT.get())`.
   - `true` → `chain.next(req, res)`.
   - `false` → `res.setStatus(403)`; return.

### Layered authorization

`MiddlewareTrie.collect` accumulates middlewares outer-to-inner along the request's path segments, so authorizers compose additively: an `APIAuthorized` installed at `/api` runs as a baseline for every API, and an `APIAuthorized` installed at `/api/users` runs in addition to it. Both must pass. This is a baseline-plus-refinement model — sub-APIs can tighten the baseline, never loosen it. There is no most-specific-wins override; applications that need per-API independence install exactly the authorizer(s) they want at each prefix.

## Code reuse

This feature deliberately leans on what the cookie flow already built. Concretely:

- **`TokenValidator`** — reused as the home for IdP-side token checks; introspection is added as `introspect(token)` rather than a new type, reusing `Tools.HTTP` and `Tools.MAPPER`.
- **Refresh logic** — `Authenticated.attemptRefresh` already POSTs `grant_type=refresh_token` via `Tools.postToken`, parses the response, and extracts `access_token` / `refresh_token` / `id_token` / `expires_in`. The token-endpoint call + response parsing (everything except the cookie write-back) is factored down into a shared `Tools` helper returning the parsed tokens. `Authenticated` then writes cookies; `APIAuthenticated` calls the `TokenWriter`. This removes duplication and is a targeted, in-scope improvement to existing code.
- **`JWT.decode(token, jwks, ...)`** — the same call `TokenValidator` uses for local validation provides the bound JWT here.
- **`Tools.CURRENT_JWT`** — the existing ScopedValue; no new binding mechanism. `OIDC.jwt()` / `oidc.user()` work unchanged inside API handlers.
- **`Tools.postToken`, `Tools.requireSecureURI`, `Tools.HTTP`, `Tools.MAPPER`** — reused as-is.
- **`OIDCConfig.Builder.fillIn()`** — the introspection endpoint is discovered through the existing discovery block.

New code is limited to: the four small public types (`Tokens`, `TokenExtractor`, `TokenWriter`, `APIAuthorizer`), the two middlewares (`APIAuthenticated`, `APIAuthorized`), the `introspect` method + result type on `TokenValidator`, the two factory methods on `OIDC<U>`, and the three `OIDCConfig` fields with their defaults.

## Usage

```java
var config = OIDCConfig.builder()
    .issuer("https://id.lattejava.org")           // discovery populates introspectionEndpoint
    .clientId("repo-api")
    .clientSecret(System.getenv("OIDC_CLIENT_SECRET"))
    .build();

var oidc = OIDC.create(config, jwt -> new AppUser(jwt.subject(), jwt.getString("email")));

web.install(oidc);  // system middleware (login/callback/logout) — harmless for pure-API apps

web.prefix("/api", api -> {
  api.install(oidc.apiAuthenticated());                       // authN for everything under /api
  api.install(oidc.apiAuthorized((req, jwt) ->                // layered baseline (always applies)
      jwt.getList("scope", String.class).contains("api")));

  api.prefix("/users", u -> {
    u.install(oidc.apiAuthorized((req, jwt) ->                // additional per-API check
        jwt.getList("scope", String.class).contains("users:read")));
    u.get("/{id}", (req, res) -> {
      AppUser user = oidc.user();                             // bound JWT available
      // ...
    });
  });
});
```

A custom transport (e.g. alternate header names) is configured by supplying `apiTokenExtractor` / `apiTokenWriter`, using only public request/response APIs:

```java
OIDCConfig.builder()
    // ...
    .apiTokenExtractor(req -> new Tokens(
        req.getHeader("X-My-Access"),
        req.getHeader("X-My-Refresh"),
        null, null))
    .apiTokenWriter((req, res, tokens) -> {
      res.setHeader("X-My-Access", tokens.accessToken());
      if (tokens.refreshToken() != null) {
        res.setHeader("X-My-Refresh", tokens.refreshToken());
      }
    })
    .build();
```

## Error handling summary

| Condition                                                | Status | Side effects |
|----------------------------------------------------------|--------|--------------|
| No access token extracted                                | `401`  | none         |
| Introspection `active=false` and refresh absent          | `401`  | none         |
| Refresh exception / non-2xx / missing `access_token`     | `401`  | none         |
| Refreshed access token fails JWT decode                  | `401`  | none         |
| Introspection endpoint network error / `5xx`             | `503`  | none         |
| `APIAuthorizer` returns `false`                          | `403`  | none         |
| `APIAuthorized` reached with no bound JWT                | `401`  | none         |
| Valid access token (or successful refresh) + authorizer allows | continue | refreshed tokens written via `TokenWriter` (refresh path only) |

No redirects anywhere — pure status codes, matching `JWTAuthenticated`'s contract for API clients.

## Testing plan

Following the existing OIDC testing conventions ([Local] / [FA] / [Mock]); FusionAuth-first.

### Config + discovery
- **[FA]** Issuer-only config: discovery populates `introspectionEndpoint` from `introspection_endpoint`.
- **[Local]** Explicit `introspectionEndpoint` overrides discovery.
- **[Local]** Non-loopback `http://` introspection endpoint → `IllegalArgumentException` at `build()`.
- **[Local]** `apiAuthenticated()` with no `introspectionEndpoint` resolvable → `IllegalStateException`.
- **[Local]** Default extractor/writer are non-null when not configured.

### `APIAuthenticated`
- **[FA]** Valid access token → introspection active → JWT bound → `chain.next` → handler runs; `oidc.user()` returns the translated value.
- **[Local]** Missing access token → `401`.
- **[FA]** Expired/revoked access token + valid refresh token → refresh succeeds, new access token written to `X-Access-Token`, request proceeds with refreshed JWT bound.
- **[FA]** Refresh response rotates the refresh token → `X-Refresh-Token` response header set.
- **[FA]** Refresh response omits the refresh token → no `X-Refresh-Token` header written.
- **[FA]** Introspection `active=false` + no refresh token → `401`.
- **[FA]** ~~`client_credentials` token~~ — deferred. FusionAuth's `client_credentials` grant requires licensed Entity Management, which the kickstart does not provision, so there is no client-credentials app to test against. The no-refresh-token path it would exercise (`inactiveToken_noRefreshToken_returns401`) and the introspection contract are already covered; introspection's `active` flag is grant-agnostic, so a `client_credentials` token follows the same code path as any other.
- **[Mock]** Introspection `5xx` / network error → `503`, no token changes.
- **[Mock]** Introspection `200` with `active=false` vs. a non-5xx error status both → `401` via the `Inactive` result.
- **[Mock]** Refresh endpoint non-2xx → `401`.
- **[Mock]** Refreshed access token fails JWT decode → `401`.
- **[Local]** Custom `apiTokenExtractor` (cookie-based) is honored.
- **[Local]** Custom `apiTokenWriter` is invoked on the refresh path with the new `Tokens`.

### `APIAuthorized`
- **[FA]** Authorizer returns `true` → handler runs.
- **[FA]** Authorizer returns `false` → `403`.
- **[Local]** `APIAuthorized` with no bound JWT → `401`.
- **[Local]** Authorizer receives the request (asserts path/method visibility).
- **[Local]** Layered: baseline authorizer at `/api` + sub-API authorizer at `/api/users` both run; failing either yields the respective denial.

### Integration
- **[FA]** End-to-end: install `apiAuthenticated()` at `/api` and `apiAuthorized()` at a sub-prefix; valid token + allowed scope reaches the handler; disallowed scope → `403`.

## Caveats and deferred

- **Introspection on every request.** Each protected API call makes one IdP introspection round-trip (revocation is the point). Latency and IdP load scale with traffic. Short-TTL caching keyed by an access-token hash is a post-MVP enhancement, mirroring the deferred userinfo-caching note in the cookie design.
- **JWT access tokens required.** The access token is decoded locally for claims; opaque access tokens are unsupported. If the IdP issues opaque access tokens, this flow cannot bind a JWT for the authorizer.
- **No re-introspection of refreshed tokens.** A token freshly minted by the token endpoint is trusted without introspection. The window between refresh and the next request is assumed too small for revocation to matter; a determined deployment can introspect in a custom layer.
- **No concurrent-refresh coordination.** Two simultaneous requests with the same expired access token both attempt refresh; if the IdP rotates refresh tokens, one may fail. Acceptable for v1, same as the cookie flow.
- **Layered, not override.** Authorization composes additively along the prefix chain; there is no most-specific-wins override. Sub-APIs can only add constraints to a baseline, not escape it.
- **Single `OIDC` instance per app.** Inherited from the cookie design — `Tools.CURRENT_JWT` is static.

