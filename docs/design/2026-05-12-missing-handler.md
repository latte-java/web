# Missing handler

## Purpose

Let applications register a custom `Handler` that runs in place of the framework's default 404 behavior when a request path does not match any registered route. Both 404 paths in `Web.handleRequest` (the no-prefix-middleware case and the prefix-middleware case) defer to this handler when it is set; the prefix middleware chain still executes around it.

## Placement and naming

- Class: `org.lattejava.web.Web`
- New field: `private Handler missingHandler` (mutable instance field)
- New setter: `public Web missingHandler(Handler handler)`

Field is alphabetically placed in the mutable-instance-fields block between `loggerFactory` and `server`. Setter is alphabetically placed in the public-instance-methods block between `loggerFactory` and `options`.

## API

```java
web.missingHandler((req, res) -> {
  res.setStatus(404);
  res.setHeader("Content-Type", "text/html; charset=utf-8");
  res.write("<h1>Not Found</h1>");
});
```

The handler has the same signature as a route handler. It is responsible for setting whatever status, headers, and body it wants — the framework's default `res.setStatus(404)` is replaced wholesale, not layered on top.

If `missingHandler` is not set, the existing default 404 behavior (status 404, no body) is preserved.

## Setter contract

```java
public Web missingHandler(Handler handler) {
  if (isChild) {
    throw new IllegalStateException("Cannot call missingHandler on a prefix child Web instance");
  }
  if (started.get()) {
    throw new IllegalStateException("Cannot set missingHandler after Web has been started");
  }
  Objects.requireNonNull(handler, "handler must not be null");
  this.missingHandler = handler;
  return this;
}
```

- **Root only.** Calling on a `prefix(...)` child throws `IllegalStateException`, matching how `start()` and `close()` already treat child Webs. The handler is request-level and global; per-prefix scoping is out of scope.
- **Locked after `start()`.** Throws `IllegalStateException` once the server is running, matching all other registration methods (`install`, `route`, `baseDir`, `logLevel`, `loggerFactory`).
- **Null rejected.** `Objects.requireNonNull` on the handler argument. Clearing back to the default is intentionally not supported — same pattern as the existing `install`/`route` setters that reject null.
- **Fluent.** Returns `this` for chaining.

## Request dispatch

The `NotFound` case in `Web.handleRequest` becomes:

```java
case RouteTrie.Outcome.NotFound(var segments) -> {
  Handler notFound = missingHandler != null ? missingHandler : (_, res) -> res.setStatus(404);
  List<Middleware> prefixMiddlewares = middlewareTrie.collect(segments);
  if (prefixMiddlewares.isEmpty()) {
    notFound.handle(request, response);
  } else {
    new MiddlewareChainImpl(prefixMiddlewares, notFound).next(request, response);
  }
}
```

The `notFound` handler is the user's `missingHandler` if set, otherwise the inline default. Both branches invoke it:

- **No prefix middlewares**: invoked directly on the request and response.
- **Prefix middlewares present**: invoked as the terminal handler of a `MiddlewareChainImpl` that runs the collected prefix middlewares first.

The `MethodNotAllowed` (405) branch is unchanged — the missing-handler feature is scoped to path-not-matched cases only.

## Behavior notes

- **Exception propagation.** If the handler throws, the exception propagates out of `handleRequest` exactly as any route handler exception does. No special wrapping or fallback to the default 404.
- **Path attribute.** The handler receives the full `HTTPRequest` and can read `req.getPath()` to obtain the unmatched path. No new attributes are populated by the framework.
- **Body writing.** The handler may write a response body the same way a route handler does. No restrictions.
- **Default behavior preserved.** Existing tests that assert plain 404 responses (no body, status 404) continue to pass when `missingHandler` is not set.

## Tests

New file: `src/test/java/org/lattejava/web/tests/MissingHandlerTest.java`.

- `missingHandler_invokedWhenNoRouteMatches` — register a handler that sets status 410 with a custom body, hit `/nope`, assert 410 + body.
- `missingHandler_runsAfterPrefixMiddlewares` — install a prefix middleware that adds a response header, set `missingHandler`, hit an unmatched path under that prefix, assert both the middleware header AND the handler's status appear.
- `missingHandler_defaultBehaviorPreservedWhenNotSet` — no handler set, hit unmatched path, assert plain status 404 (regression).
- `missingHandler_doesNotAffect405` — register `GET /x`, hit `POST /x`, assert 405 and `Allow: GET`; the handler (if installed in this test) must not be invoked.
- `missingHandler_rejectsNull` — `web.missingHandler(null)` throws `NullPointerException`.
- `missingHandler_rejectsCallAfterStart` — start the server, call `missingHandler`, expect `IllegalStateException`.
- `missingHandler_rejectsCallOnChildWeb` — inside a `prefix(...)` callback, call `child.missingHandler(...)`, expect `IllegalStateException`.

## Non-goals

- No 405 customization. The user explicitly scoped this to 404; 405 is out of scope.
- No per-prefix scoping. A single global handler matches the user's `web.missingHandler()` registration shape.
- No "clear back to default" via `null`. Same pattern as the existing null-rejecting setters; consumers can replace with another non-null handler if needed.
- No automatic status enforcement. If the handler forgets to call `setStatus`, the response uses whatever the underlying HTTPResponse defaults to. The handler owns the response.
