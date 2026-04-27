# OpenID Connect

Design for OIDC authentication in Latte Web. Branch: `features/oidc`.

`UnauthenticatedException` lives in `org.lattejava.web` because non-OIDC code may throw/catch it. Everything else lives in `org.lattejava.web.oidc` (public) or `org.lattejava.web.oidc.internal` (implementation).

IdP-agnostic: no external OIDC/OAuth2 library on the request path. Discovery and JWKS use the `org.lattejava.jwt` library (`OpenIDConnect.discover` and `JWKS.fromJWKS`).

## Scope

One cohesive subsystem covering:

- Configuration (`OIDCConfig`).
- Runtime class that is both the API entry point AND the system middleware (`OIDC<U>`).
- Three protection middlewares: `authenticated()`, `hasAnyRole()`, `hasAllRoles()`.
- Login-redirect flow with PKCE (`S256`).
- OIDC Discovery (`{issuer}/.well-known/openid-configuration`) with explicit-endpoint override, performed at config-build time.
- JWKS fetch for token (access and ID) signature verification.
- Login handler (kicks off the authorize redirect on `loginPath`).
- Callback handler (code exchange → set cookies → redirect to return-to).
- Logout handler with optional IdP RP-initiated logout (redirect to IdP end-session endpoint → IdP redirects back to a dedicated return path → clear cookies → redirect to landing).
- Refresh-token cookie (lifetime configurable) and reactive refresh on expired access token.
- Request-scoped access to the JWT via `ScopedValue`, optionally typed via translator.

## Package layout

```
org.lattejava.web
└── UnauthenticatedException                 (extends RuntimeException)

org.lattejava.web.oidc                       (exported)
├── OIDC<U>                                  (implements Middleware)
├── OIDCConfig                               (record with Builder)
├── Authenticated                            (implements Middleware; package-private constructor)
├── HasAnyRole                               (implements Middleware; package-private constructor)
└── HasAllRoles                              (implements Middleware; package-private constructor)

org.lattejava.web.oidc.internal              (not exported)
├── CallbackHandler                          (Handler)
├── LoginHandler                             (Handler)
├── LogoutHandler                            (Handler)
├── LogoutReturnHandler                      (Handler)
├── TokenValidator                           (validates access tokens via JWT-or-userinfo)
└── Tools                                    (cookie helpers, HTTP client, mapper, ScopedValue)
```

`Authenticated`, `HasAnyRole`, and `HasAllRoles` are public types so handlers can refer to them, but their constructors are package-private. Construction goes through the factory methods on `OIDC`:

```java
web.prefix("/app", app -> {
  app.install(oidc.authenticated());
  app.prefix("/admin", admin -> admin.install(oidc.hasAnyRole("admin")));
});
```

The `ScopedValue<JWT>` lives on `org.lattejava.web.oidc.internal.Tools` (`Tools.CURRENT_JWT`); `OIDC` exposes it via the static `jwt()` / `optionalJWT()` and the instance `user()` / `optionalUser()`.

## `OIDC<U>`

```java
public class OIDC<U> implements Middleware {
  private final CallbackHandler callbackHandler;
  private final OIDCConfig config;
  private final JWKS jwks;
  private final LoginHandler loginHandler;
  private final LogoutHandler logoutHandler;
  private final LogoutReturnHandler logoutReturnHandler;
  private final Function<JWT, U> translator;

  public static OIDC<JWT> create(OIDCConfig config) { ... }
  public static <U> OIDC<U> create(OIDCConfig config, Function<JWT, U> translator) { ... }

  /** System-endpoint middleware: handles login, callback, logout, and logout-return paths; passes through otherwise. */
  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    String path = req.getPath();
    if (path.equals(config.callbackPath()))     { callbackHandler.handle(req, res); return; }
    if (path.equals(config.loginPath()))        { loginHandler.handle(req, res); return; }
    if (path.equals(config.logoutPath()))       { logoutHandler.handle(req, res); return; }
    if (path.equals(config.logoutReturnPath())) { logoutReturnHandler.handle(req, res); return; }
    chain.next(req, res);
  }

  public Authenticated authenticated() { return new Authenticated(config, jwks); }
  public HasAnyRole    hasAnyRole(String... roles)  { return new HasAnyRole(config, roles); }
  public HasAllRoles   hasAllRoles(String... roles) { return new HasAllRoles(config, roles); }

  public U user() { ... }                       // throws UnauthenticatedException if no JWT bound
  public Optional<U> optionalUser() { ... }     // empty if no JWT bound

  public static JWT jwt() { ... }               // throws UnauthenticatedException if no JWT bound
  public static Optional<JWT> optionalJWT() { ... }
}
```

