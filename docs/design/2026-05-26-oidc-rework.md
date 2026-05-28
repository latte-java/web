# OIDC Rework — Mode-Based Profiles

Redesign of the Latte Web OIDC subsystem. The current design models everything through a single `OIDC<U>` class plus a god-record `OIDCConfig`, then expresses the differences between client types through ad-hoc inheritance (`JWTAuthenticated extends Authenticated`) and a sibling re-implementation (`APIAuthenticated`). That breaks down because SSR, SPA/CSR, and API clients vary along axes that single-axis subclassing can't express cleanly.

This document supersedes `2026-04-27-oidc.md` and `2026-05-22-oidc-api-auth.md`. It is written from scratch — the existing implementation is **not** a constraint. A migration summary is at the end.

## The core insight: two axes, three profiles

Authentication for a protected route varies along exactly **two independent axes**. Everything else — token validation, the refresh mechanism, claims binding, discovery, JWKS, the audience check — is identical machinery configured per client.

1. **Transport** — how tokens are read off the request and written back. Pluggable via `TokenReader` and `TokenWriter`; each mode supplies a sensible **default** (cookies for the browser modes, bearer headers for API), but either is overridable. Nothing forces a browser to use cookies or an API to use headers — the cookie/header split below is the default, not a rule.

2. **Challenge** — how authentication/authorization outcomes are communicated to the client.
   - **HTML/redirect** (SSR) vs. **status codes** (SPA/CSR, API).

The three real client profiles are the three sensible points in that 2×2 grid of *default* transport × challenge; the fourth (a non-browser client receiving HTML challenges) is nonsensical:

|                                                  | Cookie transport | Bearer transport |
|--------------------------------------------------|------------------|------------------|
| **HTML/redirect challenge** (incl. interstitial) | **SSR**          | —                |
| **Status-code challenge**                        | **SPA / CSR**    | **API**          |

- **SSR** = cookies + HTML/redirect challenge + interstitial + session endpoints
- **SPA/CSR** = cookies + status-code challenge + session endpoints
- **API** = bearer headers + status-code challenge, no session endpoints

### Component matrix

| Component                                                         | SSR                                                                                 | SPA / CSR                | API                                                              |
|-------------------------------------------------------------------|-------------------------------------------------------------------------------------|--------------------------|------------------------------------------------------------------|
| **Token location (read)**                                         | Cookies (access, refresh, id)                                                       | Cookies (same)           | `Authorization: Bearer` + refresh header; no id token            |
| **Token validation**                                              | Decode/verify access-token JWT vs. JWKS; `aud` must contain the client's `clientId` | Identical                | Identical (its `clientId` may belong to a different application) |
| **Claims source / bound JWT**                                     | Decoded access-token JWT, bound to ScopedValue                                      | Identical                | Identical                                                        |
| **Refresh mechanism**                                             | `POST grant_type=refresh_token` to the token endpoint                               | Identical                | Identical                                                        |
| **Refresh write-back**                                            | New tokens → cookies                                                                | New tokens → cookies     | New tokens → response headers                                    |
| **Missing token**                                                 | 302 to login + return-to cookie                                                     | 401                      | 401                                                              |
| **Invalid token, can't refresh**                                  | Clear cookies, return-to, 302 to login                                              | Clear cookies, 401       | 401                                                              |
| **Invalid refresh (IdP rejects)**                                 | Clear cookies, return-to, 302 to login                                              | Clear cookies, 401       | 401                                                              |
| **Refresh token withheld (SameSite cross-site nav)**              | Meta-refresh interstitial (one-shot same-site retry)                                | n/a — fetch is same-site | n/a                                                              |
| **Network error (IdP unreachable)**                               | 503 (HTML page)                                                                     | 503                      | 503                                                              |
| **Authorization failure (authenticated, lacks role)**             | Pluggable `forbiddenHandler` (default minimal 403 page; may render or redirect)     | 403                      | 403                                                              |
| **Session endpoints (login / callback / logout / logout-return)** | Required                                                                            | Required                 | None — tokens obtained out-of-band                               |

