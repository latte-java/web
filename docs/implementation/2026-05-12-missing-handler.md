# Missing Handler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional `Handler missingHandler` to `Web`, registered via `web.missingHandler(Handler)`, that replaces the framework's default 404 behavior in both branches of `Web.handleRequest`'s `NotFound` case.

**Architecture:** One mutable instance field plus one fluent setter on `Web`. The `NotFound` switch arm in `handleRequest` chooses between the user-supplied handler and an inline default `(_, res) -> res.setStatus(404)`. The chosen handler is invoked directly when no prefix middlewares matched, or as the terminal of a `MiddlewareChainImpl` when they did.

**Tech Stack:** Java 25 (module system), Latte build tool, TestNG.

**Spec:** `docs/design/2026-05-12-missing-handler.md`

---

## File structure

- Modify: `src/main/java/org/lattejava/web/Web.java` — one field, one setter, one branch body change.
- Create: `src/test/java/org/lattejava/web/tests/MissingHandlerTest.java` — TestNG test class with 7 tests.

Both packages are already exported / opened via `module-info.java`; no module-info changes required.

---

## Conventions (apply to every Java file written below)

- SPDX header at the very top of every Java file:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
```

The existing `Web.java` already has a `2025-2026` range — leave it as is; substantive changes don't require bumping the range further.

- Acronyms uppercase. (Not relevant here — `missingHandler` has no acronym.)
- Error message values bracketed `[value]` (no current error message includes a runtime value, but follow the rule if you add one).
- Module imports preferred (`import module java.base;`).
- No blank lines between fields. The new field goes between two existing fields with no separator.
- Class member order: static fields → instance fields → constructors → static methods → instance methods → nested. Alphabetize within each group.
- Javadoc on public methods, American English.

---

## Build commands

- Test: `latte test` from the project root.
- Single class: `latte test --tests org.lattejava.web.tests.MissingHandlerTest` (note: in this repo the `--tests` filter does not narrow execution; the full suite runs either way. This is fine — the suite runs fast.)

---

## Task 1: Field, setter, dispatch, and tests

**Files:**
- Modify: `src/main/java/org/lattejava/web/Web.java`
- Create: `src/test/java/org/lattejava/web/tests/MissingHandlerTest.java`

- [ ] **Step 1: Write all 7 failing tests**

Create `src/test/java/org/lattejava/web/tests/MissingHandlerTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

public class MissingHandlerTest extends BaseWebTest {
  @Test
  public void missingHandler_defaultBehaviorPreservedWhenNotSet() throws Exception {
    try (var web = new Web()) {
      web.get("/exists", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/missing");
      assertEquals(response.statusCode(), 404);
      assertEquals(response.body(), "");
    }
  }

  @Test
  public void missingHandler_doesNotAffect405() throws Exception {
    try (var web = new Web()) {
      web.get("/x", (_, res) -> res.setStatus(200));
      web.missingHandler((_, res) -> {
        res.setStatus(418);
        res.setHeader("X-Missing-Handler", "ran");
      });
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/x");
      assertEquals(response.statusCode(), 405);
      assertEquals(response.headers().firstValue("Allow").orElse(null), "GET");
      assertFalse(response.headers().firstValue("X-Missing-Handler").isPresent(),
          "missingHandler must not run for 405");
    }
  }

  @Test
  public void missingHandler_invokedWhenNoRouteMatches() throws Exception {
    try (var web = new Web()) {
      web.get("/exists", (_, res) -> res.setStatus(200));
      web.missingHandler((req, res) -> {
        res.setStatus(410);
        res.setHeader("X-Missing-Path", req.getPath());
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/nope");
      assertEquals(response.statusCode(), 410);
      assertEquals(response.headers().firstValue("X-Missing-Path").orElse(null), "/nope");
    }
  }

  @Test
  public void missingHandler_rejectsCallAfterStart() throws Exception {
    try (var web = new Web()) {
      web.start(PORT);
      assertThrows(IllegalStateException.class,
          () -> web.missingHandler((_, res) -> res.setStatus(404)));
    }
  }

  @Test
  public void missingHandler_rejectsCallOnChildWeb() {
    try (var web = new Web()) {
      AtomicReference<Throwable> caught = new AtomicReference<>();
      web.prefix("/api", child -> {
        try {
          child.missingHandler((_, res) -> res.setStatus(404));
        } catch (Throwable t) {
          caught.set(t);
        }
      });
      assertNotNull(caught.get(), "Expected IllegalStateException, got nothing");
      assertTrue(caught.get() instanceof IllegalStateException,
          "Expected IllegalStateException, got: [" + caught.get() + "]");
    }
  }

  @Test
  public void missingHandler_rejectsNull() {
    try (var web = new Web()) {
      assertThrows(NullPointerException.class, () -> web.missingHandler(null));
    }
  }

  @Test
  public void missingHandler_runsAfterPrefixMiddlewares() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", child -> child.install((req, res, chain) -> {
        res.setHeader("X-Prefix-Middleware", "ran");
        chain.next(req, res);
      }));
      web.missingHandler((_, res) -> {
        res.setStatus(410);
        res.setHeader("X-Missing-Handler", "ran");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/api/nope");
      assertEquals(response.statusCode(), 410);
      assertEquals(response.headers().firstValue("X-Prefix-Middleware").orElse(null), "ran",
          "Prefix middleware must run before missingHandler");
      assertEquals(response.headers().firstValue("X-Missing-Handler").orElse(null), "ran",
          "missingHandler must still run after prefix middleware");
    }
  }
}
```

Notes on the test code:

- Tests extend `BaseWebTest` (existing helper that provides `PORT` and the `send(method, path)` HTTP client). See `src/test/java/org/lattejava/web/tests/BaseWebTest.java`.
- `missingHandler_rejectsCallOnChildWeb` cannot use `assertThrows` directly because the call happens inside the `prefix(...)` consumer callback, where the exception would propagate out of the lambda. Capturing it in an `AtomicReference` and asserting after the `prefix` call completes is the cleanest pattern. `AtomicReference` is in `java.util.concurrent.atomic` — reachable via `import module java.base;`.
- `prefix(...)` in this codebase signals child-Web via `isChild = true` set in the private constructor. The test exercises that path.
- All assertions use the project's `org.testng.Assert.*` static imports.
- Test method names follow the project's `methodName_describesBehavior` convention; methods are listed alphabetically.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `latte test`

Expected: compile errors — `Web` has no `missingHandler` method. The error count depends on how Latte surfaces compile failures; the key observation is that the suite cannot run because the test class doesn't compile.

- [ ] **Step 3: Modify `Web.java` — add field, setter, dispatch**

There are three edits to `src/main/java/org/lattejava/web/Web.java`.

**Edit 1 — add the field.** The current mutable-instance-field block (Web.java:26–30) is:

```java
  private Path baseDir;
  private Level logLevel;
  private LoggerFactory loggerFactory;
  private HTTPServer server;
  private Thread shutdownHook;