`OIDC.create(...)` constructs `JWKS.fromJWKS(config.jwksEndpoint().toString()).build()` — a single fetch of the JWKS document at startup. The four internal handlers are pre-built on the instance and dispatched by path in `handle`.

## `OIDCConfig`

```java
public record OIDCConfig(
    String issuer,                           // optional — triggers discovery at build time
    URI authorizeEndpoint,                   // optional if discovery resolves it
    URI tokenEndpoint,                       // ditto
    URI userinfoEndpoint,                    // ditto
    URI jwksEndpoint,                        // ditto
    URI logoutEndpoint,                      // optional; IdP end-session endpoint. If null, logout skips the IdP roundtrip.

    String clientId,                         // required
    String clientSecret,                     // required

    List<String> scopes,                     // default: ["openid", "profile", "email", "offline_access"]
    Function<JWT, Set<String>> roleExtractor,// default: jwt -> new HashSet<>(jwt.getList("roles", String.class))

    boolean validateAccessToken,             // default: true — verify access-token JWT locally against JWKS. When false, validate by calling userinfo on every protected request.

    String postLoginLanding,                 // default: "/"
    String postLogoutLanding,                // default: "/"
    String loginPath,                        // default: "/login"           — Authenticated redirects here; LoginHandler runs the authorize redirect
    String callbackPath,                     // default: "/oidc/return"
    String logoutPath,                       // default: "/logout"
    String logoutReturnPath,                 // default: "/oidc/logout-return"

    String stateCookieName,                  // default: "oidc_state"
    String accessTokenCookieName,            // default: "access_token"
    String refreshTokenCookieName,           // default: "refresh_token"
    String idTokenCookieName,                // default: "id_token"
    String returnToCookieName,               // default: "oidc_return_to"

    Duration refreshTokenMaxAge              // default: Duration.ofDays(30)
) {
  public static Builder builder() { return new Builder(); }

  /** Computes the callback redirect URI as {@code req.getBaseURL() + callbackPath()}. */
  public URI fullRedirectURI(HTTPRequest req) { ... }

  public static class Builder { ... }
}
```

There is no configurable `redirectURI` field. The redirect URI is always computed per-request as `req.getBaseURL() + config.callbackPath()` via `OIDCConfig.fullRedirectURI(req)`, so login and callback compute the same value as long as the request reaches the app on the same base URL.

### Required fields

- `clientId`, `clientSecret`.
- Either `issuer` OR all four of `authorizeEndpoint` / `tokenEndpoint` / `userinfoEndpoint` / `jwksEndpoint`. Mixing is allowed — explicit endpoints override discovery.

### Validation at `Builder.build()`

Throws `IllegalArgumentException` for:

- `clientId` / `clientSecret` null or blank.
- Neither `issuer` set nor all four endpoints set.
- `scopes` null or missing `"openid"`.
- `roleExtractor` null.
- Any of the five cookie names null/blank, or duplicates among them.
- Any of `callbackPath`, `logoutPath`, `logoutReturnPath` not starting with `/`, or duplicates among the three.
- `issuer`, `authorizeEndpoint`, `tokenEndpoint`, `userinfoEndpoint`, `jwksEndpoint`, `logoutEndpoint` using a non-HTTPS scheme — except `http://` is permitted when the host is `localhost`, `127.0.0.1`, or `::1` (loopback). This makes local development against `http://localhost:9011` and similar workable without weakening production defaults.

`loginPath` is not validated for `/`-prefix or distinctness from the other paths.

After running discovery (`fillIn()`), `Builder.build()` throws `IllegalStateException` for:

- `authorizeEndpoint`, `tokenEndpoint`, or `jwksEndpoint` still unresolved.
- `validateAccessToken=false` with no `userinfoEndpoint` resolvable.

`fillIn()` itself throws `IllegalStateException` (wrapping the underlying cause) if the discovery request fails: `"Failed to fetch OIDC discovery document for issuer [...]"`.

## `UnauthenticatedException`

`org.lattejava.web.UnauthenticatedException extends RuntimeException`. Thrown by `OIDC.user()` / `OIDC.jwt()` when no JWT is bound (i.e., route isn't protected or middleware hasn't run yet).

