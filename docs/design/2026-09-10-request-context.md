# Request context

## Motivation

`Web.inject(...)` lets a DI framework build handlers, controllers, and middlewares, but the request and response still travel as method parameters. Anything deeper in the object graph that needs them (`Messages`, `Flash`, a service that reads a path parameter) has to be handed the request by every caller.

This design binds the current `HTTPRequest` and `HTTPResponse` for the life of each request so a DI framework can provide them like any other dependency.

## Public API

### `RequestContext` (in `org.lattejava.web`)

```java
public final class RequestContext {
  public static final ScopedValue<HTTPRequest> REQUEST;
  public static final ScopedValue<HTTPResponse> RESPONSE;

  public static HTTPRequest request();    // IllegalStateException outside a request
  public static HTTPResponse response();  // IllegalStateException outside a request
}
```

`Web.handleRequest` binds both values around dispatch, so middlewares, handlers, the missing handler, and the default `HTTPException` renderer all run inside the binding.

### DI wiring (Avaje)

```java
@Factory
public class WebFactory {
  @Bean @Prototype
  HTTPRequest request() { return RequestContext.request(); }

  @Bean @Prototype
  HTTPResponse response() { return RequestContext.response(); }
}

@Prototype
public class UserController {
  public UserController(HTTPRequest req, UserService users) { ... }
}
```

The provider must be prototype-scoped, or consumed through a `Provider<HTTPRequest>`, so each request resolves the current binding. A singleton would capture the first request.

### Tests

Code that resolves such beans outside a server binds the values itself:

```java
ScopedValue.where(RequestContext.REQUEST, req)
           .where(RequestContext.RESPONSE, res)
           .run(() -> scope.get(UserController.class).show(req, res));
```

## Why `ScopedValue`

The HTTP server runs each request synchronously on its own virtual thread (one per HTTP/1 connection, one per HTTP/2 stream), so a thread-bound value covers the whole request.

| Option                                  | Verdict  | Reason                                                                                                                                                                              |
|-----------------------------------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ScopedValue` (chosen)                  | Best fit | Final in Java 25. Bound for a lexical scope and unbound automatically, so no leak across requests. Immutable and cheap on virtual threads.                                          |
| `ThreadLocal`                           | Rejected | Needs explicit `remove()` in a `finally`. Mutable from anywhere. `InheritableThreadLocal` copies into every child thread, which is rarely what a request wants.                     |
| Widen `Injector` to take `req`/`res`    | Rejected | Pushes request scoping onto every DI adapter. Each framework does it differently, and a plain `BeanScope::get` method reference stops working.                                      |
| Per-request child `BeanScope`           | Rejected | Avaje-specific and allocates a scope per request. The framework stays DI-agnostic.                                                                                                  |

## Limits

- Threads started with `Thread.start()` or a plain executor do not see the binding. Pass the request explicitly to work that runs on another thread.
- Rebinding is allowed. A middleware that wraps the request can rebind `REQUEST` for the rest of the chain with `ScopedValue.where(...)`.
