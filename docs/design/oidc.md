# OpenID Connect

Design for OIDC authentication in Latte Web. Branch: `features/oidc`.

All classes live in `org.lattejava.web.oidc` except `UnauthenticatedException`, which lives in `org.lattejava.web` because non-OIDC code may throw/catch it.

IdP-agnostic: no external OIDC/OAuth2 library. All code written in this project.

## Scope

One cohesive subsystem covering:

- Configuration (`OIDCConfig`).
- Runtime class that is both the API entry point AND the system middleware (`OpenIDConnect<U>`).
- Three protection middlewares: `authenticated()`, `hasAnyRole()`, `hasAllRoles()`.
- Login-redirect flow with PKCE (`S256`).
- OIDC Discovery (`{issuer}/.well-known/openid-configuration`) with explicit-endpoint override.
- JWKS fetch + caching for token (access and ID) signature verification.
- Callback handler (code exchange → set cookies → redirect to return-to).
- Logout handler with optional IdP RP-initiated logout (redirect to IdP end-session endpoint → IdP redirects back to a dedicated return path → clear cookies → redirect to landing).
- Refresh-token cookie (lifetime configurable).
- Request-scoped access to the JWT via `ScopedValue`, optionally typed via translator.

## Package layout

```
org.lattejava.web
└── UnauthenticatedException                 (extends RuntimeException)

org.lattejava.web.oidc
├── OpenIDConnect<U>                         (implements Middleware)
├── OIDCConfig                               (record with Builder)
├── Authenticated                            (implements Middleware)
├── HasAnyRole                               (implements Middleware)
├── HasAllRoles                              (implements Middleware)
└── (internal: DiscoveryClient, JWKSCache, ...)
```

The three protection middlewares are **top-level public classes**, not inner/internal types. `OpenIDConnect.authenticated()` / `.hasAnyRole(...)` / `.hasAllRoles(...)` are thin factory methods that construct them — useful for readability but not required. A developer can construct the middlewares directly:

```java
web.prefix("/app", app -> {
  app.install(new Authenticated(oidc));
  app.prefix("/admin", admin -> admin.install(new HasAnyRole(oidc, "admin")));
});
```

All three take an `OpenIDConnect<?>` in their constructor and delegate JWT validation, JWKS access, redirect-URL computation, and ScopedValue binding to it via package-private methods shared inside `org.lattejava.web.oidc`.

## `OpenIDConnect<U>`

```java
public class OpenIDConnect<U> implements Middleware {
  private static final ScopedValue<JWT> CURRENT_JWT = ScopedValue.newInstance();

  private final OIDCConfig config;
  private final Function<JWT, U> translator;
  // internal: discovered endpoints, JWKS cache, HTTP client

  public static OpenIDConnect<JWT> create(OIDCConfig config) {
    return new OpenIDConnect<>(config, Function.identity());
  }

  public static <U> OpenIDConnect<U> create(OIDCConfig config, Function<JWT, U> translator) {
    return new OpenIDConnect<>(config, translator);
  }

  /** System-endpoint middleware: handles callback, logout, and logout-return paths; passes through otherwise. */
  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    String path = req.getPath();
    if (path.equals(config.callbackPath())) { handleCallback(req, res); return; }
    if (path.equals(config.logoutPath())) { handleLogout(req, res); return; }
    if (path.equals(config.logoutReturnPath())) { handleLogoutReturn(req, res); return; }
    chain.next(req, res);
  }

  /** Convenience factory: {@code new Authenticated(this)}. */
  public Authenticated authenticated() { return new Authenticated(this); }

  /** Convenience factory: {@code new HasAnyRole(this, roles)}. */
  public HasAnyRole hasAnyRole(String... roles) { return new HasAnyRole(this, roles); }

  /** Convenience factory: {@code new HasAllRoles(this, roles)}. */
  public HasAllRoles hasAllRoles(String... roles) { return new HasAllRoles(this, roles); }

  /** Translated user for the current request. Throws UnauthenticatedException if not bound. */
  public U user() {
    if (!CURRENT_JWT.isBound()) throw new UnauthenticatedException();
    return translator.apply(CURRENT_JWT.get());
  }

  /** Translated user, or empty if not bound. */
  public Optional<U> optionalUser() {
    return CURRENT_JWT.isBound() ? Optional.of(translator.apply(CURRENT_JWT.get())) : Optional.empty();
  }

  /** Raw JWT. Throws UnauthenticatedException if not bound. */
  public static JWT jwt() {
    if (!CURRENT_JWT.isBound()) throw new UnauthenticatedException();
    return CURRENT_JWT.get();
  }

  /** Raw JWT, or empty if not bound. */
  public static Optional<JWT> optionalJWT() {
    return CURRENT_JWT.isBound() ? Optional.of(CURRENT_JWT.get()) : Optional.empty();
  }
}
```