### `ExceptionHandler` default mapping

`ExceptionHandler` has a baked-in default: `UnauthenticatedException.class → 401`. User-supplied entries merge on top (user wins on key collision). The merge happens in the `ExceptionHandler` constructor; lookup walks the exception class hierarchy from most-specific to most-general before giving up and rethrowing.

## Cookies

Common attributes on every cookie set by this subsystem: `Path=/`, `SameSite=Strict`. `Secure` is set when the request scheme is `https` or `X-Forwarded-Proto: https` is present, so that local HTTP development still produces working cookies.

| Cookie           | HttpOnly             | Max-Age                                                                          |
|------------------|----------------------|----------------------------------------------------------------------------------|
| `oidc_state`     | ✅                    | session (no Max-Age — set as a transient cookie until callback consumes it)      |
| `oidc_return_to` | ✅                    | session (no Max-Age — set as a transient cookie until callback consumes it)     |
| `access_token`   | ✅                    | from token response `expires_in` (default 3600 if absent)                       |
| `refresh_token`  | ✅                    | `config.refreshTokenMaxAge()` (default 30 days)                                 |
| `id_token`       | ❌ (SPA reads claims) | same as access_token                                                             |

`Tools.addAuthCookies` is the single chokepoint for writing `id_token`, `access_token`, `refresh_token`. `Tools.addTransientCookie` writes the state and return-to cookies. `Tools.clearAllAuthCookies` clears the three auth cookies; `Tools.clearAllCookies` additionally clears state and return-to.

### State cookie + PKCE verifier (single value)

A securely-random hex string of 44 chars (22 bytes from `SecureRandom.nextBytes`, hex-encoded). Single value, three roles:

- CSRF defense: sent as the OIDC `state` parameter; callback verifies the query-param `state` equals the cookie.
- PKCE verifier: S256-hashed to produce the `code_challenge` sent in the authorize request; re-sent as `code_verifier` in the token exchange.
- Unique-per-login nonce.

Hex chars are a subset of the PKCE-allowed set; 44 hex chars satisfy PKCE's ≥43-char requirement with 128 bits of entropy.

### Return-to cookie

Set by the `Authenticated` middleware **before redirecting to `loginPath`**. Value: `req.getBaseURL() + req.getPath()`. The callback reads this cookie, clears it, and redirects there. Absent or blank → callback redirects to `config.postLoginLanding()`. Direct hits to `loginPath` (without going through `Authenticated` first) skip this step, so direct logins land on `postLoginLanding`.

## Flow walkthrough

### Authenticated middleware on an unauthenticated request

`Authenticated` does not build the authorize URL itself — it bounces the user to `config.loginPath()` and lets `OIDC.handle` route to `LoginHandler`.

1. Read the `access_token` cookie. If absent:
   1. `Tools.clearAllAuthCookies(res, config)` (defensive).
   2. `Tools.addTransientCookie(req, res, config.returnToCookieName(), req.getBaseURL() + req.getPath())`.
   3. `res.sendRedirect(config.loginPath())`.

The same sequence runs at the end of any failed validation/refresh path below.

### Login handler (`OIDC.handle` on `config.loginPath()`)

1. Generate state hex string via `SecureRandom` (22 bytes → 44 hex chars).
2. Compute `code_challenge = base64url(sha256(state))` (no padding).
3. `Tools.addTransientCookie` for `oidc_state` → state value.
4. Compute `redirectURI = config.fullRedirectURI(req)`.
5. Build the authorize URL: `authorizeEndpoint?response_type=code&client_id=...&redirect_uri=...&scope=...&state=...&code_challenge=...&code_challenge_method=S256`. The `?`/`&` separator is chosen based on whether `authorizeEndpoint` already contains a query string.
6. `res.sendRedirect(authorizeURL)`.

`LoginHandler` does **not** set the `oidc_return_to` cookie. That's the responsibility of whichever middleware redirected to `loginPath` (typically `Authenticated`).

### Callback handler (`OIDC.handle` on `config.callbackPath()`)

