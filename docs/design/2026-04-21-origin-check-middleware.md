# OriginCheckMiddleware

Design doc for the Origin-header CSRF defense middleware. Branch: `features/mvp-security`.

## Purpose

Defense-in-depth CSRF layer for browser mutation requests. The primary defense is `SameSite=Strict` on the session cookie; this middleware rejects unsafe-method requests whose `Origin` header doesn't match an expected value.

## Threat model

- `SameSite=Strict` on the JWT cookie blocks the cookie from being attached to any cross-site request. Classic CSRF (evil.com forges a POST to repo.lattejava.org) arrives unauthenticated → 401.
- This middleware closes remaining gaps: sibling-subdomain XSS (same site, cookie attached), accidental `SameSite` misconfiguration, proxy/CDN rewriting the attribute.
- Not addressed here: token theft (XSS that exfiltrates cookies, network MITM). Different problem.

## API

Four constructors. Both parameters optional; defaults are `requireOrigin=false` and `allowedOrigins=null`.

```java
new OriginCheckMiddleware()                         // requireOrigin=false, auto-derive
new OriginCheckMiddleware(true)                     // requireOrigin=true, auto-derive
new OriginCheckMiddleware(List.of(URI.create(...))) // requireOrigin=false, explicit
new OriginCheckMiddleware(true, List.of(...))       // both specified
```

### Parameters

- **`requireOrigin`**: when `true`, a missing `Origin` header on an unsafe-method request returns 403. When `false`, missing `Origin` passes through (non-browser clients like curl/build tools don't auto-attach cookies, so there's no CSRF vector to defend against).
- **`allowedOrigins`**: when non-null, the Origin must match one of the listed URIs (compared by scheme + host + port). When null, the middleware compares Origin against the request's own `getBaseURL()` for each request — safe because `X-Forwarded-Host` is not attacker-controllable via browser CSRF vectors.

### Validation at construction

- Empty list → `IllegalArgumentException`.
- Each URI must have a scheme and host, no userinfo, no query, no fragment, path must be empty or `/`. Violations → `IllegalArgumentException`.

## Behavior

1. Safe methods (`GET`, `HEAD`, `OPTIONS`) skip the check and pass through.
2. Missing `Origin` header:
   - `requireOrigin=true` → 403
   - `requireOrigin=false` → pass through
3. `Origin` present:
   - Parse and normalize (lowercase scheme/host, collapse default ports, strip path/query/fragment). Unparseable → 403.
   - Compare against allowed set (explicit list if configured, else normalized `getBaseURL()`).
   - No match → 403.
   - Match → pass through.
4. `Origin: null` literal falls naturally into the "unparseable or unmatched" path → 403.

## Normalization rules

- Scheme: lowercased.
- Host: lowercased.
- Port: `-1` (default) for `:80` on http and `:443` on https; otherwise preserved.
- Path, query, fragment, userinfo: dropped before comparison.

## Non-goals

- No path exemption mechanism (not needed for MVP; per-route install via Latte's existing middleware wiring).
- No `Referer` fallback. All modern browsers send `Origin` on unsafe methods; `Referer` is too easily stripped to be useful.
- No CORS handling (separate concern).

## Test plan

Auto-derive mode:

1. POST with Origin matching `getBaseURL()` → passes
2. POST with foreign Origin → 403
3. Behind proxy (`X-Forwarded-Host`) with matching Origin → passes

Explicit-list mode:

4. POST with Origin matching first allowed → passes
5. POST with Origin matching second allowed → passes
6. POST with Origin not in list → 403

Shared:

7. POST with no Origin, `requireOrigin=true` → 403
8. POST with no Origin, `requireOrigin=false` → passes
9. POST with `Origin: null` literal → 403
10. POST with malformed Origin → 403
11. GET, HEAD, OPTIONS skip the check entirely (even with bad Origin)
12. PUT, PATCH, DELETE all enforced
13. Normalization: trailing slash, scheme/host case, default port collapse — all resolve to match
14. Constructor rejects empty list
15. Constructor rejects URI with path beyond `/`, query, fragment, or userinfo
