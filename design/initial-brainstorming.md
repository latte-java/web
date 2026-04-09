Basic example:

```java
import module org.lattejava.mvc;

MVC mvc = new MVC();
Router router = new Router();
Security security = new AuthorizationSecurity("api-key");
Bodies bodies = new Bodies();

void main() {
  mvc.install(security.protect("/api/**/*"));
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

