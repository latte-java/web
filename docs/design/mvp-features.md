# MVP Features for Latte Repository Webapp

Tracking framework features required to build a webapp that lets developers publish artifacts to the Latte repository. Uses FusionAuth for login/registration. Auth via JWTs stored in `HttpOnly; SameSite=Strict` cookies, with a separate JS-readable CSRF token cookie for the SPA frontend.

## Status Legend

- ✅ Done
- ⏳ In progress
- 📋 Not started
- 🔮 Deferred (post-MVP)

---

## Framework-level features

### ✅ Done

| Feature | Notes |
|---------|-------|
| Routing | Verb methods (`get`, `post`, `put`, `delete`, `patch`, `options`), path parameters via `{name}`, nested `prefix()` grouping, trie-based matcher with backtracking, 404/405 responses with `Allow` header aggregation |
| Middleware | `install(Middleware...)` for global middlewares, trailing `Middleware...` varargs on every route-registration method |
| Body handling (interfaces) | `BodyHandler<T>` and `BodySupplier<T>` functional interfaces; POST/PUT/PATCH route overloads; supplier-returns-null short-circuit for handled errors |
| JSON body supplier | `JSONBodySupplier<T>` in `org.lattejava.web.json` package, backed by Jackson 2.19.2 |
| HEAD handling | Handled automatically by `org.lattejava.http` (routed to GET, body stripped); framework never sees HTTPMethod.HEAD |
| Exception handling | `ExceptionMiddleware` maps exception classes to status codes; extensible via `protected writeBody()` and `lookupStatus()` hooks |
| Response helpers | `HTTPResponse.sendRedirect()`, `HTTPResponse.getWriter()` — provided by `org.lattejava.http` |
| Cookie primitives | `Cookie`, `addCookie`, `getCookies` — provided by `org.lattejava.http` |
| Lifecycle | `Web implements AutoCloseable`, JVM shutdown hook auto-registered on `start()`, registration locked after `start()` via shared `AtomicBoolean` |

### 📋 Must-have (MVP blockers)

| Feature | Description |
|---------|-------------|
| Static file serving | `web.files("/assets", Paths.get("static"))` with Content-Type inference, ETag/Last-Modified, path traversal protection. Needed to serve the SPA's CSS/JS/images. |
| Multipart body supplier | `MultipartBodySupplier` adapting `HTTPRequest.getFormData()` / `getFiles()` into the `BodySupplier<T>` pattern for artifact uploads. |
| CSRF middleware | Double-submit cookie pattern. Issues a JS-readable `csrf_token` cookie, validates `X-CSRF-Token` header against it on POST/PUT/PATCH/DELETE. Returns 403 on mismatch. Configurable path exemptions (e.g., login callback). |
| JWT validation middleware | Verifies JWT signature against FusionAuth's JWKS, extracts claims, attaches user identity to request as attribute. Likely lives in a separate `latte-auth` module. |
| OIDC / FusionAuth flow helpers | Login-redirect handler, OAuth2 callback endpoint helper (code exchange, set JWT + refresh cookies, redirect). Likely in the `latte-auth` module. |
| Refresh-token flow helper | Middleware or helper that uses the refresh cookie to obtain a new JWT when the current one is near-expiry. Also likely in `latte-auth`. |

### 📋 Strongly recommended (non-blocking)

| Feature | Description |
|---------|-------------|
| Per-route body size limits | `org.lattejava.http` has a global `maxRequestBodySize`. Artifact uploads need a higher limit; ordinary JSON endpoints should stay smaller. Expose a per-route override as a middleware. |
| Request logging middleware | Canonical access-log middleware: method, path, status, duration. Cheap to build on existing middleware; avoids every app rewriting it. |

### 🔮 Deferred (post-MVP)

