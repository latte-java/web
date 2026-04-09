Basic example:

```java
import module org.lattejava.mvc;

MVC mvc = new MVC();
Router router = new Router(); // Middleware
Security security = new AuthorizationSecurity("api-key"); // Middleware
Bodies bodies = new Bodies();

void main() {
  mvc.install(security); // Global protection
  mvc.install(router);
  
  // Method reference
  router.get("/", this::getSlash);
  
  // Inline lambda
  router.get("/foo", (req, res) -> {
    res.setStatus(200);
    res.getWriter().write("Hello Foo");
  });
  
  // Handling JSON
  router.post("/api/user/{id}", this::createUser, bodies.fromJSON(User.class));
  
  // Middleware injection
  router.post("/api/foo", security, this::foo);
  
  // Grouping
  router.group("/api", security, r -> {
    r.get("/user", this::getUser);
    r.get("/foo", this::getFoo);
  });

  mvc.start(8001);
  mvc.daemon(); // Runs until the JVM is sent a signal such as QUIT or KILL
}

void getSlash(HTTPRequest req, HTTPResponse res) {
  res.setStatus(200);
  res.getWriter().write("Hello World");
}

void createUser(HTTPRequest req, HTTPResponse res, User user) {
  if (!valid(user)) {
    res.setStatus(400);
    return;
  }
  
  res.setStatus(200);
  res.getWriter().write(bodies.toJSON(user));
}
```

## Middleware Interface

Inspired by Express's `(req, res, next)` pattern and Go's `func(http.Handler) http.Handler` decorator pattern. Both share the same core idea: middleware either calls the next handler or short-circuits.

```java
/**
 * A middleware intercepts requests in the pipeline. It can:
 * - Inspect/modify the request or response
 * - Short-circuit by NOT calling chain.next() (e.g., return a 401)
 * - Pass control downstream by calling chain.next()
 */
@FunctionalInterface
public interface Middleware {
  void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain);
}

/**
 * The Middleware chain that handles how each Middleware in the chain is called.
 * If not called, the chain stops (short-circuit pattern from both Express and Go).
 */
@FunctionalInterface
public interface MiddlewareChain {
  void next(HTTPRequest req, HTTPResponse res);
}
```

## Router

Possible methods:

```java
public class Router {
  Router route(String pathSpec, Handler handler) {
  }

  Router route(String pathSpec, BodyHandler<T> handler, BodySupplier<T> handler) {
  }

  Router route(String pathSpec, Middleware middleware, Handler handler) {
  }

  Router route(String pathSpec, Middleware middleware, BodyHandler<T> handler, BodySupplier<T> handler) {
  }

  Router group(String pathSpec, Middleware middleware, Consumer<Router> group) {
  }
}

@FunctionalInterface
public interface Handler {
  void handle(HTTPRequest req, HTTPResponse res);
}

@FunctionalInterface
public interface BodyHandler<T> {
  void handle(HTTPRequest req, HTTPResponse res, T object);
}

@FunctionalInterface
public interface BodySupplier<T> extends BiFunction<HTTPRequest, HTTPResponse, T> {
  T get(HTTPRequest req, HTTPResponse res);
}
```