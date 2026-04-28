# Web logging

Design doc for making the logger used by `Web` swappable, providing a Web-flavored default with ISO-8601 timestamps, and emitting a startup message that includes a clickable URL.

## Purpose

Today `Web.start(int)` hardcodes `new SystemOutLoggerFactory()` from `org.lattejava.http`, which prefixes every log line with `System.currentTimeMillis()`. Two problems:

1. Developers can't substitute their own logger (SLF4J bridge, file logger, test capture, etc.) without forking `Web`.
2. The epoch-millis prefix is unfriendly. Operators want a human-readable, system-zone ISO-8601 timestamp so log lines are interpretable without a converter.

This change makes the `LoggerFactory` swappable, ships a Web-specific default that writes ISO-offset timestamps to `System.out`, and adds a single info-level startup line so a developer running `latte` in a terminal sees a clickable URL.

## Public API additions to `Web`

Two pre-start setters and one `start` overload. All locked after `start()` (matching `baseDir(...)` semantics).

```java
public Web loggerFactory(LoggerFactory loggerFactory)   // default = WebStdoutLoggerFactory.FACTORY
public Web logLevel(Level level)                        // default = Level.Info
public Web start(HTTPListenerConfiguration listener)    // new overload
public Web start(int port)                              // becomes start(new HTTPListenerConfiguration(port))
```

### Behavior

- `loggerFactory(null)` → `NullPointerException` (`Objects.requireNonNull`).
- `logLevel(null)` → `NullPointerException`.
- Both setters called after `start()` → `IllegalStateException("Cannot ... after Web has been started")`.
- `logLevel` is applied at `start()` time by calling `setLevel` on the logger returned by `factory.getLogger(Web.class)`. The factories in this codebase return a singleton, so this also affects the logger handed to `HTTPServer`.
- `start(HTTPListenerConfiguration)` is the new canonical entry point. `start(int port)` is preserved as a thin convenience that delegates to it.

## Default logger: `org.lattejava.web.log`

A new exported subpackage holds two classes, mirroring the `org.lattejava.http.log.SystemOutLogger` / `SystemOutLoggerFactory` pair.

```
src/main/java/org/lattejava/web/log/
  WebStdoutLogger.java         extends BaseLogger
  WebStdoutLoggerFactory.java  implements LoggerFactory
```

### `WebStdoutLogger`

- `handleMessage(String)` writes to `System.out.println(message)` (same as `SystemOutLogger`).
- `timestamp()` overrides `BaseLogger.timestamp()` to return:

  ```java
  OffsetDateTime.now()
                .truncatedTo(ChronoUnit.MILLIS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " "
  ```

  Produces e.g. `2026-04-27T13:45:23.689-04:00 `. Uses the system default zone implicitly via `OffsetDateTime.now()`.

- All level filtering, `{}` substitution, and throwable formatting come from `BaseLogger` unchanged.

### `WebStdoutLoggerFactory`

Mirrors `SystemOutLoggerFactory`:

- `public static final WebStdoutLoggerFactory FACTORY = new WebStdoutLoggerFactory();`
- Holds a single `WebStdoutLogger` instance.
- `getLogger(Class<?>)` returns the singleton.

## Startup message

After `HTTPServer.start()` returns successfully, `Web` emits one info line:

```
Web application is available at [http://localhost:8080]
```

Logger used is `factory.getLogger(Web.class)` (the same factory configured on the server, so output goes to the same destination). Value bracketed per `error-messages.md`.

### URL composition (`buildURL(HTTPListenerConfiguration)`)

Private static helper on `Web`. Components:

- **scheme:** `listener.isTLS() ? "https" : "http"`
- **host:**
  - If `listener.getBindAddress().isAnyLocalAddress()` → `"localhost"`.
  - Else `listener.getBindAddress().getHostAddress()`. If the address is an `Inet6Address`, wrap in brackets per RFC 3986 (`[2001:db8::1]`).
- **port:** `listener.getPort()`.

Concatenated as `scheme + "://" + host + ":" + port`.

## `start()` flow

```java
public Web start(int port) {
  return start(new HTTPListenerConfiguration(port));
}

public Web start(HTTPListenerConfiguration listener) {
  if (isChild) {
    throw new IllegalStateException("Cannot call start on a prefix child Web instance");
  }
  if (started.get()) {
    throw new IllegalStateException("Web has already been started");
  }
  Objects.requireNonNull(listener, "listener cannot be null");

  LoggerFactory factory = loggerFactory != null ? loggerFactory : WebStdoutLoggerFactory.FACTORY;
  Logger log = factory.getLogger(Web.class);
  if (logLevel != null) {
    log.setLevel(logLevel);
  }

  HTTPServer newServer = new HTTPServer()
      .withHandler(this::handleRequest)
      .withListener(listener)
      .withLoggerFactory(factory)
      .withBaseDir(baseDir != null ? baseDir : Paths.get("."))
      .start();

  Thread hook;
  try {
    hook = new Thread(this::closeServer, "Web shutdown hook");
    Runtime.getRuntime().addShutdownHook(hook);
  } catch (IllegalStateException e) {
    newServer.close();
    throw e;
  }

  server = newServer;
  shutdownHook = hook;
  started.set(true);

  log.info("Web application is available at [{}]", buildURL(listener));
  return this;
}
```

New instance fields on `Web`: `private LoggerFactory loggerFactory;` and `private Level logLevel;` — both nullable, lazy-defaulted at start.

## Module changes

`module-info.java` gains one `exports` clause, alphabetized between the existing entries:

```java
exports org.lattejava.web.log;
```

No new `requires` — `org.lattejava.http` is already required (transitively gives access to `Logger`, `LoggerFactory`, `BaseLogger`, `Level`).

## Conventions

All new files follow the project rules in `.claude/rules/`:

- MIT/Latte Java 2025-2026 license header.
- `@author Brian Pontarelli`.
- Acronyms full uppercase: `ISO`, `URL`, `HTTP`, `TLS`.
- Members alphabetized per `code-conventions.md`.
- Runtime values in messages bracketed (`[value]`) per `error-messages.md`.
- Prefer module imports (`import module java.base;`, `import module org.lattejava.http;`).

## Testing (TestNG)

### `WebStdoutLoggerTest`

- Capture `System.out`, log an info message, assert the line matches `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2} <msg>$`.
- Set level to `Error`, call `info(...)`, assert nothing is written.
- Log with `{}` substitution, assert the value appears in place.
- Log with a throwable, assert the stack trace follows the message.

### `WebStdoutLoggerFactoryTest`

- `FACTORY.getLogger(A.class) == FACTORY.getLogger(B.class)` (singleton).

### `WebTest` extensions

- `loggerFactory(null)` throws NPE.
- `logLevel(null)` throws NPE.
- Both setters throw `IllegalStateException` after `start()`.
- Using a recording `LoggerFactory`, start on an ephemeral port; assert exactly one info line was emitted with class `Web.class` containing `http://localhost:`.
- TLS listener (reusing an existing test certificate helper if present) → URL starts with `https://`.
- Bind to `127.0.0.1` → URL contains `127.0.0.1`, not `localhost`.
- IPv6 loopback `[::1]` → URL contains `[::1]`.

Existing `Web` tests stay green; the new info line is observed only by tests that opt into it via a recording factory.
