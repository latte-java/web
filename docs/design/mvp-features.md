# MVP Features for Latte Repository Webapp

Tracking framework features required to build a webapp that lets developers publish artifacts to the Latte repository. Uses FusionAuth for login/registration. Auth via JWTs stored in `HttpOnly; SameSite=Strict` cookies. CSRF defense is provided by the `SameSite=Strict` cookie attribute plus an `Origin`/`Referer` check middleware — no double-submit tokens.

## Status Legend

- ✅ Done
- ⏳ In progress
- 📋 Not started
- 🔮 Deferred (post-MVP)

---

## Framework-level features

### ✅ Done

| Feature                     | Notes                                                                                                                                                                                                                                                        |
|-----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Routing                     | Verb methods (`get`, `post`, `put`, `delete`, `patch`, `options`), path parameters via `{name}`, nested `prefix()` grouping, trie-based matcher with backtracking, 404/405 responses with `Allow` header aggregation                                         |
| Middleware                  | `install(Middleware...)` for global middlewares, trailing `Middleware...` varargs on every route-registration method                                                                                                                                         |
| Body handling (interfaces)  | `BodyHandler<T>` and `BodySupplier<T>` functional interfaces; POST/PUT/PATCH route overloads; supplier-returns-null short-circuit for handled errors                                                                                                         |
| JSON body supplier          | `JSONBodySupplier<T>` in `org.lattejava.web.json` package, backed by Jackson 2.19.2                                                                                                                                                                          |
| HEAD handling               | Handled automatically by `org.lattejava.http` (routed to GET, body stripped); framework never sees HTTPMethod.HEAD                                                                                                                                           |
| Exception handling          | `ExceptionMiddleware` maps exception classes to status codes; extensible via `protected writeBody()` and `lookupStatus()` hooks                                                                                                                              |
| Response helpers            | `HTTPResponse.sendRedirect()`, `HTTPResponse.getWriter()` — provided by `org.lattejava.http`                                                                                                                                                                 |
| Cookie primitives           | `Cookie`, `addCookie`, `getCookies` — provided by `org.lattejava.http`                                                                                                                                                                                       |
| Lifecycle                   | `Web implements AutoCloseable`, JVM shutdown hook auto-registered on `start()`, registration locked after `start()` via shared `AtomicBoolean`                                                                                                               |
| Static file serving         | `web.files("/assets", Paths.get("static"))` with Content-Type inference, ETag/Last-Modified, path traversal protection. Needed to serve the SPA's CSS/JS/images.                                                                                             |
| Origin-check middleware     | Defense-in-depth CSRF layer. On POST/PUT/PATCH/DELETE, verifies the `Origin` header (falling back to `Referer`) matches an allowed origin; otherwise returns 403. Closes the sibling-subdomain gap in `SameSite=Strict` and guards against misconfiguration. |
| Security headers middleware | CSP, X-Frame-Options, HSTS, etc. Trivial middleware to write when hardening.                                                                                                                                                                                 |

### 📋 Must-have (MVP blockers)

| Feature                        | Description                                                                                                                                                                                                                |
|--------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| JWT validation middleware      | Verifies JWT signature against FusionAuth's JWKS, extracts claims, attaches user identity to request as attribute. Likely lives in a separate `latte-auth` module.                                                         |
| OIDC / FusionAuth flow helpers | Login-redirect handler, OAuth2 callback endpoint helper (code exchange, set JWT + refresh cookies, redirect). Likely in the `latte-auth` module.                                                                           |
| Refresh-token flow helper      | Middleware or helper that uses the refresh cookie to obtain a new JWT when the current one is near-expiry. Also likely in `latte-auth`.                                                                                    |

### 🔮 Deferred (post-MVP)

| Feature                     | Notes                                                                                                                                                                                   |
|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| CORS                        | Only needed if the SPA runs on a different origin than the API. For same-origin deployment, skip.                                                                                       |
| Rate limiting               | Add when the upload endpoint justifies it; not a launch blocker.                                                                                                                        |
| HTML templating             | Not needed if the frontend is a SPA. Would matter for server-rendered pages.                                                                                                            |
| Per-route body size limits  | `org.lattejava.http` has a global `maxRequestBodySize`. Artifact uploads need a higher limit; ordinary JSON endpoints should stay smaller. Expose a per-route override as a middleware. |
| Request logging middleware  | Canonical access-log middleware: method, path, status, duration. Cheap to build on existing middleware; avoids every app rewriting it.                                                  |