## `OIDCConfig`

```java
public record OIDCConfig(
    String issuer,                           // optional — triggers discovery
    URI authorizeEndpoint,                   // optional if discovery resolves it
    URI tokenEndpoint,                       // ditto
    URI userinfoEndpoint,                    // ditto
    URI jwksEndpoint,                        // ditto
    URI logoutEndpoint,                      // optional; IdP end-session endpoint (OIDC RP-Initiated Logout). If null, logout skips the IdP roundtrip.

    String clientId,                         // required
    String clientSecret,                     // required

    URI redirectURI,                         // optional; computed as baseURL + callbackPath when null

    List<String> scopes,                     // default: ["openid", "profile", "email", "offline_access"]
    Function<JWT, Iterable<String>> roleExtractor, // default: jwt -> jwt.get("roles")

    boolean validateAccessToken,             // default: true — verify access-token JWT locally against JWKS. When false, validate by calling userinfo on every protected request.

    String postLoginLanding,                 // default: "/"
    String postLogoutLanding,                // default: "/"
    String callbackPath,                     // default: "/oidc/return"
    String logoutPath,                       // default: "/oidc/logout"
    String logoutReturnPath,                 // default: "/oidc/logout-return"

    String stateCookieName,                  // default: "oidc_state"
    String accessTokenCookieName,            // default: "access_token"
    String refreshTokenCookieName,           // default: "refresh_token"
    String idTokenCookieName,                // default: "id_token"
    String returnToCookieName,               // default: "oidc_return_to"

    Duration refreshTokenMaxAge              // default: Duration.ofDays(30)
) {
  public static Builder builder() { return new Builder(); }
  public static class Builder { ... }
}
```

### Required fields

- `clientId`, `clientSecret`.
- Either `issuer` OR all four of `authorizeEndpoint` / `tokenEndpoint` / `userinfoEndpoint` / `jwksEndpoint`. Mixing is allowed — explicit endpoints override discovery.

### Validation at build

- At least one of `{issuer set}` or `{all four endpoints set}`. Otherwise `IllegalArgumentException`. `logoutEndpoint` is independent — always optional.
- `clientId`, `clientSecret` non-blank.
- `scopes` contains `"openid"`.
- `roleExtractor` non-null (defaulted).
- All cookie names non-blank and pairwise distinct.
- `callbackPath`, `logoutPath`, `logoutReturnPath` start with `/` and are pairwise distinct.
- `issuer`, `redirectURI`, `logoutEndpoint` (each if set) are HTTPS.
- If `validateAccessToken` is `false`, `userinfoEndpoint` must be resolvable (either explicitly set or discovered). Otherwise `IllegalStateException` at `OpenIDConnect.create()`.

## `UnauthenticatedException`

`org.lattejava.web.UnauthenticatedException extends RuntimeException`. Thrown by `user()` / `jwt()` when no JWT is bound (i.e., route isn't protected or middleware hasn't run yet).

### `ExceptionHandler` default mapping

`ExceptionHandler` gains a baked-in default: `UnauthenticatedException.class → 401`. Merges with user-supplied mapping (user-supplied wins if they override). Small behavior change; callout in the `ExceptionHandler` javadoc.

## Cookies

All cookies: `Secure`, `SameSite=Strict`, `Path=/`.

| Cookie           | HttpOnly             | Max-Age                                                       |
|------------------|----------------------|---------------------------------------------------------------|
| `oidc_state`     | ✅                    | session (until callback consumes it)                          |
| `oidc_return_to` | ✅                    | session (until callback consumes it)                          |
| `access_token`   | ✅                    | from token response `expires_in` (or ID-token `exp` fallback) |
| `refresh_token`  | ✅                    | `config.refreshTokenMaxAge()`                                 |
| `id_token`       | ❌ (SPA reads claims) | same as access_token                                          |

### State cookie + PKCE verifier (single value)

