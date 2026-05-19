# SecurityHeaders middleware

Branch: `features/mvp-security`.

## Purpose

A middleware that emits a strict set of HTTP security headers on every response. Defaults match the most-secure reasonable values for each header; every header is individually overridable (or suppressible) via an inner `Builder`.

## Placement and naming

- Package: `org.lattejava.web.middleware`
- Class: `SecurityHeaders`
- Matches the sibling middleware naming (`OriginChecks`, `StaticResources`, `ExceptionHandler`).

## Headers and defaults

| Header                          | Default                                                                                                          |
|---------------------------------|------------------------------------------------------------------------------------------------------------------|
| `Strict-Transport-Security`     | `max-age=31536000; includeSubDomains; preload`                                                                  |
| `Content-Security-Policy`       | `default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'; upgrade-insecure-requests` |
| `X-Content-Type-Options`        | `nosniff`                                                                                                        |
| `X-Frame-Options`               | `DENY`                                                                                                           |
| `X-XSS-Protection`              | `0`                                                                                                              |
| `Referrer-Policy`               | `no-referrer`                                                                                                    |
| `Permissions-Policy`            | `accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()`  |
| `Cross-Origin-Opener-Policy`    | `same-origin`                                                                                                    |
| `Cross-Origin-Embedder-Policy`  | `require-corp`                                                                                                   |
| `Cross-Origin-Resource-Policy`  | `same-origin`                                                                                                    |

## Runtime behavior

- Sets each non-null header via `HTTPResponse.setHeader(name, value)` **before** calling `chain.next(req, res)` so error responses (404/405/5xx) also carry them.
- A downstream handler can override any header by calling `setHeader` again.
- Runs for every request; no method distinctions.

## API

```java
// All secure defaults
web.install(SecurityHeaders.defaults());

// Override a header
web.install(SecurityHeaders.defaults()
    .contentSecurityPolicy("default-src 'self'; script-src 'self' 'nonce-abc123'"));

// Suppress a header (pass null)
web.install(SecurityHeaders.defaults()
    .strictTransportSecurity(null));

// Only one header for a prefix/handler
web.install(SecurityHeaders.empty()
    .xFrameOptions("SAMEORIGIN"));
```

`null` suppresses; any String (including empty) emits.

## Class shape

One `final String` field per header. `SecurityHeaders` has:

- Private all-args constructor.
- Static `defaults()` factory (all headers at secure defaults) and `empty()` factory (no headers).
- One copy-on-write method per header returning a new immutable instance; `null` clears that header.

Superseded by `docs/design/2026-05-18-security-headers-immutable.md`.

## Tests

- `defaults_emitsAllHeadersWithExpectedValues` — every header present with its documented default on a 200 response.
- `builder_overridesHeaderValue` — custom value wins.
- `builder_nullSuppressesHeader` — header absent from response.
- `headersPresentOn404` — 404 responses carry headers (prefix middleware runs on NotFound).
- `handlerCanOverrideHeader` — handler's `setHeader` overrides middleware default.

## Caveats

- `405 Method Not Allowed` responses skip the middleware chain (architectural decision from the prefix-scoped middleware work). Those responses carry only `Allow` and no body, so the missing security headers are not a meaningful gap.

## Non-goals

- No CSP nonce generation (app-level concern; pass via builder).
- No scheme-aware toggling (e.g., skipping HSTS on plain HTTP).
- No per-header conditional emission based on content type.
