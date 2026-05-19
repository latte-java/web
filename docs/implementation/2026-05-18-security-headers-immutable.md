# SecurityHeaders Immutable CSP-style API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `Builder`-based `SecurityHeaders` with a deeply immutable, CSP-style API (`empty()` / `defaults()` factories + copy-on-write setters).

**Architecture:** `SecurityHeaders` keeps its ten `private final String` header fields and a single private all-args constructor. Two static factories build the all-`null` (`empty()`) and all-secure-default (`defaults()`) instances. Each per-header setter returns a brand-new instance with one field changed, so every instance is deeply immutable and thread-safe by JMM final-field semantics. `handle()` is unchanged.

**Tech Stack:** Java 25 (module system), TestNG, Latte build tool (`latte build`, `latte test`).

**Spec:** `docs/design/2026-05-18-security-headers-immutable.md`

**Branch:** `security-headers-immutable` (already checked out; spec already committed there).

**Note on TDD in a statically-typed module:** the test file and the source file compile together. Until `SecurityHeaders` exposes the new API, the whole module fails to compile — that compile failure *is* the red state for the test-first steps. Tasks are ordered so each commit leaves the tree green.

---

### Task 1: Rewrite `SecurityHeaders` to the immutable CSP-style API

**Files:**
- Modify (full rewrite of the class body): `src/main/java/org/lattejava/web/middleware/SecurityHeaders.java`

- [ ] **Step 1: Replace the entire file contents**

Replace the whole file with exactly this:

```java
/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.middleware;

import module org.lattejava.http;
import module org.lattejava.web;

/**
 * A middleware that emits a strict set of HTTP security headers on every response. Headers are written to the response
 * before the chain is invoked, so error responses (404/405/5xx) also carry them; a downstream handler can override any
 * header by calling {@code setHeader} again.
 * <p>
 * Instances are deeply immutable. Obtain one from {@link #defaults()} (every header at its most-secure value) or
 * {@link #empty()} (no headers at all), then derive variants with the per-header methods — each returns a new
 * {@code SecurityHeaders} with a single header changed; passing {@code null} clears that header. Because every field is
 * {@code final}, an instance is safely published to request threads regardless of when or how it is installed, and it
 * cannot be mutated after installation.
 * <p>
 * Caveat: this middleware does not run on {@code 405 Method Not Allowed} responses, which bypass the middleware chain.
 * Those responses carry only {@code Allow} and have no body, so the missing headers are not a meaningful gap.
 *
 * @author Brian Pontarelli
 */
public class SecurityHeaders implements Middleware {
  private final String contentSecurityPolicy;
  private final String crossOriginEmbedderPolicy;
  private final String crossOriginOpenerPolicy;
  private final String crossOriginResourcePolicy;
  private final String permissionsPolicy;
  private final String referrerPolicy;
  private final String strictTransportSecurity;
  private final String xContentTypeOptions;
  private final String xFrameOptions;
  private final String xXSSProtection;

  private SecurityHeaders(String contentSecurityPolicy, String crossOriginEmbedderPolicy,
                          String crossOriginOpenerPolicy, String crossOriginResourcePolicy,
                          String permissionsPolicy, String referrerPolicy, String strictTransportSecurity,
                          String xContentTypeOptions, String xFrameOptions, String xXSSProtection) {
    this.contentSecurityPolicy = contentSecurityPolicy;
    this.crossOriginEmbedderPolicy = crossOriginEmbedderPolicy;
    this.crossOriginOpenerPolicy = crossOriginOpenerPolicy;
    this.crossOriginResourcePolicy = crossOriginResourcePolicy;
    this.permissionsPolicy = permissionsPolicy;
    this.referrerPolicy = referrerPolicy;
    this.strictTransportSecurity = strictTransportSecurity;
    this.xContentTypeOptions = xContentTypeOptions;
    this.xFrameOptions = xFrameOptions;
    this.xXSSProtection = xXSSProtection;
  }

  /**
   * @return A new instance with every header set to its most-secure default value. The Content-Security-Policy default
   *     is {@code CSP.defaults().build()}.
   */
  public static SecurityHeaders defaults() {
    return new SecurityHeaders(
        CSP.defaults().build(),
        "require-corp",
        "same-origin",
        "same-origin",
        "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()",
        "no-referrer",
        "max-age=31536000; includeSubDomains; preload",
        "nosniff",
        "DENY",
        "0");
  }

  /**
   * @return A new instance with no headers set. Installed as-is it emits nothing; add headers with the per-header
   *     methods.
   */
  public static SecurityHeaders empty() {
    return new SecurityHeaders(null, null, null, null, null, null, null, null, null, null);
  }

  /**
   * @param csp The Content-Security-Policy builder, or {@code null} to clear the header.
   * @return A new instance with the Content-Security-Policy set to {@code csp.build()} (or cleared when {@code null}).
   */
  public SecurityHeaders contentSecurityPolicy(CSP csp) {
    return contentSecurityPolicy(csp == null ? null : csp.build());
  }

  /**
   * @param value The Content-Security-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Content-Security-Policy set to {@code value}.
   */
  public SecurityHeaders contentSecurityPolicy(String value) {
    return new SecurityHeaders(value, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The Cross-Origin-Embedder-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Cross-Origin-Embedder-Policy set to {@code value}.
   */
  public SecurityHeaders crossOriginEmbedderPolicy(String value) {
    return new SecurityHeaders(contentSecurityPolicy, value, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The Cross-Origin-Opener-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Cross-Origin-Opener-Policy set to {@code value}.
   */
  public SecurityHeaders crossOriginOpenerPolicy(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, value,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The Cross-Origin-Resource-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Cross-Origin-Resource-Policy set to {@code value}.
   */
  public SecurityHeaders crossOriginResourcePolicy(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        value, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    if (strictTransportSecurity != null && res.getHeader("Strict-Transport-Security") == null) {
      res.setHeader("Strict-Transport-Security", strictTransportSecurity);
    }
    if (contentSecurityPolicy != null && res.getHeader("Content-Security-Policy") == null) {
      String host = req.getHost();
      String csp = contentSecurityPolicy;
      if (host.equalsIgnoreCase("localhost") || host.equalsIgnoreCase("127.0.0.1")) {
        csp = csp.replaceAll(
            "(?:\\bupgrade-insecure-requests\\s*;\\s*|\\s*;\\s*\\bupgrade-insecure-requests\\b\\s*$|^\\s*upgrade-insecure-requests\\s*$)",
            "").trim();
      }

      res.setHeader("Content-Security-Policy", csp);
    }
    if (xContentTypeOptions != null && res.getHeader("X-Content-Type-Options") == null) {
      res.setHeader("X-Content-Type-Options", xContentTypeOptions);
    }
    if (xFrameOptions != null && res.getHeader("X-Frame-Options") == null) {
      res.setHeader("X-Frame-Options", xFrameOptions);
    }
    if (xXSSProtection != null && res.getHeader("X-XSS-Protection") == null) {
      res.setHeader("X-XSS-Protection", xXSSProtection);
    }
    if (referrerPolicy != null && res.getHeader("Referrer-Policy") == null) {
      res.setHeader("Referrer-Policy", referrerPolicy);
    }
    if (permissionsPolicy != null && res.getHeader("Permissions-Policy") == null) {
      res.setHeader("Permissions-Policy", permissionsPolicy);
    }
    if (crossOriginOpenerPolicy != null && res.getHeader("Cross-Origin-Opener-Policy") == null) {
      res.setHeader("Cross-Origin-Opener-Policy", crossOriginOpenerPolicy);
    }
    if (crossOriginEmbedderPolicy != null && res.getHeader("Cross-Origin-Embedder-Policy") == null) {
      res.setHeader("Cross-Origin-Embedder-Policy", crossOriginEmbedderPolicy);
    }
    if (crossOriginResourcePolicy != null && res.getHeader("Cross-Origin-Resource-Policy") == null) {
      res.setHeader("Cross-Origin-Resource-Policy", crossOriginResourcePolicy);
    }
    chain.next(req, res);
  }

  /**
   * @param value The Permissions-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Permissions-Policy set to {@code value}.
   */
  public SecurityHeaders permissionsPolicy(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, value, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The Referrer-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Referrer-Policy set to {@code value}.
   */
  public SecurityHeaders referrerPolicy(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, value, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The Strict-Transport-Security header value, or {@code null} to clear the header.
   * @return A new instance with the Strict-Transport-Security set to {@code value}.
   */
  public SecurityHeaders strictTransportSecurity(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, value,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The X-Content-Type-Options header value, or {@code null} to clear the header.
   * @return A new instance with the X-Content-Type-Options set to {@code value}.
   */
  public SecurityHeaders xContentTypeOptions(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        value, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The X-Frame-Options header value, or {@code null} to clear the header.
   * @return A new instance with the X-Frame-Options set to {@code value}.
   */
  public SecurityHeaders xFrameOptions(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, value, xXSSProtection);
  }

  /**
   * @param value The X-XSS-Protection header value, or {@code null} to clear the header.
   * @return A new instance with the X-XSS-Protection set to {@code value}.
   */
  public SecurityHeaders xXSSProtection(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, value);
  }
}
```