A securely-random hex string ≥ 44 chars (22 bytes from `SecureRandom.nextBytes`, hex-encoded). This value serves three roles at once:

- CSRF defense: sent as the OIDC `state` parameter; callback verifies the query-param `state` equals the cookie.
- PKCE verifier: S256-hashed to produce the `code_challenge` sent in the authorize request; re-sent as `code_verifier` in the token exchange.
- Unique-per-login nonce.

Hex chars are a subset of the PKCE-allowed set; 44 hex chars satisfy PKCE's ≥43-char requirement with 128 bits of entropy.

### Return-to cookie

Set alongside the state cookie when `authenticated()` triggers a login redirect. Value: the original request URL (or path). Callback reads it, clears it, redirects there. Absent → redirect to `config.postLoginLanding()`.

## Flow walkthrough

### Login redirect (triggered by `authenticated()` middleware on unauthenticated request)

1. Generate state hex string via `SecureRandom` (≥ 44 chars).
2. Compute `code_challenge = base64url(sha256(state))`.
3. Compute `redirectURI` — use `config.redirectURI()` if set, else `req.getBaseURL() + config.callbackPath()`.
4. Set `oidc_state` cookie (state hex, HttpOnly, Secure, SameSite=Strict).
5. Set `oidc_return_to` cookie (request URL, HttpOnly, Secure, SameSite=Strict).
6. Build authorize URL: `authorizeEndpoint?response_type=code&client_id=...&redirect_uri=...&scope=...&state=...&code_challenge=...&code_challenge_method=S256`.
7. `res.sendRedirect(authorizeURL)`.

### Callback (`OpenIDConnect.handle` on `config.callbackPath()`)

1. Parse query: `state`, `code`, `error`, `error_description`.
2. If `error` present: clear state + return-to cookies, redirect to `postLoginLanding` with `?oidc_error=...`.
3. Read `oidc_state` cookie. Missing or not equal to query `state` → 400 (no body). Potential CSRF.
4. Compute `redirectURI` same way as login redirect (must match exactly).
5. POST to `tokenEndpoint` with: `grant_type=authorization_code`, `code`, `redirect_uri`, `code_verifier=<state cookie value>`. Basic-auth the client credentials.
6. Parse response: `access_token`, `id_token`, `refresh_token` (optional), `expires_in`.
7. Verify the `id_token` signature against JWKS. Check `iss`, `aud`, `exp`, `nbf`. Reject if bad. When `validateAccessToken=true`, also parse and verify the `access_token` as a JWT against the same checks. ID-token verification is unconditional (OIDC spec mandate); access-token verification is skipped when `validateAccessToken=false` to support IdPs that issue opaque access tokens.
8. Set cookies: `access_token` (HttpOnly, Max-Age=expires_in), `id_token` (non-HttpOnly, Max-Age=expires_in), `refresh_token` (HttpOnly, Max-Age=`refreshTokenMaxAge`, if present in response).
9. Clear `oidc_state` and `oidc_return_to` cookies.
10. Redirect to return-to value (or `postLoginLanding` if absent).

### Protected request (`authenticated()` middleware)

1. Read `access_token` cookie.
   - Absent → trigger login redirect (section above).
2. Validate the access token. Two modes:
   - **`validateAccessToken=true` (default, local JWT validation):**
     a. Parse + verify access-token JWT signature against JWKS. Check `iss`, `aud`.
        - Signature failure with unknown `kid` → refresh JWKS once and retry.
        - Signature failure after JWKS refresh → clear auth cookies, trigger login redirect.
     b. Check `exp`.
        - Not expired → valid; continue.
        - Expired → attempt refresh (step 3).
   - **`validateAccessToken=false` (userinfo-based validation):**
     a. GET `userinfoEndpoint` with `Authorization: Bearer <access_token>`.
        - 200 → access token is valid; continue.
        - 401 → expired/revoked; attempt refresh (step 3).
        - Network error / 5xx → 503, no cookie changes.
     b. The access token is NOT required to be a JWT here (IdPs that issue opaque tokens are supported). Identity claims come from the `id_token` cookie (see step 4).
