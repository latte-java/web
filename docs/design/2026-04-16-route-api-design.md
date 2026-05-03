# Route API Design

## Overview

Define the public routing API for the `Web` class. This replaces the initial `route(String, Handler)` method with a method-aware routing system that includes per-verb convenience methods and prefix-based route grouping.

## API Surface

### Core Route Method

```java
Web route(Collection<String> methods, String pathSpec, Handler handler)
```

- `methods` — HTTP methods this route responds to (e.g., `List.of("GET", "POST")`). Stored upper-cased for case-insensitive matching.
- `pathSpec` — path pattern with optional `{name}` parameters (e.g., `/api/user/{id}`).
- `handler` — the `Handler` to invoke when matched.
- Returns `this` for chaining.

### Per-Verb Convenience Methods

Each delegates to `route()` with a single-element list:

```java
Web get(String pathSpec, Handler handler)
Web post(String pathSpec, Handler handler)
Web put(String pathSpec, Handler handler)
Web delete(String pathSpec, Handler handler)
Web patch(String pathSpec, Handler handler)
Web head(String pathSpec, Handler handler)
Web options(String pathSpec, Handler handler)
```

### Prefix Grouping

```java
Web prefix(String pathPrefix, Consumer<Web> group)
```

- Creates a temporary `Web` instance with the prefix applied.
- The `Consumer` receives this temporary instance and registers routes on it.
- Routes registered inside the callback have `pathPrefix` prepended to their `pathSpec`.
- The temporary instance's routes are added to the parent `Web`'s route list.
- Prefixes nest: calling `prefix()` inside a `prefix()` callback compounds the prefixes.
- Returns `this` for chaining.

### Server Lifecycle

```java
Web start(int port)   // creates HTTPServer, binds handler, starts listening
void daemon()         // blocks calling thread until JVM shutdown
void close()          // shuts down the server
```

### Handler Interface

Unchanged:

```java
@FunctionalInterface
public interface Handler {
  void handle(HTTPRequest req, HTTPResponse res) throws Exception;
}
```

## Route Matching

- Routes are matched in registration order (first match wins).
- A route matches when both the path pattern matches AND the request's HTTP method is in the route's method set.
- Path parameters use `{name}` syntax. Matched values are set as request attributes via `HTTPRequest.setAttribute(name, value)`.
- Path patterns are compiled to regex at registration time: `{name}` becomes `([^/]+)`, literal segments are quoted.

## Error Responses

- **404** — no route's path pattern matches the request path.
- **405** — at least one route's path pattern matches but none accept the request's HTTP method. The response includes an `Allow` header listing the union of accepted methods across all path-matching routes.

## Implementation Notes

### Route Class

The inner `Route` class stores:
- `Set<String> methods` — upper-cased HTTP method names
- `String pathSpec` — the original path spec
- `Pattern pattern` — compiled regex
- `List<String> paramNames` — extracted parameter names
- `Handler handler`

### Prefix Implementation

`prefix()` creates a child `Web` instance. The child holds a reference to the parent's route list. When routes are registered on the child, they are prepended with the prefix and added directly to the parent's route list. This keeps route ordering predictable — routes inside a prefix appear in the parent's list at the point where the prefix callback executes.

## Usage Examples

```java
Web web = new Web();

// Simple routes
web.get("/", (req, res) -> {
  res.setStatus(200);
  res.getWriter().write("Hello World");
});

// Path parameters
web.get("/users/{id}", (req, res) -> {
  String id = (String) req.getAttribute("id");
  res.setStatus(200);
  res.getWriter().write("User: " + id);
});

// Multi-method route
web.route(List.of("GET", "POST"), "/form", (req, res) -> {
  res.setStatus(200);
});

// Prefix grouping
web.prefix("/api", r -> {
  r.get("/users", this::listUsers);        // /api/users
  r.post("/users", this::createUser);      // /api/users
  r.prefix("/admin", r2 -> {
    r2.get("/stats", this::getStats);      // /api/admin/stats
  });
});

// Chaining after prefix
web.prefix("/api", r -> {
  r.get("/users", handler);
}).get("/health", handler);                // /health

web.start(8001);
web.daemon();
```