1. If query `error` is present: `Tools.clearAllCookies(res, config)`, then redirect to `postLoginLanding` with `?oidc_error=<error_description or error>` URL-encoded. (Separator chosen based on whether `postLoginLanding` already contains `?`.)
2. Read `oidc_state` cookie. If query `state` is null or doesn't equal the cookie → `400`.
3. Read `code` query param. Null/blank → `400`.
4. Compute `redirectURI = config.fullRedirectURI(req)`.
5. POST to `tokenEndpoint` via `Tools.postToken` with form params `grant_type=authorization_code`, `code`, `redirect_uri`, `code_verifier=<state cookie value>`. Auth: HTTP Basic with `clientId:clientSecret`.
6. Any thrown exception during the exchange → `500`. Non-2xx response → `400`.
7. Parse the response JSON: `access_token`, `id_token`, `refresh_token` (optional), `expires_in` (default 3600 if absent). If `access_token` or `id_token` is null → `400`.
8. Verify the `id_token` signature against JWKS via `new JWTDecoder().decode(idToken, jwks)`. Failure → `400`.
9. When `validateAccessToken=true`, decode again against JWKS. (Note: the current code re-decodes the `id_token` rather than the access token in this branch — it sets the cookies regardless of which token was decoded a second time, and any failure → `400`.)
10. `Tools.addAuthCookies(req, res, config, idToken, accessToken, refreshToken, expiresIn)`.
11. `Tools.clearCookie` for both `oidc_state` and `oidc_return_to`.
12. Redirect: if the return-to cookie was set and non-blank, redirect there; otherwise to `postLoginLanding`.

### Protected request (`Authenticated` middleware)

1. Read `access_token` cookie. Absent → see "Authenticated middleware on an unauthenticated request" above.
2. Run `tokenValidator.validate(accessToken, accessToken=true)`. Three outcomes:
   - `Valid(jwt)` → bind and continue (step 4).
   - `NetworkError` → `503`, no cookie changes.
   - `Invalid` → attempt refresh (step 3).
3. **Refresh flow** (triggered by an `Invalid` access-token result):
   - Read `refresh_token` cookie. Absent → fall through to login redirect (clear auth cookies, set return-to, redirect to `loginPath`).
   - POST `tokenEndpoint` with `grant_type=refresh_token`, `refresh_token=<cookie value>`. Basic auth.
   - Any thrown exception, non-2xx response, or unparseable body → fall through to login redirect.
   - Parse the response: `access_token` (required), `refresh_token` (optional), `id_token` (optional), `expires_in` (default 3600).
   - Re-validate the new `access_token` against the validator. If still `Invalid` → fall through to login redirect.
   - On success: `Tools.addAuthCookies(req, res, config, newIdToken, newAccessToken, newRefreshToken, expiresIn)`. (Note: when the response omits a refresh or id token, that argument is null and `addAuthCookies` skips it — the existing cookie is left untouched.) Bind the new JWT and continue.
4. Bind `Tools.CURRENT_JWT` via `ScopedValue.where(...).call(...)`, then call `chain.next(req, res)`.

### Token validation (`TokenValidator`)

`validate(token, accessTokenFlag)` decides between local JWT validation and userinfo-based validation based on `accessTokenFlag` and `config.validateAccessToken()`:

- If `!accessTokenFlag || config.validateAccessToken()`: `new JWTDecoder().decode(token, jwks, this::validateJWT)`. `validateJWT` returns `true` iff `jwt.audience().contains(config.clientId())`. Any decode/verify exception → `Result.Invalid()`. (Signature, `iss`, `exp`, `nbf` checks come from `JWTDecoder` and the JWKS-backed verifier; the application layer adds the audience check.)
- Otherwise (`accessTokenFlag=true` AND `validateAccessToken=false`): `GET userinfoEndpoint` with `Authorization: Bearer <token>` via the shared `Tools.HTTP` client.
  - 200 → parse the body, wrap into a `JWT` via `Tools.userinfoToJWT` (claims copied verbatim from the JSON body, signature empty), run the audience check, return `Result.Valid(jwt)` if it passes else `Result.Invalid`.
  - 401 → `Result.Invalid`.
  - Any other status, exception, or unreadable body → `Result.NetworkError`.

The validator returns a sealed `Result` (`Valid` / `Invalid` / `NetworkError`); `Authenticated` switches on those.

### Logout (`OIDC.handle` on `config.logoutPath()`)

1. If `logoutEndpoint` is null:
   - `Tools.clearAllCookies(res, config)`.
   - `res.sendRedirect(config.postLogoutLanding())`.
2. Otherwise:
   - Read the `id_token` cookie (may be null).
   - Compute `returnURI = req.getBaseURL() + config.logoutReturnPath()`.
   - Build the end-session URL: `logoutEndpoint?post_logout_redirect_uri=...&client_id=...` (separator chosen based on whether `logoutEndpoint` already has a query string), and append `&id_token_hint=...` if the `id_token` cookie is set.
   - `res.sendRedirect(...)`. **No cookies are cleared at this step** — the `id_token` is still needed by the IdP. Cookies are cleared on the return trip.

