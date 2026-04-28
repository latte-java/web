# Web Logging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `LoggerFactory` used by `Web` swappable, ship a Web-flavored default with ISO-offset timestamps, and emit a startup info message containing a clickable URL.

**Architecture:** New `org.lattejava.web.log` subpackage holds `WebPrintStreamLogger` (subclasses `BaseLogger`, overrides `timestamp()` to format with `ISO_OFFSET_DATE_TIME`) and `WebPrintStreamLoggerFactory` (singleton). `Web` gains `loggerFactory(LoggerFactory)` and `logLevel(Level)` pre-start setters and a new `start(HTTPListenerConfiguration)` overload. The existing `start(int)` becomes a thin convenience. After `HTTPServer.start()` returns, `Web` logs `Web application is available at [<url>]`, where the URL is built from the listener: `http`/`https` from `isTLS()`, `localhost` if `bindAddress.isAnyLocalAddress()` else `getHostAddress()` (IPv6 in brackets), and the configured port.

**Tech Stack:** Java 25, Java module system, TestNG 7.12, Latte build tool, `org.lattejava:http` (provides `Logger`, `LoggerFactory`, `BaseLogger`, `Level`, `HTTPServer`, `HTTPListenerConfiguration`).

**Branch:** `features/web-logging` (already created).

**Reference spec:** `docs/design/web-logging.md`.

**Conventions to follow** (from `.claude/rules/`):
- License header on every new file: MIT, Latte Java, 2025-2026 (copy from any existing source file in this module).
- `@author Brian Pontarelli`.
- Acronyms full uppercase: `ISO`, `URL`, `HTTP`, `TLS`.
- Members alphabetized within visibility groups.
- Runtime values bracketed: `[value]` not `'value'`.
- Prefer `import module ...;` over class imports.

---

## File Structure

**Create:**
- `src/main/java/org/lattejava/web/log/WebPrintStreamLogger.java` — ISO-offset timestamp, prints to a configurable `PrintStream` (default `System.out`).
- `src/main/java/org/lattejava/web/log/WebPrintStreamLoggerFactory.java` — singleton factory; constructor accepts an optional `PrintStream`.
- `src/test/java/org/lattejava/web/tests/log/WebPrintStreamLoggerTest.java` — formatter, level filtering, null-stream rejection (uses an injected `PrintStream`, no `System.out` redirection).
- `src/test/java/org/lattejava/web/tests/log/WebPrintStreamLoggerFactoryTest.java` — singleton check, custom-stream routing.
- `src/test/java/org/lattejava/web/tests/log/RecordingLoggerFactory.java` — test helper used by `LoggingTest`.
- `src/test/java/org/lattejava/web/tests/LoggingTest.java` — integration tests for `Web.loggerFactory`, `Web.logLevel`, startup URL.

**Modify:**
- `src/main/java/module-info.java` — add `exports org.lattejava.web.log;` (alphabetized).
- `src/main/java/org/lattejava/web/Web.java` — add `loggerFactory` field + setter, `logLevel` field + setter, new `start(HTTPListenerConfiguration)` overload, `buildURL` helper, default-factory wiring, startup info log.

---

## Task 1: WebPrintStreamLogger

**Files:**
- Create: `src/main/java/org/lattejava/web/log/WebPrintStreamLogger.java`
- Test: `src/test/java/org/lattejava/web/tests/log/WebPrintStreamLoggerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/web/tests/log/WebPrintStreamLoggerTest.java`:

```java
/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.log;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

public class WebPrintStreamLoggerTest {
  private static final Pattern ISO_LINE = Pattern.compile(
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2} (.+)$");

  private ByteArrayOutputStream captured;
  private PrintStream stream;

  @BeforeMethod
  public void setUp() {
    captured = new ByteArrayOutputStream();
    stream = new PrintStream(captured, true, StandardCharsets.UTF_8);
  }

  @Test
  public void defaultConstructor_doesNotThrow() {
    new WebPrintStreamLogger();
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void constructor_nullStream_throws() {
    new WebPrintStreamLogger(null);
  }

  @Test
  public void info_emitsISOOffsetTimestamp() {
    var logger = new WebPrintStreamLogger(stream);
    logger.info("hello");

    String line = captured.toString(StandardCharsets.UTF_8).strip();
    Matcher m = ISO_LINE.matcher(line);
    assertTrue(m.matches(), "Expected ISO-offset prefix; got [" + line + "]");
    assertEquals(m.group(1), "hello");
  }

  @Test
  public void info_substitutesValues() {
    var logger = new WebPrintStreamLogger(stream);
    logger.info("at {}", "http://localhost:8080");

    String line = captured.toString(StandardCharsets.UTF_8).strip();
    assertTrue(line.endsWith("at http://localhost:8080"), "Got [" + line + "]");
  }

  @Test
  public void info_belowLevel_emitsNothing() {
    var logger = new WebPrintStreamLogger(stream);
    logger.setLevel(Level.Error);
    logger.info("hello");

    assertEquals(captured.toString(StandardCharsets.UTF_8), "");
  }

  @Test
  public void error_withThrowable_includesStackTrace() {
    var logger = new WebPrintStreamLogger(stream);
    logger.error("boom", new RuntimeException("kapow"));

    String out = captured.toString(StandardCharsets.UTF_8);
    assertTrue(out.contains("boom"), "Got [" + out + "]");
    assertTrue(out.contains("java.lang.RuntimeException: kapow"), "Got [" + out + "]");
  }
}
```

Note: `WebPrintStreamLogger` must live in `org.lattejava.web.log`. Since the module exports that package (Task 3) and tests live in the same module, the test compiles once the class exists.

- [ ] **Step 2: Run the test to verify it fails**

```bash
latte test
```

Expected: compilation failure — `WebPrintStreamLogger` does not exist.

- [ ] **Step 3: Create the WebPrintStreamLogger class**

Create `src/main/java/org/lattejava/web/log/WebPrintStreamLogger.java`:

```java
/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.log;

import module java.base;
import module org.lattejava.http;

/**
 * A {@link PrintStream}-backed logger whose timestamp prefix is an ISO-8601 offset date-time formatted in the system
 * default time zone (e.g. {@code 2026-04-27T13:45:23.689-04:00}). Defaults to {@link System#out}; tests can inject a
 * different stream (e.g. one wrapping a {@link ByteArrayOutputStream}) without redirecting {@code System.out}.
 *
 * @author Brian Pontarelli
 */
public class WebPrintStreamLogger extends BaseLogger {
  private static final DateTimeFormatter TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
      .append(DateTimeFormatter.ISO_LOCAL_DATE)
      .appendLiteral('T')
      .appendValue(ChronoField.HOUR_OF_DAY, 2)
      .appendLiteral(':')
      .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
      .appendLiteral(':')
      .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
      .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
      .appendOffsetId()
      .toFormatter();

  private final PrintStream out;

  public WebPrintStreamLogger() {
    this(System.out);
  }

  public WebPrintStreamLogger(PrintStream out) {
    Objects.requireNonNull(out, "out must not be null");
    this.out = out;
  }

  @Override
  protected void handleMessage(String message) {
    out.println(message);
  }

  @Override
  protected String timestamp() {
    return OffsetDateTime.now().format(TIMESTAMP_FORMATTER) + " ";
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
latte test
```

Expected: all four tests in `WebPrintStreamLoggerTest` pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/log/WebPrintStreamLogger.java src/test/java/org/lattejava/web/tests/log/WebPrintStreamLoggerTest.java
git commit -m "Add WebPrintStreamLogger with ISO-offset timestamp"
```

---

## Task 2: WebPrintStreamLoggerFactory

**Files:**
- Create: `src/main/java/org/lattejava/web/log/WebPrintStreamLoggerFactory.java`
- Test: `src/test/java/org/lattejava/web/tests/log/WebPrintStreamLoggerFactoryTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/web/tests/log/WebPrintStreamLoggerFactoryTest.java`:

```java
/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.log;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.http.log.Logger;

import static org.testng.Assert.*;

public class WebPrintStreamLoggerFactoryTest {
  @Test
  public void factory_isSingleton() {
    var factory = WebPrintStreamLoggerFactory.FACTORY;
    Logger a = factory.getLogger(String.class);
    Logger b = factory.getLogger(Integer.class);

    assertNotNull(a);
    assertSame(a, b, "Factory should return the same Logger instance for any class");
  }