3. **Refresh flow** (triggered by invalid access token in either mode):
   - Read `refresh_token` cookie. Absent → clear auth cookies, trigger login redirect.
   - POST to `tokenEndpoint` with `grant_type=refresh_token`, `refresh_token=<cookie value>`, client credentials via Basic auth.
   - On 4xx (refresh token expired/revoked) or network/5xx failure → clear auth cookies, trigger login redirect.
   - On success: set new `access_token` cookie (Max-Age from response `expires_in`). If response includes a new `refresh_token`, set it (Max-Age from `refreshTokenMaxAge`). If response includes a new `id_token`, set it. Re-run step 2 on the refreshed access token.
4. Resolve the JWT to bind:
   - `validateAccessToken=true` → bind the access-token JWT (already parsed in step 2a).
   - `validateAccessToken=false` → bind the response from userinfo marshalled into a JWT object. This won't have a signature, but it is still a valid JWT. See https://openid.net/specs/openid-connect-core-1_0.html#UserInfo for details.
5. Bind `CURRENT_JWT` ScopedValue with the resolved JWT; call `chain.next`.
6. For `hasAnyRole` / `hasAllRoles`: after step 5's binding, extract roles via `config.roleExtractor()`; verify membership; if missing → 403.

**Refresh is reactive, not proactive.** We only refresh when the access token has already expired. Proactive refresh (e.g., last 60s window) is a possible future enhancement; it adds complexity to handle clock skew and concurrent-request coordination without a real user-facing benefit at this stage.

**Concurrent-request race.** Two simultaneous protected requests on the same browser session, both with the just-expired access token, will both attempt refresh. Each sends the same `refresh_token`; the IdP typically issues consistent results. If refresh-token rotation is enabled at the IdP, one response may invalidate the other's newly-issued refresh token, producing a spurious login redirect on the losing request. Acceptable for v1; a request-scoped mutex can be added later if it becomes a real user issue.

### Logout (`OpenIDConnect.handle` on `config.logoutPath()`)

1. If `logoutEndpoint` is configured:
   - Read the `id_token` cookie. If present, use as the `id_token_hint` query param (the IdP uses it to identify which session to end).
   - Compute `post_logout_redirect_uri` as `req.getBaseURL() + config.logoutReturnPath()` — must be pre-registered with the IdP.
   - Build the end-session URL: `logoutEndpoint?id_token_hint=...&post_logout_redirect_uri=...&client_id=...`.
   - `res.sendRedirect(endSessionURL)`.
   - Cookies are cleared on the return trip, not here — the IdP needs the `id_token` until it handles the end-session request.
2. If `logoutEndpoint` is NOT configured:
   - Skip the IdP roundtrip. Fall through to the logout-return behavior inline: clear cookies and redirect to `postLogoutLanding`.

### Logout return (`OpenIDConnect.handle` on `config.logoutReturnPath()`)

Entry point when the IdP redirects back after its end-session flow completes.

1. Clear `access_token`, `id_token`, `refresh_token` cookies (Max-Age=0). Also clear any lingering `oidc_state`, `oidc_return_to` cookies defensively.
2. Redirect to `config.postLogoutLanding()`.

Note: `logoutReturnPath` does not verify anything — it's safe to hit directly (the worst case is "already-logged-out user gets redirected to the landing page"). Some IdPs may not redirect back reliably; users arriving here without having been through the IdP flow still get the expected outcome.

### Refresh flow

Part of v1. See the "Protected request" flow above — when the access token is expired, the middleware exchanges the `refresh_token` cookie for a new access token (and possibly new refresh/id tokens) against `tokenEndpoint`, sets the new cookies, and continues the request. Only when the refresh itself fails do we fall back to a login redirect. This is reactive (triggered by observed expiry), not proactive (pre-emptive).

## Discovery and JWKS

### Discovery

Triggered at `OpenIDConnect.create(...)`:

1. If `config.issuer()` is set, GET `{issuer}/.well-known/openid-configuration`.
2. Parse response. Populate any unset endpoint from the discovery document.
3. Explicit endpoints in the config override discovered ones.
4. Fail construction with a clear exception if a required endpoint is still unresolved.

If `issuer` is null, skip discovery; all four endpoints must be explicit.

### JWKS

- Fetched at `OpenIDConnect.create(...)` from `jwksEndpoint`.
- Parsed into `kid`-keyed map of public keys.
- Refreshed every hour on a background daemon thread, OR on verification failure (handles rotation).
- Token verification: look up key by `kid` in the token header; verify signature.

## Role extraction

