# Prefix-scoped middleware for Web.install()

Design doc for scoping `Web.install()` to the enclosing `prefix()` block. Branch: `features/mvp-security`.

## Problem

`Web.prefix()` creates a child `Web` that shares the parent's `globalMiddlewares` list. Calling `install()` on the child appends to that shared list, so the middleware applies to every route in the application — not just routes inside the prefix. That forces developers to wire prefix-specific middleware via per-route `Middleware...` varargs on every route, which is verbose and error-prone.

## Goal

`install()` called inside a `prefix(path, lambda)` block should apply only to routes whose path is under `path`. Top-level `install()` continues to apply globally.

## Design

### Storage

Replace the `List<Middleware> globalMiddlewares` field with:

```java
private final Map<String, List<Middleware>> middlewaresByPrefix; // LinkedHashMap
```

Key is the absolute path prefix the middleware applies to (e.g. `""` for root, `/api`, `/api/v1`). Value is the FIFO list of middlewares installed there. The Map is shared across parent and child `Web` instances created via `prefix()`.

### install()

Appends to `middlewaresByPrefix.computeIfAbsent(pathPrefix, _ -> new ArrayList<>())`. The `pathPrefix` field on the current `Web` determines the key — root installs land under `""`, installs inside a `prefix("/api", ...)` lambda land under `/api`.

`install()` still rejects null middlewares and throws `IllegalStateException` if called after `start()`.

### Request handling

For a matched path, build the chain:

1. Walk `middlewaresByPrefix` entries.
2. Include an entry when its key is a path-segment prefix of the request path:
   - Key `""` always matches.
   - Otherwise: request path equals the key, or starts with `key + "/"`.
3. Sort included entries by key length ascending (outer wraps inner).
4. Concatenate their lists, then append per-route middlewares.

For `NotFound`: same collection logic, trailing handler sets 404. This preserves the "middleware under prefix sees 404s under that prefix" property (lets a static-resource-style middleware installed inside `prefix("/assets", ...)` still handle 404-ish requests under that prefix).

For `MethodNotAllowed`: no middlewares run (matches current behavior).

### Ordering guarantees

- Outer-to-inner: middlewares at shallower prefixes run before deeper ones.
- FIFO within a single prefix: multiple `install()` calls on the same `Web` instance preserve registration order.
- Order-independent across `install()` vs route registration: consulted at request time.

## Test plan

1. `install()` at root applies to all routes (existing behavior preserved).
2. `install()` inside `prefix("/api", ...)` applies to a route at `/api/users`.
3. `install()` inside `prefix("/api", ...)` does NOT apply to a route at `/other`.
4. Nested prefixes: outer middleware runs before inner middleware on a route inside both.
5. Segment boundary: `/apix` does NOT match prefix `/api`.
6. Per-route middleware runs AFTER any prefix middlewares for the same route.
7. 404 under `/api` runs `/api`-prefix middleware.
8. 404 outside any prefix runs only root (`""`) middlewares.
9. 405 does NOT run any middlewares (regression).
10. `install()` called after a route registration inside the same prefix still applies to that route (order-independent).
11. Multiple `install()` calls on the same prefix preserve FIFO order.
12. `install()` after `start()` throws `IllegalStateException`.

## Non-goals

- No public API for querying the middleware chain.
- No per-method scoping at a prefix.
- No exemption mechanism at a prefix.
