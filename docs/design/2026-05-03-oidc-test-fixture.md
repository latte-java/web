# Open ID Connect Test Fixture

Design a class that helps with Open ID Connect login and logout for tests that need a user with an active session (or not).

# Problem statement

Many tests need a user with an active session. It's possible to Mock the necessary cookies, JWTs, token endpoints, refresh tokens, refresh token endpoints, etc., but that is complex and brittle. It also doesn't ensure that the application will work properly in production. Instead, it is much better to use a real Open ID Connect provider and have the tests log a test user in whenever needed.

# Strategy

Since this will connect to FusionAuth by default, we will assume that the Open ID Connect provider is already configured and running, since FusionAuth can be configured using Kickstart easily. This also assumes that the FusionAuth APIs can be called if needed.

The base use-case is logging a user into FusionAuth using Open ID Connect and then updating the Cookie JAR with the tokens in a way that matches the OIDCConfig the application will use.

Here's how a test would leverage this fixture:

```java
var app = new Application(); // This is the main entry point for the application and it constructs the Web instance, OIDConfig, and OIDC objects
app.main(); // Starts the app

WebTest test = new WebTest(8080); // 8080 is the port the app is running on
OIDCConfig oidcConfig = app.oidcConfig; // This is the OIDCConfig that the application constructs (in a public field or accessible via a getter)
OIDCTestFixture fixture = new OIDCTestFixture(test, oidcConfig); // This is the fixture we need to create

fixture.login("admin@example.com","password","<applicationId>"); // Logs the user in and stores the OIDC tokens in the Cookie JAR

// Test the handler/route that requires a user with an active session

fixture.logout(); // Removes the cookies from the Cookie JAR (if the test needs this behavior)
```

The `FusionAuthFixture` that already exists in the `src/test/java` directory can be used as a starting point. The constants in that class likely aren't needed since the OIDCConfig contains all the necessary information to connect to the Open ID Connect provider.

We don't need to create the Application class, since the developer that is building the webapp that uses this web framework will write that. They'll have access to the OIDCConfig object as illustrated in the example above.

The `OIDCTestFixture` class should be in the `src/main/java/org/lattejava/web/test` directory.