- [ ] **Step 2: Do NOT build yet**

The module will not compile until Task 2 updates the test file (it still references `SecurityHeaders.builder()` and `new SecurityHeaders()`). Proceed directly to Task 2; the green checkpoint is at the end of Task 2.

---

### Task 2: Migrate `SecurityHeadersTest` to the new API and add coverage

**Files:**
- Modify: `src/test/java/org/lattejava/web/tests/middleware/SecurityHeadersTest.java`

- [ ] **Step 1: Rename and rewrite the four `builder_*` tests**

Replace the method `builder_acceptsCSPOverload` (currently lines ~21-40) with:

```java
  @Test
  public void cspOverload_addsHost() throws Exception {
    try (var web = new Web()) {
      web.install(SecurityHeaders.defaults()
                                 .contentSecurityPolicy(CSP.defaults()
                                                           .addStyleSrc("https://cdn.example.com")));
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      String csp = response.headers().firstValue("Content-Security-Policy").orElse(null);
      assertNotNull(csp, "Content-Security-Policy should be set");
      assertTrue(csp.contains("style-src 'self' https://fonts.googleapis.com https://cdn.example.com"),
          "CSP should include the added style-src host: [" + csp + "]");
      // Localhost UIR strip still applies on top of the CSP overload
      assertFalse(csp.contains("upgrade-insecure-requests"),
          "CSP should not contain upgrade-insecure-requests for localhost: [" + csp + "]");
    }
  }
```

Replace `builder_cspOverloadNullSuppresses` with:

```java
  @Test
  public void cspOverloadNullSuppresses() throws Exception {
    try (var web = new Web()) {
      web.install(SecurityHeaders.defaults()
                                 .contentSecurityPolicy((CSP) null));
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      assertFalse(response.headers().firstValue("Content-Security-Policy").isPresent(),
          "Content-Security-Policy should be suppressed");
    }
  }
```

Replace `builder_nullSuppressesHeader` with:

```java
  @Test
  public void nullSuppressesHeader() throws Exception {
    try (var web = new Web()) {
      web.install(SecurityHeaders.defaults()
                                 .strictTransportSecurity(null));
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      assertFalse(response.headers().firstValue("Strict-Transport-Security").isPresent(),
          "Strict-Transport-Security should be suppressed");
      // Other headers still present
      assertHeader(response, "X-Frame-Options", "DENY");
    }
  }
```

Replace `builder_overridesHeaderValue` with:

```java
  @Test
  public void overridesHeaderValue() throws Exception {
    try (var web = new Web()) {
      web.install(SecurityHeaders.defaults()
                                 .contentSecurityPolicy("default-src 'self'; script-src 'self' 'nonce-xyz'"));
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      assertHeader(response, "Content-Security-Policy", "default-src 'self'; script-src 'self' 'nonce-xyz'");
      // Other headers still at defaults
      assertHeader(response, "X-Frame-Options", "DENY");
    }
  }
```

- [ ] **Step 2: Replace every `new SecurityHeaders()` with `SecurityHeaders.defaults()`**

In the remaining tests (`csp_stripsUpgradeInsecureRequestsForLocalhost`, `csp_stripsUpgradeInsecureRequestsForLoopbackIP`, `defaults_emitsAllHeadersWithExpectedValues`, `handlerCanOverrideHeader`, `headersPresentOn404`, `upstreamMiddlewareHeaderIsKept`), change each occurrence of:

```java
      web.install(new SecurityHeaders());
```

to:

```java
      web.install(SecurityHeaders.defaults());
```

(There are six occurrences. Leave everything else in those tests unchanged.)

- [ ] **Step 3: Add the `empty()` test**

Add this method to the class:

```java
  @Test
  public void empty_emitsOnlyExplicitlySetHeader() throws Exception {
    try (var web = new Web()) {
      web.install(SecurityHeaders.empty().xFrameOptions("SAMEORIGIN"));
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      assertHeader(response, "X-Frame-Options", "SAMEORIGIN");
      assertFalse(response.headers().firstValue("Content-Security-Policy").isPresent(),
          "Content-Security-Policy should not be emitted by empty()");
      assertFalse(response.headers().firstValue("Strict-Transport-Security").isPresent(),
          "Strict-Transport-Security should not be emitted by empty()");
      assertFalse(response.headers().firstValue("Referrer-Policy").isPresent(),
          "Referrer-Policy should not be emitted by empty()");
    }
  }
```

- [ ] **Step 4: Add the copy-on-write immutability test**

Add this method to the class:

```java
  @Test
  public void setterDoesNotMutateReceiver() throws Exception {
    SecurityHeaders base = SecurityHeaders.defaults();
    SecurityHeaders derived = base.xFrameOptions("SAMEORIGIN");
    assertNotSame(base, derived, "Setter must return a new instance");

    try (var web = new Web()) {
      web.install(base);
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      assertHeader(response, "X-Frame-Options", "DENY");
    }
  }
```

- [ ] **Step 5: Build and run the full test suite**

Run: `latte build`
Expected: BUILD SUCCESS (the module now compiles — old API references are gone).

Run: `latte test`
Expected: all tests pass, including `SecurityHeadersTest` (12 methods: the 4 renamed, the 6 migrated, plus `empty_emitsOnlyExplicitlySetHeader` and `setterDoesNotMutateReceiver`) and `CSPTest`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/web/middleware/SecurityHeaders.java src/test/java/org/lattejava/web/tests/middleware/SecurityHeadersTest.java
git commit -m "Make SecurityHeaders an immutable CSP-style API

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Update the prior design doc's API examples

**Files:**
- Modify: `docs/design/2026-04-21-security-headers.md`

- [ ] **Step 1: Update the `## API` section**

Replace the fenced `java` block under `## API` (the one containing `new SecurityHeaders()` and `SecurityHeaders.builder()`) with:

````markdown
```java
// All secure defaults
web.install(SecurityHeaders.defaults());

// Override a header
web.install(SecurityHeaders.defaults()
    .contentSecurityPolicy("default-src 'self'; script-src 'self' 'nonce-abc123'"));

// Suppress a header (pass null)
web.install(SecurityHeaders.defaults()
    .strictTransportSecurity(null));

// Only one header for a prefix/handler
web.install(SecurityHeaders.empty()
    .xFrameOptions("SAMEORIGIN"));
```
````

- [ ] **Step 2: Update the `## Class shape` section**

Replace the bullet list under `## Class shape` with:

```markdown
One `final String` field per header. `SecurityHeaders` has:

- Private all-args constructor.
- Static `defaults()` factory (all headers at secure defaults) and `empty()` factory (no headers).
- One copy-on-write method per header returning a new immutable instance; `null` clears that header.

Superseded by `docs/design/2026-05-18-security-headers-immutable.md`.
```

- [ ] **Step 3: Commit**

```bash
git add docs/design/2026-04-21-security-headers.md
git commit -m "Update 2026-04-21 security-headers doc for immutable API

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Final verification

- [ ] **Step 1: Clean build + full test run**

Run: `latte clean && latte build && latte test`
Expected: BUILD SUCCESS; all tests pass with zero failures/skips.

- [ ] **Step 2: Confirm no stale references remain**

Run: `grep -rn "SecurityHeaders.builder\|new SecurityHeaders()" src docs`
Expected: no output (no remaining references to the removed API).

---

## Self-Review

**Spec coverage:**
- Class shape (10 final fields, private all-args ctor, no Builder/no-arg ctor) → Task 1.
- `empty()` / `defaults()` factories → Task 1.
- Copy-on-write setters incl. both `contentSecurityPolicy` overloads, `null` clears → Task 1.
- Thread-safety via final fields → Task 1 (structural; documented in class Javadoc).
- `handle()` unchanged incl. localhost UIR strip → Task 1 (body copied verbatim).
- Test migration + `builder_*` renames + `empty()` test + immutability test + retained coverage → Task 2.
- Class Javadoc updated → Task 1 (Javadoc rewritten in the file).
- `docs/design/2026-04-21-security-headers.md` examples updated → Task 3.
- In-flight `CSP.java` working-tree edits untouched → no task touches `CSP.java`.

**Placeholder scan:** none — every code/edit step contains complete content.

**Type consistency:** factory names (`defaults`, `empty`), setter names, and the 10-arg constructor parameter order are identical across Tasks 1–3 and match the spec.