- Default: `jwt -> jwt.get("roles")` returns an `Iterable<String>`.
- The middleware calls the extractor once per request passing it the validated access token if **validateAccessToken=true** or the response from userinfo if **validateAccessToken=false**, converts to `Set<String>`.
- Nested/namespaced claims: user supplies their own lambda — e.g. `jwt -> jwt.get("realm_access").get("roles")` for Keycloak, or `jwt -> jwt.get("https://myapp.com/roles")` for Auth0.
- JWT library returns an array for a claim: user wraps with `List.of(array)` in the lambda.
- Null return from the extractor is treated as empty set (user has no roles).

## `hasAnyRole` / `hasAllRoles` semantics

- Both require authentication first (equivalent to composing `authenticated()`).
- `hasAnyRole(r1, r2, ...)` → pass if the user has at least one of the named roles.
- `hasAllRoles(r1, r2, ...)` → pass if the user has every named role.
- Empty varargs on either: compile-time allowed, runtime behavior = "has any of nothing" (always fails) / "has all of nothing" (always passes). Validate at install time — reject empty varargs with `IllegalArgumentException` to avoid footgun.
- Role check failure → 403 with no body. (Login redirect is for unauthenticated; role failure is authorization, not authentication.)

## Usage

```java
var config = OIDCConfig.builder()
    .issuer("https://id.lattejava.org")
    .clientId("repo-webapp")
    .clientSecret(System.getenv("OIDC_CLIENT_SECRET"))
    .roleExtractor(jwt -> jwt.get("realm_access").get("roles"))  // Keycloak nested
    .refreshTokenMaxAge(Duration.ofDays(60))
    .build();

var oidc = OpenIDConnect.create(config, jwt -> new AppUser(jwt.getSubject(), jwt.getClaim("email")));

web.install(oidc);  // system middleware: callback + logout

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
- **[FA]** — can run against real FusionAuth at `https://local.fusionauth.io`.
- **[Mock]** — needs a mocked IdP with canned responses. Used for anything requiring a specific token/userinfo response, a specific error, or a forced failure mode.

When using FusionAuth, the JDK HttpClient is used to call FusionAuth's OAuth endpoints, including `authorize`, `token`, `userinfo`, `jwks`, and `openid-configuration`. This means a complete login can be performed during a test.