---

## Webapp-level features (built on the framework)

### 📋 Authentication / Authorization

| Feature                  | Description                                                                                                                                     |
|--------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| Login redirect endpoint  | Redirects unauthenticated users to FusionAuth with `client_id`, `redirect_uri`, `state`, `code_challenge` (PKCE).                               |
| OAuth2 callback endpoint | Receives the authorization code, exchanges it for JWT + refresh tokens via FusionAuth's token endpoint, sets the cookies, redirects to the SPA. |
| Logout endpoint          | Clears JWT + refresh + CSRF cookies; optionally redirects to FusionAuth's logout endpoint.                                                      |
| Authorization middleware | After JWT validation, checks the authenticated user's roles/groups against the target resource (e.g., "user can publish to this group").        |

### 📋 Artifact operations

| Feature             | Description                                                                                                       |
|---------------------|-------------------------------------------------------------------------------------------------------------------|
| Upload endpoint     | Maven-style layout: `PUT /{group}/{artifact}/{version}/{file}`. Accepts JAR/POM/sources/javadoc + checksum files. |
| Checksum validation | Validate uploaded SHA-1, SHA-256, MD5 against the artifact bytes.                                                 |
| Immutability policy | Reject re-uploads of existing versions (except SNAPSHOT per configured policy).                                   |
| Owner check         | The uploading user must own the target group.                                                                     |
| Browse / search     | `GET /{group}/...` lists artifacts, versions, files. Provides Maven-compatible directory listings.                |
| Download            | `GET /{group}/{artifact}/{version}/{file}` streams the artifact.                                                  |
| Metadata endpoints  | `maven-metadata.xml` generation per artifact.                                                                     |

### 📋 UI (SPA)

| Feature          | Description                                                                             |
|------------------|-----------------------------------------------------------------------------------------|
| Dashboard        | Shows the user's groups, recent uploads, quick actions.                                 |
| Browse           | Searchable artifact tree.                                                               |
| Upload page      | Primarily for docs/manual uploads; most uploads will come from build tools via the API. |
| Group management | Create groups, manage members (invite, remove, role change).                            |
| Account settings | Link to FusionAuth for profile edits.                                                   |

---

## Next framework tasks (suggested order)

1. **Static file serving** — unblocks the SPA host.
2. **Origin-check middleware** — defense-in-depth CSRF layer alongside `SameSite=Strict` on the JWT cookie.
3. **`latte-auth` module** (separate repo/module) — JWT validation, OIDC client, refresh flow.
4. **Per-route body size limits** — needed for the upload endpoint to accept large artifacts without raising the global limit.
5. **Request logging middleware** — quality-of-life; can land any time.

## Notes on CSRF for the SPA

The JWT lives in an `HttpOnly; SameSite=Strict` cookie. `SameSite=Strict` is the primary CSRF defense: browsers will not attach the cookie to any request initiated from a different site, so a classic cross-site forged POST arrives unauthenticated and is rejected by the auth middleware with 401.

We do **not** implement the double-submit token pattern. Since Chrome enforced `SameSite=Lax` by default in 2020 and OWASP softened their position in 2023, token-based CSRF defenses are widely considered belt-and-suspenders when `SameSite=Strict` is in place on a single-host deployment. Tokens add complexity (key management or cookie/header dance, issuance on safe-method responses, SPA JS to read-and-echo) without meaningful gain for this app.

### Remaining gaps, and what closes them

| Gap                                                                                | Mitigation                                                                                                            |
|------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| Sibling-subdomain XSS (SameSite scopes to eTLD+1)                                  | Host the API on a single origin; Origin-check middleware rejects requests whose `Origin` does not match               |
| Developer accidentally sets a new cookie without `SameSite=Strict`                 | Origin-check middleware is method-agnostic about cookies — still rejects mutation requests with the wrong `Origin`    |
| Proxy/CDN rewrites or drops the `SameSite` attribute                               | Origin-check middleware is independent of cookies                                                                     |
| `Origin` parser has a bug                                                          | Accept `Origin` OR `Referer`; reject when both are missing or malformed                                               |

### Origin-check middleware

On POST/PUT/PATCH/DELETE, the middleware reads `Origin` (falling back to `Referer`), parses it, and compares scheme+host+port against the configured allowed set. Mismatch or missing → 403. GET/HEAD/OPTIONS skip the check. This is the same pattern Prime MVC uses, minus the token half.