The matrix collapses onto the two axes: the transport rows split SSR+SPA vs. API; the challenge rows split SSR vs. SPA+API. The interstitial is a sub-behavior of the HTML/redirect challenge — see [Why the interstitial is SSR-only](#why-the-interstitial-is-ssr-only).

## Public API

### Mode-first factories

A configured profile is created through a **mode-first static factory** on `OIDC`. There is no neutral "core" object you call a mode method on, so a wrong-mode combination (`apiProfile.ssr(...)`) is structurally impossible — each factory hands back a profile that is already, immutably, one mode.

```java
// Shorthand — default transport (cookie/header), paths, and policy.
static OIDC<JWT> ssr(OIDCConfig config);
static <U> OIDC<U> ssr(OIDCConfig config, Function<JWT, U> translator);
static OIDC<JWT> spa(OIDCConfig config);
static <U> OIDC<U> spa(OIDCConfig config, Function<JWT, U> translator);
static OIDC<JWT> api(OIDCConfig config);
static <U> OIDC<U> api(OIDCConfig config, Function<JWT, U> translator);

// With explicit mode settings.
static <U> OIDC<U> ssr(OIDCConfig config, BrowserSettings browser, Function<JWT, U> translator);
static <U> OIDC<U> spa(OIDCConfig config, BrowserSettings browser, Function<JWT, U> translator);
static <U> OIDC<U> api(OIDCConfig config, APISettings api, Function<JWT, U> translator);

// Escape hatch for a non-standard reader/writer/challenge combination.
static <U> OIDC<U> custom(OIDCConfig config, TokenReader reader, TokenWriter writer,
                          AuthChallenge challenge, Function<JWT, U> translator);
```

The shorthand `OIDC.ssr(config, jwt -> new AppUser(jwt))` is the common case; the `BrowserSettings`/`APISettings` overloads are used only when defaults need overriding. (The `JWT`-typed shorthands use `Function.identity()`.)

Each factory stitches the mode's pieces from the config and returns a mode-bound `OIDC<U>`:

| Factory    | Default reader      | Default writer      | Challenge           |
|------------|---------------------|---------------------|---------------------|
| `OIDC.ssr` | `CookieTokenReader` | `CookieTokenWriter` | `RedirectChallenge` |
| `OIDC.spa` | `CookieTokenReader` | `CookieTokenWriter` | `StatusChallenge`   |
| `OIDC.api` | `HeaderTokenReader` | `HeaderTokenWriter` | `StatusChallenge`   |

`TokenReader`, `TokenWriter`, and `AuthChallenge` are public (so `custom` and custom implementations are possible) but never appear in normal usage. The reader/writer defaults are supplied by the mode's settings object and can be overridden there (see below).

### `OIDC<U>` instance — the protection factory

The returned profile exposes only the protection methods. They return `Middleware` (the concrete implementations stay internal):

```java
public class OIDC<U> {
  // Authentication: the orchestrator middleware for this profile's reader, writer, and challenge.
  public Middleware authenticated();

  // Authorization: profile-agnostic decision routed through this profile's challenge.
  public Middleware hasAnyRole(String... roles);
  public Middleware hasAllRoles(String... roles);
  public Middleware authorized(Authorizer authorizer);

  // Request-scoped access to the bound JWT / translated user.
  public U user();                       // throws UnauthenticatedException if no JWT bound
  public Optional<U> optionalUser();
  public static JWT jwt();               // throws UnauthenticatedException if no JWT bound
  public static Optional<JWT> optionalJWT();
}
```

### Session endpoints

Login, callback, logout, and logout-return are shared by a browser client's SSR *and* SPA profiles, so they are **not** attached to a profile (attaching them to one would register the same paths twice if both profiles were installed). They are produced by a separate static, installed once per browser client. An API-only client never installs them.

```java
public static Middleware sessionEndpoints(OIDCConfig config);                       // defaults
public static Middleware sessionEndpoints(OIDCConfig config, BrowserSettings browser);
```

Pass the same `OIDCConfig` and `BrowserSettings` to `sessionEndpoints` and to the `ssr`/`spa` factories so the callback writes the same cookies the profiles read. (This is the same hand-threading already required for `OIDCConfig`; if it proves error-prone, a follow-up can fold the config into `BrowserSettings` so there is one object per browser client.)

### One `OIDC` per OAuth client

`OIDCConfig` is **per OAuth client**, not per app. `/app` and `/api` are typically different `clientId`s — possibly different applications in the IdP — so each validates against its own audience (`aud` contains that client's `clientId`). You create one config and one (or more) profiles per client:

```java
var webapp = OIDCConfig.builder().issuer("https://id.example.com").clientId("webapp")
    .clientSecret(System.getenv("WEBAPP_SECRET")).build();
var apiCli = OIDCConfig.builder().issuer("https://id.example.com").clientId("api")
    .clientSecret(System.getenv("API_SECRET")).build();

var ssr = OIDC.ssr(webapp, jwt -> new AppUser(jwt));   // webapp client, redirect challenge
var spa = OIDC.spa(webapp, jwt -> new AppUser(jwt));   // webapp client, status challenge
var api = OIDC.api(apiCli, jwt -> new Service(jwt));   // api client, status challenge

web.install(OIDC.sessionEndpoints(webapp));            // browser login flow for the webapp client

web.prefix("/app", app -> {
  app.install(ssr.authenticated());
  app.prefix("/admin", a -> a.install(ssr.hasAnyRole("admin")));
});

web.prefix("/spa-data", d -> d.install(spa.authenticated()));   // same client, 401 on failure

web.prefix("/api", r -> {
  r.install(api.authenticated());
  r.install(api.authorized((req, jwt) -> jwt.getList("scope", String.class).contains("api")));
});
```

Building `ssr` and `spa` from the same `webapp` config would fetch JWKS twice; the factory dedups internally with a process-wide cache keyed by the JWKS URI, so the clean API costs no extra fetch.

## Configuration objects

### `OIDCConfig` — the IdP relationship (per client)

Holds only what describes the OAuth client's relationship with the IdP. No cookie names, no paths, no transport knobs, no `apiAudience` (audience is `clientId`).

```java
public record OIDCConfig(
    String issuer,                          // optional — triggers discovery at build time
    URI authorizeEndpoint,                  // optional if discovery resolves it
    URI tokenEndpoint,
    URI userinfoEndpoint,
    URI jwksEndpoint,
    URI logoutEndpoint,                     // optional; IdP end-session endpoint
    URI introspectionEndpoint,              // optional; required when validateAccessToken=false
    String clientId,                        // required — also the expected token audience
    String clientSecret,                    // required
    List<String> scopes,                    // default: ["openid","profile","email","offline_access"]
    Function<JWT, Set<String>> roleExtractor,// default: jwt -> new HashSet<>(jwt.getList("roles", String.class))
    boolean validateAccessToken             // default: true — local JWKS decode; false → introspection
) { public static Builder builder(); }
```

`validateAccessToken` is a uniform toggle (local JWKS decode vs. RFC 7662 introspection) applied identically across all profiles of this client. Discovery and the secure-URI rules are unchanged from the prior design. `build()` requires `clientId`, `clientSecret`, and either `issuer` or all four of authorize/token/userinfo/jwks; it requires `introspectionEndpoint` resolvable when `validateAccessToken=false`.

### Settings — `BrowserSettings` and `APISettings`

The settings records carry **only** a `TokenReader` and `TokenWriter` (plus, for the browser, the session-flow config that is not token transport). Token cookie/header names, refresh-cookie max-age, SameSite/HttpOnly policy — none of that appears on the settings; it all lives on the reader/writer implementations. The Builder's `build()` defaults the pair, and a supplied reader/writer supersedes the default regardless of mechanism.

```java
public record BrowserSettings(
    // transport — default cookie-based; the reader/writer own all cookie names and policy
    TokenReader tokenReader,                // default: new CookieTokenReader()  (uses its own default names)
    TokenWriter tokenWriter,                // default: new CookieTokenWriter()  (uses its own default names + 30-day refresh max-age)
    // session-flow cookie names (not OAuth tokens — used by sessionEndpoints / RedirectChallenge directly)
    String stateCookieName,                 // default: "oidc_state"      (PKCE / CSRF state)
    String returnToCookieName,              // default: "oidc_return_to"  (post-login return target)
    // session-endpoint paths
    String loginPath,                       // default: "/login"
    String callbackPath,                    // default: "/oidc/return"
    String logoutPath,                      // default: "/logout"
    String logoutReturnPath,                // default: "/oidc/logout-return"
    // redirect targets (session endpoints / login flow)
    String postLoginPage,                   // default: "/"
    String postLogoutPage,                  // default: "/"
    String errorPage,                       // default: "/"  — callback failures land here
    // RedirectChallenge page renderers — pluggable Handlers (render inline or redirect)
    Handler forbiddenHandler,               // default: a minimal built-in 403 page
    Handler unavailableHandler              // default: a minimal built-in 503 page
) { public static Builder builder(); }

public record APISettings(
    TokenReader tokenReader,                // default: new HeaderTokenReader(AUTHORIZATION, X_REFRESH_TOKEN)
    TokenWriter tokenWriter                 // default: new HeaderTokenWriter(X_ACCESS_TOKEN, X_REFRESH_TOKEN)
) { public static Builder builder(); }
```

`stateCookieName`/`returnToCookieName` are session-flow cookies (the PKCE/CSRF state and the post-login return target), not OAuth tokens, so the reader/writer don't touch them — `sessionEndpoints` and `RedirectChallenge` use them directly. The default `CookieTokenWriter` applies the prior design's cookie attributes: `Path=/`, `HttpOnly` per cookie (id token readable for SPA), `Secure` when the request is HTTPS or `X-Forwarded-Proto: https`, SameSite `Lax` for access/id and `Strict` for the refresh token — the asymmetry the interstitial handles.

`forbiddenHandler` and `unavailableHandler` are the existing public `Handler` (`void handle(HTTPRequest, HTTPResponse) throws Exception`), so the app renders a real page — `(req, res) -> jte.html("403.jte", req, res)` — or redirects if it prefers — `(req, res) -> res.sendRedirect("/403")`. The defaults write a minimal page with the right status. SPA profiles ignore these and the redirect-target fields, since they never render or redirect.

`APISettings` is intentionally minimal — the API mode has no paths or redirect targets — but the same shape as `BrowserSettings` (just the reader/writer pair) keeps the override surface symmetric. The default header names live as public constants on `APISettings` (`AUTHORIZATION`, `X_ACCESS_TOKEN`, `X_REFRESH_TOKEN`) used by `build()` to construct the default `HeaderTokenReader`/`HeaderTokenWriter`. The default cookie names + refresh max-age live as public constants on `CookieTokenWriter` (`ACCESS_TOKEN_COOKIE`, `REFRESH_TOKEN_COOKIE`, `ID_TOKEN_COOKIE`, `REFRESH_TOKEN_MAX_AGE`); `CookieTokenReader` references them, and the no-arg constructors of both use them. This split — settings hold only the pair; the pair owns its own configuration — is what lets an API authenticate from cookies (supply a `CookieTokenReader`) or a browser endpoint read a bearer header, without changing modes. The mode only fixes the challenge.

## Internal architecture

### Package layout

```
org.lattejava.web
└── UnauthenticatedException                (existing; default ExceptionHandler mapping → 401)

org.lattejava.web.oidc                      (exported)
├── OIDC<U>                                 (mode-first factories + profile instance)
├── OIDCConfig                              (record + Builder)
├── BrowserSettings                         (record + Builder)
├── APISettings                             (record + Builder)
├── AuthChallenge                           (interface — public for custom)
├── Authorizer                              (@FunctionalInterface + hasAnyRole/hasAllRoles statics)
├── TokenReader                             (interface — public for custom)
├── TokenWriter                             (interface — public for custom)
└── Tokens                                  (record)

org.lattejava.web.oidc.internal             (not exported)
├── Authentication                          (orchestrator Middleware)
├── Authorization                           (Middleware)
├── CookieTokenReader                       (TokenReader)
├── CookieTokenWriter                       (TokenWriter)
├── HeaderTokenReader                       (TokenReader)
├── HeaderTokenWriter                       (TokenWriter)
├── RedirectChallenge                       (AuthChallenge)
├── StatusChallenge                         (AuthChallenge)
├── SessionEndpoints                        (Middleware; dispatches the four browser paths)
├── CallbackHandler / LoginHandler / LogoutHandler / LogoutReturnHandler  (Handlers)
├── TokenValidator                          (uniform validate(); local-or-introspection)
└── Tools                                   (cookies, HTTP, JSON, ScopedValue, refresh)
```

### `TokenReader` / `TokenWriter` (axis 1)

Pure token I/O, split so reading and writing are independently pluggable. The reader pulls tokens off the request; the writer updates them on a successful refresh and clears them on failure.

```java
public interface TokenReader {
  Tokens read(HTTPRequest req);
}

public interface TokenWriter {
  void write(HTTPRequest req, HTTPResponse res, Tokens tokens);   // update after a refresh
  void clear(HTTPRequest req, HTTPResponse res);                  // remove on failure
}
```

Default implementations (selected by the mode's settings; overridable by setting `tokenReader`/`tokenWriter` directly):

- **`CookieTokenReader`** / **`CookieTokenWriter`**: own the access/refresh/id token-cookie names (and the writer owns the refresh max-age + cookie policy). Both expose a no-arg constructor that uses the standard defaults, and an explicit-name constructor for custom names. The default names and max-age are public constants on `CookieTokenWriter` (`ACCESS_TOKEN_COOKIE`, `REFRESH_TOKEN_COOKIE`, `ID_TOKEN_COOKIE`, `REFRESH_TOKEN_MAX_AGE`); `CookieTokenReader`'s defaults reference them, guaranteeing the pair's names match. `BrowserSettings.builder().build()` defaults the pair via `new CookieTokenReader()` / `new CookieTokenWriter()`.
- **`HeaderTokenReader`** / **`HeaderTokenWriter`**: own the read/write header names; constructed with explicit names. The reader reads `Authorization: Bearer` for the access token (case-insensitive) and the configured refresh header; the writer sets the response token headers; **`clear` is a no-op** (there is nothing persisted to remove). The default header names (`AUTHORIZATION`, `X_ACCESS_TOKEN`, `X_REFRESH_TOKEN`) are public constants on `APISettings`, and `APISettings.builder().build()` defaults the pair via those constants.

Keeping `clear` on the writer is what lets SPA and API share one `StatusChallenge`: "clear cookies on a 401" vs. "don't" is a writer property, not a challenge property — the challenge just calls `writer.clear(...)` and the writer decides what that means. When a reader and writer are paired (the default cookie/header pairs, or a custom pair), the read names must match the write names; the defaults guarantee this, and a custom pair owns its own consistency.

### `AuthChallenge` (axis 2)

Shapes the response for the three outcomes. It receives the writer so it decides *when* to clear, and a `retryable` flag for the one-shot interstitial.

```java
public interface AuthChallenge {
  void unauthenticated(HTTPRequest req, HTTPResponse res, TokenWriter writer, boolean retryable) throws Exception;
  void forbidden(HTTPRequest req, HTTPResponse res) throws Exception;
  void unavailable(HTTPRequest req, HTTPResponse res) throws Exception;
}
```

**`StatusChallenge`** (SPA + API):
- `unauthenticated`: `writer.clear(req, res)` then `401` (ignores `retryable` — a fetch client can't follow a meta-refresh; its JS re-navigates to login on a 401).
- `forbidden`: `403` (no clear — the session is valid, the user simply lacks the role).
- `unavailable`: `503`.

**`RedirectChallenge`** (SSR; built from `BrowserSettings`):
- `unauthenticated`: if `retryable` and the retry marker query parameter is absent, write the meta-refresh interstitial to the same URL with the marker appended (no clearing, no return-to) and return. Otherwise `writer.clear(req, res)`, set the return-to cookie to the current URL, and `302` to `loginPath`.
- `forbidden`: invoke the configured `forbiddenHandler` (the existing public `Handler`); it owns the status and body, so it can render a page (`(req, res) -> jte.html("403.jte", req, res)`) or redirect. Defaults to a minimal `403` page.
- `unavailable`: invoke the configured `unavailableHandler`. Defaults to a minimal `503` page.

### `Authentication` — the one orchestrator

A single middleware runs the same algorithm for every profile, holding the profile's `reader`, `writer`, `challenge`, the config, and a `TokenValidator`:

```
Tokens tokens = reader.read(req);
if (tokens.accessToken() == null)
    → challenge.unauthenticated(req, res, writer, false); return;        // no session at all
switch (validator.validate(tokens.accessToken())) {
  case Valid(jwt)   → bind(jwt); chain.next(req, res); return;
  case NetworkError → challenge.unavailable(req, res); return;
  case Invalid → {
    if (tokens.refreshToken() == null)
        → challenge.unauthenticated(req, res, writer, true); return;     // refresh may be withheld (SameSite)
    Tokens refreshed = Tools.refresh(config, tokens.refreshToken());
    if (refreshed == null || !(validator.validate(refreshed.accessToken()) instanceof Valid v))
        → challenge.unauthenticated(req, res, writer, false); return;    // refresh genuinely failed
    writer.write(req, res, refreshed);
    bind(v.jwt()); chain.next(req, res); return;
  }
}
```

`bind` is `ScopedValue.where(Tools.CURRENT_JWT, jwt).call(...)`. Only the `Invalid`-but-no-refresh-token branch passes `retryable=true`: that is the SameSite signature (the `Lax` access cookie arrived but the `Strict` refresh cookie was withheld on a cross-site top-level navigation). Every other failure passes `false`.

### `Authorizer` + `Authorization`

Roles and custom API authorization unify into one interface; an authorizer returns a boolean and **never emits a status code** — the middleware routes the outcome through the profile's challenge, which is what fixes SSR returning a bare 403.

```java
@FunctionalInterface
public interface Authorizer {
  boolean authorize(HTTPRequest req, JWT jwt);
  static Authorizer hasAnyRole(Function<JWT, Set<String>> roleExtractor, String... roles);
  static Authorizer hasAllRoles(Function<JWT, Set<String>> roleExtractor, String... roles);
}
```

The `Authorization` middleware holds an `Authorizer` plus the profile's `writer` and `challenge`:

```
if (!Tools.CURRENT_JWT.isBound())
    → challenge.unauthenticated(req, res, writer, false); return;        // authenticated() missing upstream — fail closed
if (authorizer.authorize(req, Tools.CURRENT_JWT.get())) chain.next(req, res);
else challenge.forbidden(req, res);
```

`oidc.hasAnyRole(...)` / `hasAllRoles(...)` build the role authorizers from the config's `roleExtractor`; `oidc.authorized(authorizer)` wraps an arbitrary one. Authorizers compose additively along the prefix chain (all must pass), as in the prior design.

### `TokenValidator` — uniform validation

One path for all profiles. When `validateAccessToken=true`: `JWT.decode(token, jwks, this::checkAudience)`, where `checkAudience` throws unless `aud` contains `config.clientId()`. When `false`: RFC 7662 introspection against `introspectionEndpoint`, the response wrapped as a `JWT`, subject to the same audience check. Returns the sealed `Result` (`Valid(jwt)` / `Invalid` / `NetworkError`). There is no separate "introspect as a gate, decode for claims" API-only path and no `apiAudience` — audience is always the client's `clientId`.

### Session endpoints

`SessionEndpoints` is a middleware that dispatches the four browser paths (`loginPath`, `callbackPath`, `logoutPath`, `logoutReturnPath`) from `BrowserSettings`, passing through otherwise — the same dispatch the current `OIDC.handle` performs, extracted into its own type. Login (PKCE `S256` authorize redirect), callback (code exchange → cookies → return-to redirect), logout (optional IdP end-session), and logout-return (clear cookies → landing) behave as in `2026-04-27-oidc.md`. Cookie writes/clears go through the same `CookieTokenWriter`/`Tools` helpers the browser profiles use, so names and policy stay consistent.

## Why the interstitial is SSR-only

The interstitial rescues a **cross-site top-level navigation to a protected route that serves content directly** — the SSR case. With `SameSite=Lax` on the access cookie and `SameSite=Strict` on the refresh cookie, a user following an external link to `/app/dashboard` sends the (possibly expired) access cookie but not the refresh cookie. Without recovery, an authenticated user with a valid refresh token would be bounced through login. The meta-refresh re-requests same-site so the `Strict` refresh cookie is sent and the reactive refresh succeeds.

A SPA/CSR app never hits this: the external link lands on the SPA initializer, which is **unprotected static content**, not behind `authenticated()`. The booted SPA then fetches its API same-site from its own origin, so all cookies are sent regardless of how the user arrived. API clients carry no cookies at all. Hence the interstitial belongs solely to `RedirectChallenge`.

## Changes from the current implementation

| Current                                                                | Replaced by                                                                                 |
|------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| `OIDC.create(config)` / `create(config, translator)`                   | `OIDC.ssr/spa/api(config[, settings], translator)`                                          |
| `Authenticated` middleware                                             | `Authentication` orchestrator + cookie reader/writer + `RedirectChallenge` (via `OIDC.ssr`) |
| `JWTAuthenticated`                                                     | `Authentication` + cookie reader/writer + `StatusChallenge` (via `OIDC.spa`)                |
| `APIAuthenticated`                                                     | `Authentication` + header reader/writer + `StatusChallenge` (via `OIDC.api`)                |
| `APIAuthorized` + `APIAuthorizer`                                      | `Authorization` + `Authorizer` (`oidc.authorized(...)`)                                     |
| `HasAnyRole` / `HasAllRoles`                                           | `Authorization` + `Authorizer.hasAnyRole/hasAllRoles`                                       |
| `TokenExtractor` / `TokenWriter`                                       | `TokenReader` (read) / `TokenWriter` (write + clear)                                        |
| `OIDC.handle` system dispatch                                          | `SessionEndpoints` middleware                                                               |
| `OIDCConfig` token-cookie names / `refreshTokenMaxAge`                 | `CookieTokenReader` / `CookieTokenWriter`                                                   |
| `OIDCConfig` paths / redirect targets / state + return-to cookie names | `BrowserSettings`                                                                           |
| `OIDCConfig` `apiTokenExtractor` / `apiTokenWriter` / header names     | `HeaderTokenReader` / `HeaderTokenWriter` (defaults on `APISettings`)                       |
| `OIDCConfig.apiAudience`                                               | removed — audience is `clientId`                                                            |
| `validateAccessToken`                                                  | retained on `OIDCConfig`, uniform across profiles                                           |

The two-state authorization (role check emitting a status code) becomes challenge-routed, so SSR authorization failures render HTML instead of a bare 403 — the original motivation for the rework.

## Deferred / caveats

- **Single bound JWT per request.** `Tools.CURRENT_JWT` stays a static `ScopedValue`; a request is handled by one profile, so one JWT is bound. Multiple `OIDC` instances (one per client) coexist fine — you call `user()` on the profile that protected the route. Instance-scoped binding for advanced multi-tenant cases is deferred.
- **JWKS dedup** is a process-wide cache keyed by JWKS URI; cross-issuer rotation/refresh-on-miss is not handled (restart to pick up rotated keys), as in the prior design.
- **No introspection caching** when `validateAccessToken=false` — every protected request makes an IdP round-trip.
- **No concurrent-refresh coordination** — two simultaneous expired-token requests may both refresh; if the IdP rotates refresh tokens, one may fail.
- **No proactive refresh** — refresh runs only after validation fails.
- **`BrowserSettings` hand-threading** — passing the same object to `sessionEndpoints` and the browser profiles is the one place a deployment can drift cookie names; folding the config into `BrowserSettings` is a possible follow-up.
- **Reader/writer pairing is the caller's responsibility** — a custom `TokenReader` and `TokenWriter` must agree on token locations (read names match write names). The default cookie and header pairs guarantee this; a custom pair owns its own consistency.

## Testing plan

Tags as before: **[Local]** (no IdP), **[FA]** (FusionAuth at `http://localhost:9012`, kickstart-provisioned), **[Mock]** (canned IdP responses). FusionAuth-first.

### Config objects
- **[Local]** `OIDCConfig` required-field validation; either issuer or all four endpoints.
- **[FA]** Issuer-only discovery populates endpoints incl. `introspection_endpoint`.
- **[Local]** `validateAccessToken=false` with no resolvable `introspectionEndpoint` → build error.
- **[Local]** `BrowserSettings` / `APISettings` defaults are non-null; default `tokenReader`/`tokenWriter` are the standard cookie/header implementations; `CookieTokenReader`/`CookieTokenWriter` and `HeaderTokenReader`/`HeaderTokenWriter` token names overridable on those types.

### Transport (reader / writer)
- **[Local]** `CookieTokenReader` pulls access/refresh/id from cookies; `CookieTokenWriter.write` sets policy + max-ages; `clear` deletes the three.
- **[Local]** `HeaderTokenReader` parses `Authorization: Bearer` + refresh header; `HeaderTokenWriter.write` sets the write headers; `clear` is a no-op.
- **[Local]** Custom header names (a configured `HeaderTokenReader`/`HeaderTokenWriter`) are honored.
- **[Local]** A custom `tokenReader`/`tokenWriter` supplied to `BrowserSettings`/`APISettings` supersedes the default (e.g. an API profile configured with a `CookieTokenReader` authenticates from cookies).

### Validation
- **[FA]** Valid access-token JWT → `Valid`; bound JWT carries claims.
- **[FA]** `aud` missing the client's `clientId` → `Invalid`.
- **[Mock]** `validateAccessToken=false`: introspection `active=true` → `Valid`; `active=false`/non-5xx → `Invalid`; 5xx/exception → `NetworkError`.

### Orchestrator (per challenge)
- **[FA]** Valid token → JWT bound, `chain.next` runs; `oidc.user()` returns the translated value.
- **[FA]** Expired token + valid refresh → refresh succeeds, tokens written via the writer, request proceeds with refreshed JWT.
- **[Mock]** Validation `NetworkError` → `unavailable` (SSR HTML 503 / SPA+API 503).
- **[Mock]** Refresh endpoint non-2xx, or refreshed token fails validation → `unauthenticated`.

### Challenge behaviors
- **[FA/Local]** SSR missing token → 302 to `loginPath` + return-to cookie set.
- **[Local]** SSR `retryable` (access present/invalid, refresh absent), no marker → meta-refresh interstitial, no cookies cleared; with marker → clear + return-to + 302.
- **[Local]** SPA missing/unrefreshable token → cookies cleared + 401.
- **[Local]** API missing/unrefreshable token → 401, no cookies touched.
- **[Local]** Authorization denial: SSR → invokes `forbiddenHandler` (default minimal 403 page; a custom handler is honored and may render or redirect); SPA/API → 403.
- **[Local]** Authorization with no bound JWT (authenticate() missing) → fail closed via `unauthenticated`.

### Authorizer
- **[FA]** `hasAnyRole`/`hasAllRoles` pass/deny against kickstart users; custom `roleExtractor` resolves nested claims.
- **[Local]** Empty roles varargs → `IllegalArgumentException`.
- **[FA]** `authorized(...)` allow → handler runs; deny → 403; receives the request for per-endpoint decisions.
- **[Local]** Layered authorizers along a prefix chain both run; failing either denies.

### Session endpoints
- **[FA]** Full browser login: `/login` → authorize redirect (PKCE) → `/callback` code exchange → cookies set → return-to redirect.
- **[Local]** Callback error paths → 302 to `errorPage` with `oidc_error` / `oidc_error_description`, cookies cleared.
- **[FA/Local]** Logout with/without `logoutEndpoint`; logout-return clears cookies and redirects.
- **[Local]** API-only client never installs `sessionEndpoints`; those paths 404.

### Multi-client integration
- **[FA]** Two clients (`webapp`, `api`) with distinct `clientId`s: an `/app` request validates against `webapp`'s audience and an `/api` request against `api`'s; a token minted for one is rejected by the other.
- **[FA]** SSR and SPA profiles built from the same client share one JWKS fetch (dedup cache).
```