  @Test
  public void factory_returnsWebPrintStreamLogger() {
    Logger logger = WebPrintStreamLoggerFactory.FACTORY.getLogger(Object.class);
    assertTrue(logger instanceof WebPrintStreamLogger, "Expected WebPrintStreamLogger; got [" + logger.getClass() + "]");
  }

  @Test
  public void factory_withCustomStream_routesOutputThroughIt() {
    var captured = new ByteArrayOutputStream();
    var stream = new PrintStream(captured, true, StandardCharsets.UTF_8);
    var factory = new WebPrintStreamLoggerFactory(stream);

    factory.getLogger(Object.class).info("hello");

    assertTrue(captured.toString(StandardCharsets.UTF_8).contains("hello"),
        "Expected captured output to contain [hello]; got [" + captured.toString(StandardCharsets.UTF_8) + "]");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
latte test
```

Expected: compilation failure — `WebPrintStreamLoggerFactory` does not exist.

- [ ] **Step 3: Create the WebPrintStreamLoggerFactory class**

Create `src/main/java/org/lattejava/web/log/WebPrintStreamLoggerFactory.java`:

```java
/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.log;

import module java.base;
import module org.lattejava.http;

/**
 * A {@link LoggerFactory} that always returns a single shared {@link WebPrintStreamLogger} writing to a configurable
 * {@link PrintStream} (defaulting to {@link System#out}). This is the default factory used by
 * {@link org.lattejava.web.Web} when none is configured.
 *
 * @author Brian Pontarelli
 */
public class WebPrintStreamLoggerFactory implements LoggerFactory {
  public static final WebPrintStreamLoggerFactory FACTORY = new WebPrintStreamLoggerFactory();

  private final WebPrintStreamLogger logger;

  public WebPrintStreamLoggerFactory() {
    this(System.out);
  }

  public WebPrintStreamLoggerFactory(PrintStream out) {
    this.logger = new WebPrintStreamLogger(out);
  }

  @Override
  public Logger getLogger(Class<?> klass) {
    return logger;
  }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
latte test
```

Expected: both tests in `WebPrintStreamLoggerFactoryTest` pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/log/WebPrintStreamLoggerFactory.java src/test/java/org/lattejava/web/tests/log/WebPrintStreamLoggerFactoryTest.java
git commit -m "Add WebPrintStreamLoggerFactory singleton"
```

---

## Task 3: Export `org.lattejava.web.log`

**Files:**
- Modify: `src/main/java/module-info.java`

- [ ] **Step 1: Inspect current exports**

Run:

```bash
cat src/main/java/module-info.java
```

Expected current contents:

```java
module org.lattejava.web {
  requires com.fasterxml.jackson.databind;
  requires java.net.http;
  requires org.lattejava.http;
  requires org.lattejava.jwt;

  exports org.lattejava.web;
  exports org.lattejava.web.json;
  exports org.lattejava.web.middleware;
  exports org.lattejava.web.oidc;
  exports org.lattejava.web.oidc.internal;
}
```

- [ ] **Step 2: Add the new export, alphabetized**

Insert `exports org.lattejava.web.log;` between `exports org.lattejava.web.json;` and `exports org.lattejava.web.middleware;`.

Final contents:

```java
module org.lattejava.web {
  requires com.fasterxml.jackson.databind;
  requires java.net.http;
  requires org.lattejava.http;
  requires org.lattejava.jwt;

  exports org.lattejava.web;
  exports org.lattejava.web.json;
  exports org.lattejava.web.log;
  exports org.lattejava.web.middleware;
  exports org.lattejava.web.oidc;
  exports org.lattejava.web.oidc.internal;
}
```

- [ ] **Step 3: Verify the build**

```bash
latte test
```

Expected: build succeeds, all existing tests plus the two new test classes (Tasks 1–2) pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/module-info.java
git commit -m "Export org.lattejava.web.log"
```

---

## Task 4: RecordingLoggerFactory test helper

**Files:**
- Create: `src/test/java/org/lattejava/web/tests/log/RecordingLoggerFactory.java`

This helper is used by every integration test that needs to observe log output without writing to stdout. It records each `(class, level, message)` tuple as it is emitted.

- [ ] **Step 1: Create the helper**

Create `src/test/java/org/lattejava/web/tests/log/RecordingLoggerFactory.java`:

```java
/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.log;

import module java.base;
import module org.lattejava.http;

/**
 * Test helper that records every log call for inspection. Returns a single shared {@link RecordingLogger} for any
 * class — matching the singleton semantics of the bundled factories so {@code setLevel} affects every observer.
 *
 * @author Brian Pontarelli
 */
public class RecordingLoggerFactory implements LoggerFactory {
  public final RecordingLogger logger = new RecordingLogger();

  @Override
  public Logger getLogger(Class<?> klass) {
    return logger;
  }

  public static final class Entry {
    public final Level level;
    public final String message;
    public final Throwable throwable;

    Entry(Level level, String message, Throwable throwable) {
      this.level = level;
      this.message = message;
      this.throwable = throwable;
    }
  }

  public static final class RecordingLogger implements Logger {
    public final List<Entry> entries = new ArrayList<>();
    private Level level = Level.Info;

    @Override
    public void debug(String message) {
      record(Level.Debug, message, null);
    }

    @Override
    public void debug(String message, Object... values) {
      record(Level.Debug, format(message, values), null);
    }

    @Override
    public void debug(String message, Throwable throwable) {
      record(Level.Debug, message, throwable);
    }

    @Override
    public void error(String message) {
      record(Level.Error, message, null);
    }

    @Override
    public void error(String message, Throwable throwable) {
      record(Level.Error, message, throwable);
    }

    @Override
    public void info(String message) {
      record(Level.Info, message, null);
    }

    @Override
    public void info(String message, Object... values) {
      record(Level.Info, format(message, values), null);
    }

    @Override
    public boolean isDebugEnabled() {
      return level.ordinal() <= Level.Debug.ordinal();
    }

    @Override
    public boolean isErrorEnabled() {
      return level.ordinal() <= Level.Error.ordinal();
    }

    @Override
    public boolean isInfoEnabled() {
      return level.ordinal() <= Level.Info.ordinal();
    }

    @Override
    public boolean isTraceEnabled() {
      return level.ordinal() <= Level.Trace.ordinal();
    }

    @Override
    public void setLevel(Level level) {
      this.level = level;
    }

    @Override
    public void trace(String message) {
      record(Level.Trace, message, null);
    }

    @Override
    public void trace(String message, Object... values) {
      record(Level.Trace, format(message, values), null);
    }

    public List<String> messagesAtLevel(Level wanted) {
      List<String> out = new ArrayList<>();
      for (Entry e : entries) {
        if (e.level == wanted) {
          out.add(e.message);
        }
      }
      return out;
    }

    private String format(String message, Object[] values) {
      for (Object value : values) {
        String replacement = value != null ? value.toString() : "null";
        message = message.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(replacement));
      }
      return message;
    }

    private void record(Level entryLevel, String message, Throwable throwable) {
      if (entryLevel.ordinal() < level.ordinal()) {
        return;
      }
      entries.add(new Entry(entryLevel, message, throwable));
    }
  }
}
```

- [ ] **Step 2: Verify the helper compiles**

```bash
latte test
```

Expected: build still passes (helper is unused; no behavior change yet).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/lattejava/web/tests/log/RecordingLoggerFactory.java
git commit -m "Add RecordingLoggerFactory test helper"
```

---

## Task 5: `Web.loggerFactory(LoggerFactory)` setter wired through `start()`

**Files:**
- Modify: `src/main/java/org/lattejava/web/Web.java`
- Create: `src/test/java/org/lattejava/web/tests/LoggingTest.java`

This task adds the setter, wires it into the existing `start(int port)` so the configured factory replaces `new SystemOutLoggerFactory()`, and defaults to `WebPrintStreamLoggerFactory.FACTORY` when none is configured.

- [ ] **Step 1: Write failing tests**

Create `src/test/java/org/lattejava/web/tests/LoggingTest.java`:

```java
/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.log.RecordingLoggerFactory;

import static org.testng.Assert.*;

public class LoggingTest extends BaseWebTest {
  @Test
  public void loggerFactory_afterStart_throws() {
    try (var web = new Web()) {
      web.start(PORT);
      try {
        web.loggerFactory(new RecordingLoggerFactory());
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void loggerFactory_null_throws() {
    new Web().loggerFactory(null);
  }

  @Test
  public void loggerFactory_swapped_routesHTTPServerLogs() {
    var recording = new RecordingLoggerFactory();
    try (var web = new Web()) {
      web.loggerFactory(recording).start(PORT);
    }

    // HTTPServer emits at least one info line during startup. Whatever it logs must have flowed through
    // the configured factory — i.e., into the recording logger's entries — rather than to stdout via
    // the bundled SystemOutLoggerFactory.
    assertFalse(recording.logger.entries.isEmpty(),
        "Expected HTTPServer log lines to be captured by the configured factory");
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
latte test
```

Expected: compilation failure — `Web.loggerFactory(LoggerFactory)` does not exist.

- [ ] **Step 3: Add the field, setter, and default-factory wiring**

Open `src/main/java/org/lattejava/web/Web.java`.

Add a new instance field, alphabetized with the existing nullable instance fields (`baseDir`, `server`, `shutdownHook`):

```java
  private LoggerFactory loggerFactory;
```

Add the setter, alphabetized with the other `Web` instance methods (between `install` and `options`):

```java
  /**
   * Sets the {@link LoggerFactory} used by this Web instance and the underlying HTTP server. If not called, the default
   * is {@link WebPrintStreamLoggerFactory#FACTORY}.
   *
   * @param loggerFactory The factory.
   * @return This Web instance for chaining.
   * @throws IllegalStateException if called after {@link #start(int)}.
   */
  public Web loggerFactory(LoggerFactory loggerFactory) {
    if (started.get()) {
      throw new IllegalStateException("Cannot set loggerFactory after Web has been started");
    }
    Objects.requireNonNull(loggerFactory, "loggerFactory must not be null");
    this.loggerFactory = loggerFactory;
    return this;
  }
```

Add an `import` for the default factory at the top of the file (with the existing `org.lattejava.web.internal.*` import), alphabetized:

```java
import org.lattejava.web.internal.*;
import org.lattejava.web.log.WebPrintStreamLoggerFactory;
```

In `start(int port)`, replace the existing line:

```java
        .withLoggerFactory(new SystemOutLoggerFactory())
```

with:

```java
        .withLoggerFactory(loggerFactory != null ? loggerFactory : WebPrintStreamLoggerFactory.FACTORY)
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
latte test
```

Expected: all three new tests pass; existing tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/Web.java src/test/java/org/lattejava/web/tests/LoggingTest.java
git commit -m "Add Web.loggerFactory setter with WebPrintStreamLoggerFactory default"
```

---

## Task 6: `Web.logLevel(Level)` setter applied at start

**Files:**
- Modify: `src/main/java/org/lattejava/web/Web.java`
- Modify: `src/test/java/org/lattejava/web/tests/LoggingTest.java`

- [ ] **Step 1: Add failing tests**

Append to `src/test/java/org/lattejava/web/tests/LoggingTest.java` (inside the class):

```java
  @Test
  public void logLevel_afterStart_throws() {
    try (var web = new Web()) {
      web.start(PORT);
      try {
        web.logLevel(Level.Debug);
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test
  public void logLevel_atError_suppressesInfo() {
    var recording = new RecordingLoggerFactory();
    try (var web = new Web()) {
      web.loggerFactory(recording).logLevel(Level.Error).start(PORT);
    }

    // HTTPServer emits info-level messages during start. With logLevel set to Error before start(),
    // those info lines must not appear in the recorded entries.
    assertTrue(recording.logger.messagesAtLevel(Level.Info).isEmpty(),
        "Expected no Info entries; got " + recording.logger.messagesAtLevel(Level.Info));
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void logLevel_null_throws() {
    new Web().logLevel(null);
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
latte test
```

Expected: compilation failure — `Web.logLevel(Level)` does not exist.

- [ ] **Step 3: Add the field and setter**

In `Web.java`, add an instance field next to `loggerFactory` (alphabetized: `logLevel` before `loggerFactory`):

```java
  private Level logLevel;
  private LoggerFactory loggerFactory;
```

Add the setter, alphabetized with the other `Web` instance methods (between `install` and `loggerFactory`):

```java
  /**
   * Sets the level applied to the logger this Web instance retrieves from its {@link LoggerFactory}. If the factory
   * shares a single logger across classes (as the bundled factories do), this also affects the logger used by the
   * underlying HTTP server.
   *
   * @param logLevel The level.
   * @return This Web instance for chaining.
   * @throws IllegalStateException if called after {@link #start(int)}.
   */
  public Web logLevel(Level logLevel) {
    if (started.get()) {
      throw new IllegalStateException("Cannot set logLevel after Web has been started");
    }
    Objects.requireNonNull(logLevel, "logLevel must not be null");
    this.logLevel = logLevel;
    return this;
  }
```

- [ ] **Step 4: Apply the level inside `start(int port)`**

In `start(int port)`, immediately before the `HTTPServer newServer = ...` block, resolve the factory and apply the level:

```java
    LoggerFactory factory = loggerFactory != null ? loggerFactory : WebPrintStreamLoggerFactory.FACTORY;
    Logger log = factory.getLogger(Web.class);
    if (logLevel != null) {
      log.setLevel(logLevel);
    }

    HTTPServer newServer = new HTTPServer()
        .withHandler(this::handleRequest)
        .withListener(new HTTPListenerConfiguration(port))
        .withLoggerFactory(factory)
        .withBaseDir(baseDir != null ? baseDir : Paths.get("."))
        .start();
```

(`factory` replaces the `loggerFactory != null ? ... : WebPrintStreamLoggerFactory.FACTORY` ternary that was inlined in Task 5; the `.withLoggerFactory(factory)` line now references the resolved local.)

- [ ] **Step 5: Run the tests to verify they pass**

```bash
latte test
```

Expected: the three new tests pass; all earlier tests still pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/web/Web.java src/test/java/org/lattejava/web/tests/LoggingTest.java
git commit -m "Add Web.logLevel setter applied at start"
```

---

## Task 7: `Web.start(HTTPListenerConfiguration)` overload + startup URL message

**Files:**
- Modify: `src/main/java/org/lattejava/web/Web.java`
- Modify: `src/test/java/org/lattejava/web/tests/LoggingTest.java`

This task:
1. Extracts `start(int)` into a new `start(HTTPListenerConfiguration)` method that contains all the existing start logic (now operating on the supplied listener instead of one built from the port).
2. Reduces `start(int)` to a thin convenience: `return start(new HTTPListenerConfiguration(port));`.
3. Adds a private static `buildURL(HTTPListenerConfiguration)` helper.
4. Logs the startup URL info message after `HTTPServer.start()` returns.

- [ ] **Step 1: Add failing tests**

Append to `src/test/java/org/lattejava/web/tests/LoggingTest.java`:

```java
  @Test
  public void start_intPort_logsHTTPLocalhostURL() {
    var recording = new RecordingLoggerFactory();
    try (var web = new Web()) {
      web.loggerFactory(recording).start(PORT);
    }

    String expected = "Web application is available at [http://localhost:" + PORT + "]";
    List<String> infos = recording.logger.messagesAtLevel(Level.Info);
    assertTrue(infos.contains(expected),
        "Expected info message [" + expected + "] in " + infos);
  }

  @Test
  public void start_specificIPv4_logsThatHost() throws Exception {
    var recording = new RecordingLoggerFactory();
    var listener = new HTTPListenerConfiguration(InetAddress.getByName("127.0.0.1"), PORT);
    try (var web = new Web()) {
      web.loggerFactory(recording).start(listener);
    }

    String expected = "Web application is available at [http://127.0.0.1:" + PORT + "]";
    List<String> infos = recording.logger.messagesAtLevel(Level.Info);
    assertTrue(infos.contains(expected),
        "Expected info message [" + expected + "] in " + infos);
  }

  @Test
  public void start_ipv6Loopback_bracketsAddress() throws Exception {
    var recording = new RecordingLoggerFactory();
    var listener = new HTTPListenerConfiguration(InetAddress.getByName("::1"), PORT);
    try (var web = new Web()) {
      web.loggerFactory(recording).start(listener);
    }

    String expected = "Web application is available at [http://[::1]:" + PORT + "]";
    List<String> infos = recording.logger.messagesAtLevel(Level.Info);
    assertTrue(infos.contains(expected),
        "Expected info message [" + expected + "] in " + infos);
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void start_nullListener_throws() {
    new Web().start((HTTPListenerConfiguration) null);
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
latte test
```

Expected: compilation failure — `Web.start(HTTPListenerConfiguration)` does not exist.

- [ ] **Step 3: Add the overload, helper, and startup log**

In `Web.java`, replace the existing `start(int port)` method with both the convenience and the new overload (alphabetized: `start(HTTPListenerConfiguration)` before `start(int)` — but constructors/overloads should sit together; place the new overload immediately above the existing `start(int port)` so the file scans top-to-bottom):

```java
  /**
   * Starts the HTTP server using the given listener configuration.
   *
   * @param listener The listener configuration.
   * @return This Web instance for chaining.
   */
  public Web start(HTTPListenerConfiguration listener) {
    if (isChild) {
      throw new IllegalStateException("Cannot call start on a prefix child Web instance");
    }
    if (started.get()) {
      throw new IllegalStateException("Web has already been started");
    }
    Objects.requireNonNull(listener, "listener must not be null");

    LoggerFactory factory = loggerFactory != null ? loggerFactory : WebPrintStreamLoggerFactory.FACTORY;
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

  /**
   * Starts the HTTP server on the given port using a default listener configuration (non-TLS, all interfaces).
   *
   * @param port The port to listen on.
   * @return This Web instance for chaining.
   */
  public Web start(int port) {
    return start(new HTTPListenerConfiguration(port));
  }
```

Add a private static helper alongside the existing private static `isValidMethodToken`, alphabetized:

```java
  private static String buildURL(HTTPListenerConfiguration listener) {
    String scheme = listener.isTLS() ? "https" : "http";
    InetAddress address = listener.getBindAddress();
    String host;
    if (address.isAnyLocalAddress()) {
      host = "localhost";
    } else if (address instanceof Inet6Address) {
      host = "[" + address.getHostAddress() + "]";
    } else {
      host = address.getHostAddress();
    }
    return scheme + "://" + host + ":" + listener.getPort();
  }
```

`Inet6Address` is in `java.net`. The file already declares `import module java.base;`, which transitively brings in `java.net.*` — no additional import needed. Verify by running the build.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
latte test
```

Expected: all four new tests pass. All earlier tests still pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/Web.java src/test/java/org/lattejava/web/tests/LoggingTest.java
git commit -m "Add Web.start(HTTPListenerConfiguration) overload and startup URL log"
```

---

## Task 8: Final verification

- [ ] **Step 1: Run the full test suite once more from a clean build**

```bash
latte clean && latte test
```

Expected: clean build succeeds; every test in the project passes (including the new logging tests).

- [ ] **Step 2: Visually verify the live startup output**

Run a small ad-hoc smoke check by writing a one-off `Main`-style harness OR rely on an existing example app if one exists. If neither, skip this step — automated tests already cover the message content. Otherwise:

```bash
# Example smoke test (skip if no Main.java exists in the module)
latte build
java --module-path build/jars:build/jars/dependencies -m org.lattejava.web/org.lattejava.web.Main
```

Expected console output should look like (ISO timestamp will vary):

```
2026-04-27T13:45:23.689-04:00 Starting the HTTP server. Buckle up!
2026-04-27T13:45:23.701-04:00 HTTP server listening on port [8080]
2026-04-27T13:45:23.702-04:00 HTTP server started successfully
2026-04-27T13:45:23.702-04:00 Web application is available at [http://localhost:8080]
```

- [ ] **Step 3: Confirm branch state**

```bash
git -C . log --oneline main..HEAD
```

Expected: 7 commits on `features/web-logging` beyond `main`, one per task above plus the design-doc commit already made.

- [ ] **Step 4: Verify git status is clean**

```bash
git status
```

Expected: working tree clean, on `features/web-logging`.