FusionAuth is pre-provisioned via a Kickstart file (outside this project's scope) that creates all required objects: test users, applications for various role/policy combinations, and a lambda for custom-claim tests. Fixture users are `user@example.com` (roles `user`, `moderator`) and `admin@example.com` (role `admin`). Applications include one with refresh-token rotation enabled, one with rotation disabled, and an app named `keycloak` whose lambda injects a nested `realm_access.roles` claim into issued tokens. The tests consume these fixtures; they do not create them.

A few tests still use the FusionAuth API at runtime (creating or deleting key-pairs, configuring an app's signing key) — those are noted in the relevant test cases.

The tests should always prefer using FusionAuth versus a mock.

### Config + validation
- **[Local]** Required fields rejected as null/blank.
- **[FA]** Issuer-only config: discovery populates endpoints. Use `https://local.fusionauth.io/.well-known/openid-configuration`, it always has the same endpoints.
- **[Local]** All-explicit config: no discovery, endpoints used as given.
- **[Local]** Neither issuer nor all explicit endpoints → `IllegalArgumentException`.
- **[Local]** Scopes must contain "openid".
- **[Local]** Cookie names must be pairwise distinct.

### Discovery + JWKS
- **[FA]** Discovery parses a spec-compliant document. Use `https://local.fusionauth.io/.well-known/openid-configuration`, it always has the same endpoints.
- **[FA]** Explicit endpoints override discovery.
- **[Local]** Unreachable issuer → clear exception at `create`. Point at a closed localhost port.
- **[FA]** JWKS refresh succeeds when `kid` not found locally — token verifies after retry. Sequence: boot OIDC first (caches JWKS without K1), then create K1 in FusionAuth and configure the test app to sign with it, then log in to get a K1-signed token. Submit the token → cache miss → JWKS refresh now includes K1 → retry succeeds.
- **[FA]** JWKS refresh fails when `kid` is not in FusionAuth's JWKS at all — token verification still fails after retry, request rejected. Sequence: create K1, configure the app to sign with it, log in to get a K1-signed token, then delete K1 from FusionAuth. Boot OIDC (JWKS fetch returns no K1). Submit the K1-signed token → cache miss → refresh still has no K1 → fail.

### Login redirect
All login-redirect tests use FusionAuth's discovery document to populate endpoints; the tested behavior is local (URL construction + cookie setting). No actual redirect is followed.
- **[FA]** Unauthenticated request to protected route → 302 to authorize URL.
- **[FA]** Authorize URL contains `client_id`, `redirect_uri`, `scope`, `state`, `code_challenge`, `code_challenge_method=S256`.
- **[FA]** `oidc_state` cookie set with a 44+ hex-char random value.
- **[FA]** `code_challenge` equals `base64url(sha256(state_cookie_value))`.
- **[FA]** `oidc_return_to` cookie set to the original URL.
- **[FA]** `redirectURI` computed from `req.getBaseURL() + callbackPath` when config URL is null.
- **[FA]** Explicit `config.redirectURI()` used as-is when set.

### Callback
- **[Local]** Missing `oidc_state` cookie → 400.
- **[Local]** State cookie value ≠ query state → 400.
- **[Local]** Error query param → clear cookies, redirect to `postLoginLanding` with error marker.
- **[FA]** Successful code exchange → sets access/id/refresh cookies with correct attributes and Max-Age.
- **[FA]** Return-to cookie honored when present; falls back to `postLoginLanding` when absent.
- **[Mock]** Invalid ID token signature → clear cookies, 400. Mock token endpoint returns an id_token signed with a key not present in mock JWKS. Reproducing this against real FusionAuth is nearly impossible without production test hooks, since FA always signs with the app's current signing key.

### Protected middleware
- **[FA]** Valid access token → binds `CURRENT_JWT`, calls next.
- **[FA]** `user()` returns translated value inside a protected route.
- **[Local]** `user()` outside a protected route → throws `UnauthenticatedException`.
- **[Local]** `optionalUser()` returns `Optional.empty()` outside.
- **[FA]** Missing access token → triggers login redirect. (Uses real discovery for the authorize URL; middleware behavior is local.)
- **[FA]** `hasAnyRole("admin")` with `admin@example.com` → passes.
- **[FA]** `hasAnyRole("admin")` with `user@example.com` (roles `user`, `moderator`) → 403.
- **[FA]** `hasAllRoles("user", "moderator")` with `user@example.com` → passes.
- **[FA]** `hasAllRoles("user", "moderator")` with `admin@example.com` (only `admin`) → 403.
- **[FA]** Custom `roleExtractor` resolves nested claims correctly. Uses the Kickstart-provisioned `keycloak` app whose lambda injects a `realm_access.roles` claim into the token; test uses `roleExtractor: jwt -> jwt.get("realm_access").get("roles")` and verifies role checks pass.

### Refresh flow
All refresh tests need controlled token-endpoint responses.
- **[FA]** Expired access token + valid refresh token → refresh succeeds, new `access_token` cookie set, request proceeds with refreshed JWT bound. This will require that an initial JWT is issued with a very short expiration. The expiration time is set on a separate Tenant in FusionAuth with an expiration duration of 1 second. It issues a JWT, sleeps, and then runs the test with an expired access token. The Tenant used for this test should be separate and can always have a 1 second duration. 
- **[FA]** Refresh response includes new `refresh_token` → new cookie set with `refreshTokenMaxAge`. Uses the Kickstart-provisioned FusionAuth app with refresh-token rotation enabled.
- **[FA]** Refresh response omits `refresh_token` → old cookie preserved. Uses the Kickstart-provisioned app with refresh-token rotation disabled.
- **[FA]** Refresh response includes new `id_token` → new cookie set.
- **[Local]** Expired access token + no `refresh_token` cookie → clear auth cookies, login redirect. (Detection happens before any IdP call.)
- **[FA]** Refresh endpoint returns 400/401 → clear auth cookies, login redirect. This will require changing a character in the refresh token and trying to refresh.
- **[Mock]** Refresh endpoint network/5xx error → clear auth cookies, login redirect.
- **[Mock]** Refreshed access token fails signature verification → clear auth cookies, login redirect.
- **[FA]** Signature failure with unknown `kid` → JWKS refresh, retry once before giving up.

### Userinfo-based validation (`validateAccessToken=false`)
- **[Local]** `validateAccessToken=false` + no `userinfoEndpoint` resolvable → `IllegalStateException` at `OpenIDConnect.create()`.
- **[FA]** Access token + 200 from userinfo → valid; userinfo response wrapped as a JWT and bound to ScopedValue; request proceeds. (FusionAuth issues JWT access tokens; with `validateAccessToken=false` the middleware treats them as opaque regardless.)
- **[FA]** Access token + 401 from userinfo → refresh flow triggered. Strategy: short-TTL access token + sleep until expired, submit; userinfo returns 401.
- **[Mock]** Access token + 5xx/network error from userinfo → 503, no cookie changes.

### Logout
- **[FA]** With `logoutEndpoint` configured: request to `logoutPath` → 302 to IdP end-session URL with `id_token_hint`, `post_logout_redirect_uri` (= baseURL + `logoutReturnPath`), `client_id`. (FusionAuth discovery populates `end_session_endpoint`.)
- **[FA]** With `logoutEndpoint` configured but no `id_token` cookie: 302 to IdP end-session URL without `id_token_hint`.
- **[Local]** Without `logoutEndpoint`: request to `logoutPath` clears cookies inline and redirects to `postLogoutLanding` (no IdP roundtrip).
- **[Local]** `logoutReturnPath` clears `access_token`, `id_token`, `refresh_token`, `oidc_state`, `oidc_return_to` cookies (Max-Age=0) and redirects to `postLogoutLanding`.
- **[Local]** `logoutReturnPath` hit directly (never went through IdP) still succeeds: clears cookies, redirects to landing.

### System middleware scoping
- **[Local]** `web.install(oidc)` at root handles callback + logout regardless of other prefixes.
- **[Local]** Installing at a prefix confines system handling to that prefix (not the intended usage; just verify behavior isn't surprising).

### ExceptionHandler integration
- **[Local]** `UnauthenticatedException` thrown inside a handler → 401 response.
- **[Local]** User-supplied `ExceptionHandler` mapping overrides the default.

## Caveats and deferred

- **No CSRF `state` on IdP logout.** The end-session redirect doesn't include a `state` parameter for the return trip. Acceptable because `logoutReturnPath` only clears cookies and redirects — a forged return doesn't leak or grant anything.
- **No proactive refresh.** Refresh only runs when the access token has already expired, not in a pre-emptive window.
- **No userinfo caching in `validateAccessToken=false` mode.** Every protected request makes a userinfo HTTP call. Latency and IdP load scale linearly with traffic; deploy with care when enabling this mode. Caching (e.g., 30-second TTL keyed by access-token hash) is a post-MVP enhancement.
- **Single `OpenIDConnect` instance per app.** `CURRENT_JWT` ScopedValue is static. Multi-tenant use-cases deferred.
- **No per-cookie HttpOnly override** in config. Hardcoded per cookie (see table). Can add later.
- **No opt-out on HTTPS-enforcement** (validation refuses non-HTTPS issuer/redirectURI). Dev-mode override can be added when needed.
- **No concurrent-refresh coordination.** Two simultaneous expired-token requests both attempt refresh; if the IdP rotates refresh tokens, one may fail. Acceptable for v1.

## Implementation ordering

The design is one cohesive system but the implementation splits into phases. Rough order:

1. `OIDCConfig` record + Builder + validation. Tests.
2. `UnauthenticatedException` + `ExceptionHandler` default mapping.
3. `DiscoveryClient` + JWKS fetch + caching (internal). Discovery populates `logoutEndpoint` when the IdP advertises `end_session_endpoint`.
4. `OpenIDConnect<U>` skeleton: factories, ScopedValue, `user()`/`jwt()` methods. Tests.
5. Login-redirect logic inside `Authenticated` middleware.
6. Callback handling in `OpenIDConnect.handle`.
7. Access-token verification + ScopedValue binding in `Authenticated`. Covers both `validateAccessToken=true` (local JWT against JWKS) and `validateAccessToken=false` (call userinfo, marshal the JSON response into a JWT object with claims populated from the body and empty signature). The bound JWT is whichever form step 4 of the protected-request flow resolved.
8. Refresh flow on expired/invalid access token (shared between both validation modes).
9. `HasAnyRole` / `HasAllRoles`.
10. Logout handling: `logoutPath` (with and without `logoutEndpoint`) and `logoutReturnPath`.

Each phase ships with its tests. End-to-end test (real HTTP round-trip to a mock IdP or recorded fixture) added at phase 7+.