```

Insert `missingHandler` in alphabetical order between `loggerFactory` and `server`:

```java
  private Path baseDir;
  private Level logLevel;
  private LoggerFactory loggerFactory;
  private Handler missingHandler;
  private HTTPServer server;
  private Thread shutdownHook;
```

No blank lines between fields. `Handler` is in `org.lattejava.web` (same package as `Web`); no import needed.

**Edit 2 — add the setter.** The setter goes in alphabetical position among the public instance methods: between `loggerFactory(LoggerFactory)` (Web.java:227–234) and `options(...)` (Web.java:245). Specifically, immediately before the `/**` of `options`.

The exact code to insert:

```java
  /**
   * Sets the handler invoked when no route matches the request path. The handler receives the request and response
   * just like a route handler; it is responsible for setting an appropriate status (the default 404 behavior is
   * replaced wholesale). Any prefix middlewares that matched the unmatched path's prefix still run before this
   * handler is invoked.
   *
   * @param handler The handler to invoke for unmatched paths.
   * @return This Web instance for chaining.
   * @throws IllegalStateException if called on a prefix child Web, or after {@link #start(int)}.
   */
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

The trailing blank line matters — leave it so the existing blank line separating `options`'s Javadoc from `loggerFactory`'s closing brace remains.

**Edit 3 — replace the `NotFound` case body in `handleRequest`.** The current code (Web.java:523–530):

```java
      case RouteTrie.Outcome.NotFound(var segments) -> {
        List<Middleware> prefixMiddlewares = middlewareTrie.collect(segments);
        if (prefixMiddlewares.isEmpty()) {
          response.setStatus(404);
        } else {
          new MiddlewareChainImpl(prefixMiddlewares, (_, res) -> res.setStatus(404)).next(request, response);
        }
      }
```

Replace with:

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

The `MethodNotAllowed` case (Web.java:519–522) is unchanged.

- [ ] **Step 4: Run the tests and verify they all pass**

Run: `latte test`

Expected: full suite passes. Specifically the 7 new tests in `MissingHandlerTest` pass, AND the existing 408 tests (post-CSP-merge) continue to pass — in particular `RoutingTest.route_404_hasEmptyBody`, `RoutingTest.route_405_*`, and `SecurityHeadersTest.headersPresentOn404` (these assert the default 404 behavior and would catch a regression if the default-handler fallback broke).

The final summary line should read: `Total tests run: <N+7>, Passes: <N+7>, Failures: 0, Skips: 0` where `<N>` is the pre-task baseline (~408 after the CSP merge).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/Web.java \
        src/test/java/org/lattejava/web/tests/MissingHandlerTest.java
git commit -m "Web: optional missingHandler invoked for 404 in place of default"
```

The commit will be SSH-signed automatically. Do not pass `-S` or `--no-gpg-sign`.

---

## Self-review

**Spec coverage:**
- Field on `Web` → Edit 1.
- Setter `missingHandler(Handler)` with root-only guard, after-start guard, null rejection, fluent return → Edit 2.
- `handleRequest` `NotFound` dispatch using the handler if set, default otherwise; both branches use it; `MethodNotAllowed` unchanged → Edit 3.
- All seven tests from the spec are present in the test file → Step 1.
- Default 404 behavior preserved (regression contract) → covered by `missingHandler_defaultBehaviorPreservedWhenNotSet` plus the existing `RoutingTest.route_404_hasEmptyBody`, all run in Step 4.

**Placeholder scan:** No TBDs, no "implement later", every step shows the code or command.

**Type consistency:** Field type `Handler` matches setter param type matches dispatch variable type. `IllegalStateException` and `NullPointerException` (the latter via `Objects.requireNonNull`) match the spec.

**Spec gaps:** None identified.
