# SecurityHeaders: immutable, CSP-style API

**Date:** 2026-05-18
**Status:** Approved, ready for implementation plan
**Supersedes the API shape in:** `docs/design/2026-04-21-security-headers.md` (behavior unchanged)

## Problem

`SecurityHeaders` today is constructed through a `Builder` whose ten header
fields are all pre-populated with secure defaults. There is no convenient way
to install a `SecurityHeaders` that emits only a single header (or a small
subset) for a specific route prefix or handler — you always start from the full
default set and must null out the nine you don't want.

`CSP` already solves the analogous problem with `CSP.empty()` /
`CSP.defaults()` plus chainable mutators on the instance and no separate
builder. `SecurityHeaders` should follow the same shape.

A `CSP` is consumed at configuration time (`build()` produces a `String` before
the server runs), so its mutability is never observed on the request path.
`SecurityHeaders` is different: the instance *is* the middleware, and
`handle()` reads its fields on every request from many threads concurrently.
The new API must be thread-safe by construction, not by convention.

## Goals

- `SecurityHeaders.empty()` and `SecurityHeaders.defaults()` static factories.
- Chainable per-header setters on the instance (CSP-style, bare names).
- Trivial "only one header for this prefix/handler" usage.
- Deep immutability so the served middleware is thread-safe unconditionally.
- No public/no-arg constructor, no `Builder`, no `builder()`.

## Non-goals

- No change to `handle()` behavior (header-emission semantics, the
  localhost / `127.0.0.1` `upgrade-insecure-requests` strip).
- No change to the default header values.
- No optimization of the per-request UIR-strip computation.
- No changes to the in-flight `CSP.java` working-tree edits (the `Map` field
  type, the added `imgSrc(SELF)` default) — independent and left alone.

## Design

### Class shape

Ten `private final String` fields, alphabetized per the code-conventions rule:

```
contentSecurityPolicy
crossOriginEmbedderPolicy
crossOriginOpenerPolicy
crossOriginResourcePolicy
permissionsPolicy
referrerPolicy
strictTransportSecurity
xContentTypeOptions
xFrameOptions
xXSSProtection
```

One `private SecurityHeaders(...)` all-args constructor, parameters in the same
alphabetical order. It is the only constructor. The `Builder` nested class,
`builder()`, and the public no-arg constructor are removed.

### Factories

- `public static SecurityHeaders empty()` — all ten fields `null`. `handle()`
  emits nothing until headers are added.
- `public static SecurityHeaders defaults()` — all ten fields at today's secure
  default values, with `contentSecurityPolicy` = `CSP.defaults().build()`.

Each call returns a fresh instance.

### Copy-on-write setters

One setter per header, bare-named to mirror `CSP` (e.g. `xFrameOptions`,
`contentSecurityPolicy`), each returning a new `SecurityHeaders`:

- Constructs a new instance passing `this.<field>` for the nine unchanged
  fields and the new value for the one being set.
- `null` clears that header (matches today's "null suppresses" behavior);
  `empty()` is simply the all-`null` instance.
- `contentSecurityPolicy` keeps both overloads:
  - `contentSecurityPolicy(CSP csp)` → `csp == null ? null : csp.build()`
  - `contentSecurityPolicy(String value)`

A single mechanical constructor plus full test coverage guards against
argument-order mistakes in the setters.

### Thread safety

Every instance is deeply immutable (all fields `final`, `String` values
immutable). The Java Memory Model's final-field semantics guarantee safe
publication unconditionally — correct regardless of when or how the instance is
installed, and impossible to mutate after install. `handle()` reads only final
fields and allocates nothing on the request path. The fluent configuration
chain allocates a small number of short-lived objects, but only at startup
configuration time.

### handle()

Unchanged. Same write-each-header-only-if-absent logic (so upstream or
downstream code can still override any header), and the same
localhost / `127.0.0.1` `upgrade-insecure-requests` strip applied to the CSP
value, computed per request into a local variable.

### Usage

```java
web.install(SecurityHeaders.defaults());

web.prefix("/embed", e ->
    e.install(SecurityHeaders.empty().xFrameOptions("SAMEORIGIN")));

web.install(SecurityHeaders.defaults()
                           .strictTransportSecurity(null)        // suppress
                           .contentSecurityPolicy(CSP.empty()
                                                     .defaultSrc(CSP.NONE)));
```

## Testing

Update `src/test/java/.../middleware/SecurityHeadersTest.java`:

- `SecurityHeaders.builder()...build()` → `SecurityHeaders.defaults()...`.
- `new SecurityHeaders()` → `SecurityHeaders.defaults()`.
- Rename `builder_*` test methods to reflect the new API.
- Add: `empty()` emits only the explicitly-set header and nothing else.
- Add: a setter does not mutate its receiver (copy-on-write / immutability) —
  e.g. `var a = SecurityHeaders.defaults(); var b = a.xFrameOptions("X");`
  then assert `a` still serves the original value and `a != b`.
- Retain existing coverage: all-defaults emission, null-suppression,
  string/CSP overrides, handler override wins, headers present on 404,
  upstream-middleware header is kept, localhost/loopback UIR strip.

## Documentation

- `SecurityHeaders` class Javadoc: remove `Builder` references; document
  `empty()` / `defaults()` and the immutability / thread-safety contract.
- `docs/design/2026-04-21-security-headers.md`: update the API code examples
  (`new SecurityHeaders()`, `builder()`) to the new factories.