| Feature | Notes |
|---------|-------|
| CORS | Only needed if the SPA runs on a different origin than the API. For same-origin deployment, skip. |
| Rate limiting | Add when the upload endpoint justifies it; not a launch blocker. |
| Security headers middleware | CSP, X-Frame-Options, HSTS, etc. Trivial middleware to write when hardening. |
| HTML templating | Not needed if the frontend is a SPA. Would matter for server-rendered pages. |

---

## Webapp-level features (built on the framework)

### 📋 Authentication / Authorization

| Feature | Description |
|---------|-------------|
| Login redirect endpoint | Redirects unauthenticated users to FusionAuth with `client_id`, `redirect_uri`, `state`, `code_challenge` (PKCE). |
| OAuth2 callback endpoint | Receives the authorization code, exchanges it for JWT + refresh tokens via FusionAuth's token endpoint, sets the cookies, redirects to the SPA. |
| Logout endpoint | Clears JWT + refresh + CSRF cookies; optionally redirects to FusionAuth's logout endpoint. |
| Authorization middleware | After JWT validation, checks the authenticated user's roles/groups against the target resource (e.g., "user can publish to this group"). |

### 📋 Artifact operations

| Feature | Description |
|---------|-------------|
| Upload endpoint | Maven-style layout: `PUT /{group}/{artifact}/{version}/{file}`. Accepts JAR/POM/sources/javadoc + checksum files. |
| Checksum validation | Validate uploaded SHA-1, SHA-256, MD5 against the artifact bytes. |
| Immutability policy | Reject re-uploads of existing versions (except SNAPSHOT per configured policy). |
| Owner check | The uploading user must own the target group. |
| Browse / search | `GET /{group}/...` lists artifacts, versions, files. Provides Maven-compatible directory listings. |
| Download | `GET /{group}/{artifact}/{version}/{file}` streams the artifact. |
| Metadata endpoints | `maven-metadata.xml` generation per artifact. |

### 📋 UI (SPA)

| Feature | Description |
|---------|-------------|
| Dashboard | Shows the user's groups, recent uploads, quick actions. |
| Browse | Searchable artifact tree. |
| Upload page | Primarily for docs/manual uploads; most uploads will come from build tools via the API. |
| Group management | Create groups, manage members (invite, remove, role change). |
| Account settings | Link to FusionAuth for profile edits. |

---

## Next framework tasks (suggested order)

1. **Multipart body supplier** — unblocks artifact upload endpoint design.
2. **Static file serving** — unblocks the SPA host.
3. **CSRF middleware** — needed before any authenticated mutation endpoint.
4. **`latte-auth` module** (separate repo/module) — JWT validation, OIDC client, refresh flow.
5. **Per-route body size limits** — needed for the upload endpoint to accept large artifacts without raising the global limit.
6. **Request logging middleware** — quality-of-life; can land any time.

## Notes on CSRF for the SPA

The SPA can't put the JWT in an `Authorization: Bearer` header because the cookie holding it is `HttpOnly` (defense against XSS token exfiltration). That means every authenticated request authenticates via cookie, which is the CSRF-vulnerable pattern.

`SameSite=Strict` alone is not sufficient:
- Same-site subdomain attacks bypass SameSite (site = eTLD+1)
- Historical browser quirks (Chrome's old Lax+POST behavior, older browsers ignoring SameSite)
- Defense-in-depth is the correct security posture

**Double-submit cookie pattern** is the recommended approach:

1. Server sets an HttpOnly JWT cookie AND a non-HttpOnly `csrf_token` cookie (random, per-session).
2. SPA reads `csrf_token` from `document.cookie` and sends it on unsafe requests as an `X-CSRF-Token` header.
3. CSRF middleware compares the header against the cookie on POST/PUT/PATCH/DELETE; mismatch → 403.
4. Safe methods (GET/HEAD/OPTIONS) skip the check.

Why this defeats the attacker: they can't read the cookie (same-origin policy on `document.cookie`), and they can't set a custom request header cross-origin without a CORS preflight that the server denies. So they can't forge a request with a matching header/cookie pair.