### Logout return (`OIDC.handle` on `config.logoutReturnPath()`)

1. `Tools.clearAllCookies(res, config)` — clears `access_token`, `id_token`, `refresh_token`, `oidc_state`, `oidc_return_to`.
2. `res.sendRedirect(config.postLogoutLanding())`.

`logoutReturnPath` does not verify anything — it's safe to hit directly. A user arriving here without having been through the IdP flow still ends up logged out and at the landing page.

## Discovery and JWKS

### Discovery

Triggered at `OIDCConfig.Builder.build()`, before validation completes:

1. If `issuer` is null, skip discovery; all four endpoints must already be explicit.
2. Otherwise call `OpenIDConnect.discover(issuer)` (from `org.lattejava.jwt`). Failures wrap into `IllegalStateException("Failed to fetch OIDC discovery document for issuer [...]")` and abort the build.
3. For each endpoint (`authorize`, `token`, `userinfo`, `jwks`, `logout`), fill the field if it's still null and the discovery document advertises it. Explicitly-set endpoints win.

After `fillIn()` runs, `build()` checks that `authorize`, `token`, and `jwks` are non-null and that `userinfo` is non-null when `validateAccessToken=false`.

### JWKS

- Constructed at `OIDC.create(...)` via `JWKS.fromJWKS(config.jwksEndpoint().toString()).build()`, using all defaults of the `JWKS` library. This means: a single fetch at construction (with the library's standard timeout / retry behavior), no scheduled background refresh, and no automatic refresh-on-miss.
- Token verification happens through `JWTDecoder().decode(token, jwks, ...)`; the verifier picks the JWK by `kid` from the cached snapshot.
- There is no JWKS-refresh-on-unknown-`kid` retry built into the OIDC layer. A token signed with a key not present in the cached JWKS will be rejected as `Result.Invalid` and trigger the refresh flow → login redirect path. Rotating signing keys requires either restarting the app or enabling JWKS scheduled-refresh on the underlying `JWKS` object (not currently exposed as a config option here).

## Role extraction

- Default extractor: `jwt -> new HashSet<>(jwt.getList("roles", String.class))`.
- The middleware (`HasAnyRole` / `HasAllRoles`) calls the extractor once per request, passing the bound JWT — that's either the locally-validated access-token JWT (`validateAccessToken=true`) or the userinfo-as-JWT (`validateAccessToken=false`).
- Nested/namespaced claims: user supplies their own lambda — e.g. for Keycloak, something like `jwt -> new HashSet<>(jwt.getList("realm_access.roles", String.class))` (depending on what the JWT library exposes for nested access).
- The extractor must return a `Set<String>` (changed from the originally-designed `Iterable<String>`); `HasAllRoles` calls `containsAll`, `HasAnyRole` does a stream `anyMatch`.
- The default extractor uses `jwt.getList(...)`, which throws if the claim is absent or not a list. If your IdP omits `roles`, supply your own extractor that returns an empty set.

## `HasAnyRole` / `HasAllRoles` semantics

Both are public types in `org.lattejava.web.oidc` with package-private constructors; construction goes through `oidc.hasAnyRole(...)` / `oidc.hasAllRoles(...)`.

- They do **not** authenticate. Install `oidc.authenticated()` upstream of these. A request that reaches `HasAnyRole`/`HasAllRoles` without a bound `CURRENT_JWT` is treated as a configuration error and gets `401`.
- Empty varargs is rejected at construction with `IllegalArgumentException("At least one role must be provided")`.
- `hasAnyRole(r1, r2, ...)` → pass if the user's role set contains at least one named role; otherwise `401`.
- `hasAllRoles(r1, r2, ...)` → pass if the user's role set contains every named role; otherwise `401`.
- (Authorization failures are returned as `401`, not `403`. See "Caveats" below.)

## Usage

```java
var config = OIDCConfig.builder()
    .issuer("https://id.lattejava.org")
    .clientId("repo-webapp")
    .clientSecret(System.getenv("OIDC_CLIENT_SECRET"))
    .roleExtractor(jwt -> new HashSet<>(jwt.getList("realm_access.roles", String.class)))
    .refreshTokenMaxAge(Duration.ofDays(60))
    .build();

var oidc = OIDC.create(config, jwt -> new AppUser(jwt.subject(), jwt.getString("email")));

web.install(oidc);  // system middleware: login + callback + logout + logout-return

web.prefix("/app", app -> {
  app.install(oidc.authenticated());
  app.get("/me", (req, res) -> {
    AppUser user = oidc.user();
    res.getWriter().write("hello " + user.email());
  });
  app.prefix("/admin", admin -> admin.install(oidc.hasAnyRole("admin")));
});
```

## Testing plan

Phased (tests paired with implementation). Grouped by feature area.

Each test case is tagged with its required environment:

- **[Local]** — no IdP interaction. Pure unit/middleware behavior.
- **[FA]** — runs against a FusionAuth instance booted from `src/test/docker/kickstart/kickstart.json`.
- **[Mock]** — needs a mocked IdP with canned responses. Used for anything requiring a specific token/userinfo response, a specific error, or a forced failure mode.

When using FusionAuth, the JDK `HttpClient` (shared `Tools.HTTP`) calls FusionAuth's OAuth endpoints (`authorize`, `token`, `userinfo`, `jwks`, `openid-configuration`) — full logins are exercised end-to-end.

FusionAuth is pre-provisioned via the kickstart file, which creates: test users, applications for various role/policy combinations, a tenant with a tenant-scoped issuer URL, and a lambda for custom-claim tests. Fixture users are `user@example.com` (roles `user`, `moderator`) and `admin@example.com` (role `admin`). Applications include one with refresh-token rotation enabled, one with rotation disabled, one fast-expiry app for short-TTL tests, and an app named `keycloak` whose lambda injects a nested `realm_access.roles` claim. Tests consume these fixtures; they do not create them.

A few tests still drive the FusionAuth API at runtime (creating or deleting key-pairs, configuring an app's signing key) — those are noted in the relevant test cases.

The tests should always prefer using FusionAuth versus a mock.

### Config + validation
- **[Local]** Required fields rejected as null/blank.
- **[FA]** Issuer-only config: discovery populates endpoints. Use the kickstart standard tenant's issuer URL.
- **[Local]** All-explicit config: no discovery, endpoints used as given.
- **[Local]** Neither issuer nor all explicit endpoints → `IllegalArgumentException`.
- **[Local]** Scopes must contain "openid".
- **[Local]** Cookie names must be pairwise distinct.
- **[Local]** Localhost `http://` issuer is permitted.
- **[Local]** Non-localhost `http://` issuer / endpoint → `IllegalArgumentException`.

### Discovery + JWKS
- **[FA]** Discovery parses a spec-compliant document.
- **[FA]** Explicit endpoints override discovery.
- **[Local]** Unreachable issuer → `IllegalStateException` at `Builder.build()`. Point at a closed localhost port.
- **[FA]** Token signed with a key present in JWKS verifies. (JWKS refresh-on-miss and scheduled refresh are not configured in the OIDC layer; rotation-recovery tests are not part of v1.)

### Login redirect
All login-redirect tests use FusionAuth's discovery document to populate endpoints; the tested behavior is local (URL construction + cookie setting). No actual redirect is followed.
- **[FA]** Unauthenticated request to a route protected by `authenticated()` → 302 to `loginPath`, with the `oidc_return_to` cookie set to `req.getBaseURL() + req.getPath()`.
- **[FA]** GET `loginPath` directly → 302 to authorize URL.
- **[FA]** Authorize URL contains `client_id`, `redirect_uri`, `scope`, `state`, `code_challenge`, `code_challenge_method=S256`, `response_type=code`.
- **[FA]** `oidc_state` cookie set with a 44-hex-char random value, transient, HttpOnly, SameSite=Strict.
- **[FA]** `code_challenge` equals `base64url(sha256(state_cookie_value))` (no padding).
- **[FA]** `redirect_uri` equals `req.getBaseURL() + callbackPath`.

### Callback
- **[Local]** Missing `oidc_state` cookie → 400.
- **[Local]** State cookie value ≠ query state → 400.
- **[Local]** Missing/blank `code` query param → 400.
- **[Local]** Error query param → clear all cookies, redirect to `postLoginLanding` with `oidc_error` query param.
- **[FA]** Successful code exchange → sets access/id/refresh cookies with correct attributes and Max-Age (`expires_in` for access/id, `refreshTokenMaxAge` for refresh).
- **[FA]** Return-to cookie honored when present; falls back to `postLoginLanding` when absent or blank.
- **[Mock]** Token-endpoint exception during exchange → 500.
- **[Mock]** Token-endpoint non-2xx response → 400.
- **[Mock]** Token-endpoint 2xx but missing `access_token` or `id_token` → 400.
- **[Mock]** Invalid ID token signature → 400.

### Protected middleware
- **[FA]** Valid access token → binds `CURRENT_JWT`, calls next.
- **[FA]** `oidc.user()` returns the translated value inside a protected route.
- **[Local]** `OIDC.jwt()` outside a protected route → throws `UnauthenticatedException`.
- **[Local]** `OIDC.optionalJWT()` returns `Optional.empty()` outside.
- **[FA]** Missing access token → clears auth cookies, sets `oidc_return_to`, redirects to `loginPath`.
- **[FA]** Userinfo `NetworkError` from validator (`validateAccessToken=false` mode, 5xx or exception) → 503, no cookie changes.

### Role-based protection
- **[FA]** `hasAnyRole("admin")` with `admin@example.com` → passes.
- **[FA]** `hasAnyRole("admin")` with `user@example.com` (roles `user`, `moderator`) → 401.
- **[FA]** `hasAllRoles("user", "moderator")` with `user@example.com` → passes.
- **[FA]** `hasAllRoles("user", "moderator")` with `admin@example.com` (only `admin`) → 401.
- **[FA]** Custom `roleExtractor` resolves nested claims correctly. Uses the kickstart-provisioned `keycloak` app (lambda injects `realm_access.roles`).
- **[Local]** `HasAnyRole` / `HasAllRoles` reached with no bound JWT → 401 (configuration error).
- **[Local]** Empty varargs → `IllegalArgumentException` at construction.

### Refresh flow
All refresh tests need controlled token-endpoint responses.
- **[FA]** Expired access token + valid refresh token → refresh succeeds, new `access_token` cookie set, request proceeds with refreshed JWT bound. Driven by the kickstart's fast-expiry tenant/app (1-second TTL): issue a JWT, sleep, re-issue the protected request.
- **[FA]** Refresh response includes new `refresh_token` → new cookie set with `refreshTokenMaxAge`. Uses the kickstart-provisioned app with refresh-token rotation enabled.
- **[FA]** Refresh response omits `refresh_token` → old cookie preserved (the omitted argument hits the null-skip in `Tools.addAuthCookies`). Uses the kickstart-provisioned app with rotation disabled.
- **[FA]** Refresh response includes new `id_token` → new cookie set.
- **[Local]** Expired access token + no `refresh_token` cookie → clear auth cookies, set return-to, redirect to `loginPath`.
- **[FA]** Refresh endpoint returns 4xx (e.g., tampered refresh token) → clear auth cookies, set return-to, redirect to `loginPath`.
- **[Mock]** Refresh endpoint network/5xx error → clear auth cookies, set return-to, redirect to `loginPath`.
- **[Mock]** Refreshed access token fails signature verification → clear auth cookies, set return-to, redirect to `loginPath`.

### Userinfo-based validation (`validateAccessToken=false`)
- **[Local]** `validateAccessToken=false` + no `userinfoEndpoint` resolvable → `IllegalStateException` at `Builder.build()`.
- **[FA]** Access token + 200 from userinfo → valid; userinfo response wrapped as a JWT and bound to ScopedValue; request proceeds.
- **[FA]** Access token + 401 from userinfo → `Result.Invalid` → refresh flow → login redirect path.
- **[Mock]** Access token + 5xx/network error from userinfo → `Result.NetworkError` → 503, no cookie changes.

### Logout
- **[FA]** With `logoutEndpoint` configured: GET `logoutPath` with `id_token` cookie → 302 to IdP end-session URL containing `post_logout_redirect_uri` (= `baseURL + logoutReturnPath`), `client_id`, `id_token_hint`. Cookies are NOT cleared on this leg.
- **[FA]** With `logoutEndpoint` configured but no `id_token` cookie: 302 to IdP end-session URL without `id_token_hint`.
- **[Local]** Without `logoutEndpoint`: GET `logoutPath` clears all cookies inline and redirects to `postLogoutLanding`.
- **[Local]** GET `logoutReturnPath` clears `access_token`, `id_token`, `refresh_token`, `oidc_state`, `oidc_return_to` cookies (Max-Age=0) and redirects to `postLogoutLanding`.
- **[Local]** `logoutReturnPath` hit directly (never went through IdP) still succeeds: clears cookies, redirects to landing.

### System middleware scoping
- **[Local]** `web.install(oidc)` at root handles login + callback + logout + logout-return regardless of other prefixes.
- **[Local]** Installing at a prefix confines system handling to that prefix (not the intended usage; verify behavior isn't surprising).

### ExceptionHandler integration
- **[Local]** `UnauthenticatedException` thrown inside a handler → 401 response (default mapping).
- **[Local]** User-supplied `ExceptionHandler` mapping for `UnauthenticatedException` overrides the default.

## Caveats and deferred

- **Role-check failures return 401, not 403.** Both the missing-JWT misconfiguration case and the failed-role case return `401`. The original design called for `403` on authorization failures, but the current code uses `401` uniformly.
- **Callback `validateAccessToken=true` re-decodes the id_token, not the access token.** When `validateAccessToken=true`, the callback handler calls `JWTDecoder().decode(idToken, jwks)` a second time rather than decoding the access token. The cookies are still set; the only effect is that the second JWT decode never validates the access token at the callback. The runtime path (`Authenticated` + `TokenValidator`) does validate the access token on every protected request.
- **No JWKS rotation handling in the OIDC layer.** JWKS is fetched once at `OIDC.create(...)` and never refreshed. Tokens signed with a key not in the cached JWKS will be rejected and trigger the refresh-then-login-redirect path. If the IdP rotates signing keys, restart the app. Enabling `JWKS.scheduledRefresh` / `refreshOnMiss` would address this; not currently exposed via `OIDCConfig`.
- **No CSRF `state` on IdP logout.** The end-session redirect doesn't include a `state` parameter for the return trip. Acceptable because `logoutReturnPath` only clears cookies and redirects — a forged return doesn't leak or grant anything.
- **No proactive refresh.** Refresh only runs when the access token has already failed validation, not in a pre-emptive window.
- **No userinfo caching in `validateAccessToken=false` mode.** Every protected request makes a userinfo HTTP call. Latency and IdP load scale linearly with traffic; deploy with care when enabling this mode. Caching (e.g., 30-second TTL keyed by access-token hash) is a post-MVP enhancement.
- **Single `OIDC` instance per app.** `Tools.CURRENT_JWT` ScopedValue is static. Multi-tenant use-cases deferred.
- **No per-cookie HttpOnly override** in config. Hardcoded per cookie (see table). Can add later.
- **Localhost HTTP exception baked in.** `Tools.requireSecureURI` permits `http://` only for `localhost`/`127.0.0.1`/`::1`. Non-loopback HTTP is rejected. There's no opt-out for production environments that intentionally proxy HTTP internally.
- **No concurrent-refresh coordination.** Two simultaneous expired-token requests both attempt refresh; if the IdP rotates refresh tokens, one may fail. Acceptable for v1.
- **No configurable `redirectURI`.** Always computed as `req.getBaseURL() + callbackPath`. Apps behind a reverse proxy must set `X-Forwarded-*` correctly so `req.getBaseURL()` returns the externally-visible URL.

## Implementation ordering

The design is one cohesive system but the implementation split into phases. The order that was actually followed:

1. `OIDCConfig` record + Builder + validation. Tests.
2. `UnauthenticatedException` + `ExceptionHandler` default mapping.
3. Discovery wired into `OIDCConfig.Builder.build()` via the `org.lattejava.jwt` library (`OpenIDConnect.discover`). Discovery populates `logoutEndpoint` from `end_session_endpoint` when advertised.
4. JWKS construction via `JWKS.fromJWKS(url).build()` at `OIDC.create(...)`.
5. `OIDC<U>` skeleton: factories, ScopedValue (on `Tools`), `user()`/`jwt()` methods.
6. `LoginHandler` (authorize redirect, PKCE) + system-middleware dispatch on `loginPath`.
7. `Authenticated` middleware: missing-token redirect to `loginPath` (with return-to cookie).
8. `CallbackHandler` (code exchange, id-token verify, cookies, return-to redirect).
9. `TokenValidator` covering both validation modes; `Authenticated` wires it in for protected requests.
10. Refresh flow inside `Authenticated` (shared between both validation modes).
11. `HasAnyRole` / `HasAllRoles`.
12. `LogoutHandler` and `LogoutReturnHandler`.

Each phase shipped with its tests; test coverage is currently focused on `OIDCConfigTest`, with broader integration tests pending against the kickstart fixture.
