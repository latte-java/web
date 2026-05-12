# CSP Builder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a fluent `CSP` class that produces `Content-Security-Policy` header values, plug it into `SecurityHeaders` via a new `contentSecurityPolicy(CSP)` overload, and make `CSP.defaults()` produce the exact default string `SecurityHeaders` already emits.

**Architecture:** A standalone, immutable-after-`build()` builder under `org.lattejava.web.middleware.CSP`. Internally one `LinkedHashMap<String, List<String>>` keyed by lowercase directive name; insertion order is output order. Typed methods for the common directives are thin wrappers over a generic `directive`/`addDirective`/`removeDirective` API. Validation is structural and eager (at the setter), not deferred to `build()`. The localhost `upgrade-insecure-requests` stripping in `SecurityHeaders` is untouched.

**Tech Stack:** Java 25 (module system), Latte build tool, TestNG.

**Spec:** `docs/design/2026-05-11-csp-builder.md`

---

## File structure

- Create: `src/main/java/org/lattejava/web/middleware/CSP.java` — the new class.
- Modify: `src/main/java/org/lattejava/web/middleware/SecurityHeaders.java` — add `contentSecurityPolicy(CSP)` overload, refactor the default field initializer to `CSP.defaults().build()`.
- Create: `src/test/java/org/lattejava/web/tests/middleware/CSPTest.java` — TestNG unit tests for `CSP`.
- Modify: `src/test/java/org/lattejava/web/tests/middleware/SecurityHeadersTest.java` — add an integration test for the `CSP` overload, leave existing tests untouched.

`CSP` is exported via the existing `exports org.lattejava.web.middleware;` clause in `module-info.java`. The test package is already `opens`-d to TestNG. No `module-info.java` changes required.

---

## Conventions (apply to every Java file written below)

- SPDX header at the very top, single-year `2026`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
```

- Acronyms uppercase: the class is `CSP` (not `Csp`).
- Error messages wrap runtime values in `[brackets]`, e.g. `"CSP directive name is invalid: [" + name + "]"`. Never single or double quotes around interpolated values.
- Imports prefer module imports: `import module java.base;`, `import module org.lattejava.http;`, etc. Blank line between import groups.
- Inside a class, order is: static fields → instance fields → constructors → static methods → instance methods → nested classes. Alphabetize within each group except where order has semantics (none here).
- No blank lines between fields.
- Javadoc uses American English sentence structure where written, but write Javadoc only on public types and public methods; do not write per-step running commentary.
- No comments inside method bodies unless a `WHY` is non-obvious.

---

## Build commands (use these throughout)

- Build + test: `latte test`
- Run a single test class: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`
- Build only: `latte build`
- Use `--debug` if a Latte invocation fails opaquely.

---

## Task 1: Skeleton — `CSP.empty()` and `build()` returning `""`

**Files:**
- Create: `src/main/java/org/lattejava/web/middleware/CSP.java`
- Create: `src/test/java/org/lattejava/web/tests/middleware/CSPTest.java`

- [ ] **Step 1: Write the failing test**

`src/test/java/org/lattejava/web/tests/middleware/CSPTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.middleware;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

public class CSPTest {
  @Test
  public void empty_buildsEmptyString() {
    assertEquals(CSP.empty().build(), "");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: compile error — `CSP` does not exist.

- [ ] **Step 3: Write minimal implementation**

`src/main/java/org/lattejava/web/middleware/CSP.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.middleware;

import module java.base;

/**
 * A fluent builder for {@code Content-Security-Policy} header values. Pair with
 * {@link SecurityHeaders.Builder#contentSecurityPolicy(CSP)} to plug the result into the security-headers middleware.
 *
 * @author Brian Pontarelli
 */
public class CSP {
  private final LinkedHashMap<String, List<String>> directives = new LinkedHashMap<>();

  private CSP() {
  }

  /**
   * @return A new empty CSP builder. Building it returns the empty string.
   */
  public static CSP empty() {
    return new CSP();
  }

  /**
   * @return The CSP header value rendered from the current directives, in insertion order. Returns the empty string
   *         when no directives are present.
   */
  public String build() {
    if (directives.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, List<String>> e : directives.entrySet()) {
      if (!first) {
        sb.append("; ");
      }
      sb.append(e.getKey());
      for (String v : e.getValue()) {
        sb.append(' ').append(v);
      }
      first = false;
    }
    return sb.toString();
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: 1 test passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/middleware/CSP.java \
        src/test/java/org/lattejava/web/tests/middleware/CSPTest.java
git commit -m "CSP skeleton: empty() factory and build()"
```

---

## Task 2: Generic `directive(name, values...)` — replace + rendering

**Files:**
- Modify: `src/main/java/org/lattejava/web/middleware/CSP.java`
- Modify: `src/test/java/org/lattejava/web/tests/middleware/CSPTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CSPTest`:

```java
@Test
public void directive_setsSingleSourceListDirective() {
  assertEquals(CSP.empty().directive("script-src", "'self'", "https://x").build(),
      "script-src 'self' https://x");
}

@Test
public void directive_multipleDirectivesJoinedWithSemicolonSpace() {
  String out = CSP.empty()
                  .directive("default-src", "'self'")
                  .directive("img-src", "'self'", "data:")
                  .build();
  assertEquals(out, "default-src 'self'; img-src 'self' data:");
}

@Test
public void directive_noValuesRendersFlagDirective() {
  assertEquals(CSP.empty().directive("upgrade-insecure-requests").build(),
      "upgrade-insecure-requests");
}

@Test
public void directive_replaceKeepsInsertionSlot() {
  String out = CSP.empty()
                  .directive("default-src", "'self'")
                  .directive("img-src", "'self'")
                  .directive("default-src", "'none'")
                  .build();
  assertEquals(out, "default-src 'none'; img-src 'self'");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: compile error — `directive` method does not exist.

- [ ] **Step 3: Add the implementation**

Add to `CSP` (alphabetized into the instance-method block):

```java
/**
 * Replaces the named directive with the given source values. If {@code values} is empty, the directive becomes a flag
 * directive (rendered as just its name, e.g. {@code upgrade-insecure-requests}).
 *
 * @param name   The directive name (e.g. {@code script-src}).
 * @param values The source values to set (e.g. {@code 'self'}, {@code https://example.com}). Pass none for a flag
 *               directive.
 * @return This builder.
 */
public CSP directive(String name, String... values) {
  List<String> list = new ArrayList<>(values.length);
  for (String v : values) {
    list.add(v);
  }
  directives.put(name, list);
  return this;
}
```

Note: `LinkedHashMap.put` on an existing key preserves the key's insertion slot — that's how `directive_replaceKeepsInsertionSlot` passes.

- [ ] **Step 4: Run tests to verify they pass**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/middleware/CSP.java \
        src/test/java/org/lattejava/web/tests/middleware/CSPTest.java
git commit -m "CSP: generic directive(name, values) replace + rendering"
```

---

## Task 3: Source-keyword constants and nonce/hash helpers

**Files:**
- Modify: `src/main/java/org/lattejava/web/middleware/CSP.java`
- Modify: `src/test/java/org/lattejava/web/tests/middleware/CSPTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CSPTest`:

```java
@Test
public void constants_areQuoted() {
  assertEquals(CSP.NONE, "'none'");
  assertEquals(CSP.SELF, "'self'");
  assertEquals(CSP.STRICT_DYNAMIC, "'strict-dynamic'");
  assertEquals(CSP.UNSAFE_EVAL, "'unsafe-eval'");
  assertEquals(CSP.UNSAFE_HASHES, "'unsafe-hashes'");
  assertEquals(CSP.UNSAFE_INLINE, "'unsafe-inline'");
  assertEquals(CSP.WASM_UNSAFE_EVAL, "'wasm-unsafe-eval'");
  assertEquals(CSP.REPORT_SAMPLE, "'report-sample'");
  assertEquals(CSP.INLINE_SPECULATION_RULES, "'inline-speculation-rules'");
}

@Test
public void nonce_wrapsInQuotesAndPrefix() {
  assertEquals(CSP.nonce("abc123"), "'nonce-abc123'");
}

@Test
public void sha256_wrapsInQuotesAndPrefix() {
  assertEquals(CSP.sha256("aZkLp="), "'sha256-aZkLp='");
}

@Test
public void sha384_wrapsInQuotesAndPrefix() {
  assertEquals(CSP.sha384("abc="), "'sha384-abc='");
}

@Test
public void sha512_wrapsInQuotesAndPrefix() {
  assertEquals(CSP.sha512("xyz="), "'sha512-xyz='");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void nonce_rejectsNull() {
  CSP.nonce(null);
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void nonce_rejectsEmpty() {
  CSP.nonce("");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void sha256_rejectsNull() {
  CSP.sha256(null);
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void sha256_rejectsEmpty() {
  CSP.sha256("");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: compile errors — none of the constants or helpers exist.

- [ ] **Step 3: Add the implementation**

Insert into `CSP` at the top of the class, in the static-fields block (alphabetized):

```java
public static final String INLINE_SPECULATION_RULES = "'inline-speculation-rules'";
public static final String NONE                     = "'none'";
public static final String REPORT_SAMPLE            = "'report-sample'";
public static final String SELF                     = "'self'";
public static final String STRICT_DYNAMIC           = "'strict-dynamic'";
public static final String UNSAFE_EVAL              = "'unsafe-eval'";
public static final String UNSAFE_HASHES            = "'unsafe-hashes'";
public static final String UNSAFE_INLINE            = "'unsafe-inline'";
public static final String WASM_UNSAFE_EVAL         = "'wasm-unsafe-eval'";
```

Add to the static-methods block of `CSP` (alphabetized; `empty` and `defaults` will join later):

```java
/**
 * @param value The nonce value (the random per-response token). Must be non-null and non-empty.
 * @return The source expression for the nonce, e.g. {@code 'nonce-abc123'}.
 */
public static String nonce(String value) {
  requireNonEmpty(value, "nonce value");
  return "'nonce-" + value + "'";
}

/**
 * @param base64Digest The base64-encoded SHA-256 digest of the inline block's bytes.
 * @return The source expression for the hash, e.g. {@code 'sha256-aZkLp='}.
 */
public static String sha256(String base64Digest) {
  requireNonEmpty(base64Digest, "sha256 digest");
  return "'sha256-" + base64Digest + "'";
}

public static String sha384(String base64Digest) {
  requireNonEmpty(base64Digest, "sha384 digest");
  return "'sha384-" + base64Digest + "'";
}

public static String sha512(String base64Digest) {
  requireNonEmpty(base64Digest, "sha512 digest");
  return "'sha512-" + base64Digest + "'";
}

private static void requireNonEmpty(String value, String label) {
  if (value == null || value.isEmpty()) {
    throw new IllegalArgumentException("CSP " + label + " must be non-null and non-empty: [" + value + "]");
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: 14 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/middleware/CSP.java \
        src/test/java/org/lattejava/web/tests/middleware/CSPTest.java
git commit -m "CSP: source-keyword constants and nonce/sha helpers"
```

---

## Task 4: `addDirective`, `removeDirective`, `remove`

**Files:**
- Modify: `src/main/java/org/lattejava/web/middleware/CSP.java`
- Modify: `src/test/java/org/lattejava/web/tests/middleware/CSPTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CSPTest`:

```java
@Test
public void addDirective_createsDirectiveIfAbsent() {
  assertEquals(CSP.empty().addDirective("script-src", "'self'").build(), "script-src 'self'");
}

@Test
public void addDirective_appendsToExisting() {
  String out = CSP.empty()
                  .directive("style-src", "'self'")
                  .addDirective("style-src", "https://cdn.example.com")
                  .build();
  assertEquals(out, "style-src 'self' https://cdn.example.com");
}

@Test
public void addDirective_isIdempotent() {
  String out = CSP.empty()
                  .directive("style-src", "'self'")
                  .addDirective("style-src", "'self'")
                  .addDirective("style-src", "https://x", "https://x")
                  .build();
  assertEquals(out, "style-src 'self' https://x");
}

@Test
public void addDirective_promotesFlagToSourceList() {
  String out = CSP.empty()
                  .directive("script-src")
                  .addDirective("script-src", "'self'")
                  .build();
  assertEquals(out, "script-src 'self'");
}

@Test
public void removeDirective_removesOnlyNamedValues() {
  String out = CSP.empty()
                  .directive("style-src", "'self'", "https://a", "https://b")
                  .removeDirective("style-src", "https://a")
                  .build();
  assertEquals(out, "style-src 'self' https://b");
}

@Test
public void removeDirective_emptyingDropsDirective() {
  String out = CSP.empty()
                  .directive("default-src", "'self'")
                  .directive("script-src", "'self'")
                  .removeDirective("script-src", "'self'")
                  .build();
  assertEquals(out, "default-src 'self'");
}

@Test
public void removeDirective_immuneToFlagDirectives() {
  String out = CSP.empty()
                  .directive("upgrade-insecure-requests")
                  .removeDirective("upgrade-insecure-requests", "anything")
                  .build();
  assertEquals(out, "upgrade-insecure-requests");
}

@Test
public void removeDirective_noOpIfDirectiveAbsent() {
  String out = CSP.empty()
                  .directive("default-src", "'self'")
                  .removeDirective("script-src", "'self'")
                  .build();
  assertEquals(out, "default-src 'self'");
}

@Test
public void remove_dropsEntireDirective() {
  String out = CSP.empty()
                  .directive("default-src", "'self'")
                  .directive("upgrade-insecure-requests")
                  .remove("upgrade-insecure-requests")
                  .build();
  assertEquals(out, "default-src 'self'");
}

@Test
public void remove_noOpIfAbsent() {
  String out = CSP.empty()
                  .directive("default-src", "'self'")
                  .remove("script-src")
                  .build();
  assertEquals(out, "default-src 'self'");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: compile errors — `addDirective`, `removeDirective`, `remove` do not exist.

- [ ] **Step 3: Add the implementation**

Add to the instance-method block of `CSP` (alphabetized):

```java
/**
 * Appends source values to the named directive. Creates the directive if absent. Values already present in the
 * directive are not added again (case-sensitive).
 *
 * @param name   The directive name.
 * @param values The source values to append.
 * @return This builder.
 */
public CSP addDirective(String name, String... values) {
  List<String> list = directives.computeIfAbsent(name, _ -> new ArrayList<>());
  for (String v : values) {
    if (!list.contains(v)) {
      list.add(v);
    }
  }
  return this;
}

/**
 * Removes the named directive from the policy. No-op if the directive is not present.
 *
 * @param name The directive name.
 * @return This builder.
 */
public CSP remove(String name) {
  directives.remove(name);
  return this;
}

/**
 * Removes the given source values from the named directive. No-op if the directive is absent or any of the values
 * are absent from it. If the directive was non-empty before this call and is empty after, it is dropped from the
 * policy (empty source-list directives are meaningless). Flag directives (empty value lists) are immune to this
 * method; use {@link #remove(String)} to drop them.
 *
 * @param name   The directive name.
 * @param values The source values to remove.
 * @return This builder.
 */
public CSP removeDirective(String name, String... values) {
  List<String> list = directives.get(name);
  if (list == null || list.isEmpty()) {
    return this;
  }
  for (String v : values) {
    list.remove(v);
  }
  if (list.isEmpty()) {
    directives.remove(name);
  }
  return this;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: all CSP tests passed (24 total at this point).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/middleware/CSP.java \
        src/test/java/org/lattejava/web/tests/middleware/CSPTest.java
git commit -m "CSP: addDirective, removeDirective, remove"
```

---

## Task 5: Validation in the generic API

**Files:**
- Modify: `src/main/java/org/lattejava/web/middleware/CSP.java`
- Modify: `src/test/java/org/lattejava/web/tests/middleware/CSPTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CSPTest`:

```java
@Test(expectedExceptions = IllegalArgumentException.class,
      expectedExceptionsMessageRegExp = ".*\\[Script-Src\\].*")
public void validation_rejectsUppercaseDirectiveName() {
  CSP.empty().directive("Script-Src", "'self'");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void validation_rejectsEmptyDirectiveName() {
  CSP.empty().directive("", "'self'");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void validation_rejectsLeadingHyphenDirectiveName() {
  CSP.empty().directive("-foo", "'self'");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void validation_rejectsSemicolonInDirectiveName() {
  CSP.empty().directive("a;b", "'self'");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void validation_rejectsWhitespaceInDirectiveName() {
  CSP.empty().directive("a b", "'self'");
}

@Test(expectedExceptions = IllegalArgumentException.class,
      expectedExceptionsMessageRegExp = ".*\\[a;b\\].*")
public void validation_rejectsSemicolonInValue() {
  CSP.empty().directive("script-src", "a;b");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void validation_rejectsWhitespaceInValue() {
  CSP.empty().directive("script-src", "a b");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void validation_rejectsTabInValue() {
  CSP.empty().directive("script-src", "a\tb");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void validation_rejectsEmptyValue() {
  CSP.empty().directive("script-src", "");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void validation_addDirectiveValidates() {
  CSP.empty().addDirective("script-src", "a b");
}

@Test(expectedExceptions = IllegalArgumentException.class)
public void validation_removeDirectiveValidatesValues() {
  CSP.empty().directive("script-src", "'self'").removeDirective("script-src", "a b");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: most fail (no exception thrown) or pass with the wrong exception class.

- [ ] **Step 3: Add the implementation**

Add to `CSP` (private static helpers, in the static-methods block, alphabetized after the public helpers):

```java
private static final Pattern DIRECTIVE_NAME = Pattern.compile("[a-z][a-z0-9-]*");

private static void validateDirectiveName(String name) {
  if (name == null || !DIRECTIVE_NAME.matcher(name).matches()) {
    throw new IllegalArgumentException("CSP directive name is invalid: [" + name + "]");
  }
}

private static void validateValue(String value) {
  if (value == null || value.isEmpty()) {
    throw new IllegalArgumentException("CSP source value must be non-empty: [" + value + "]");
  }
  for (int i = 0; i < value.length(); i++) {
    char c = value.charAt(i);
    if (c == ';' || Character.isWhitespace(c)) {
      throw new IllegalArgumentException("CSP source value must not contain [;] or whitespace: [" + value + "]");
    }
  }
}
```

Add the import for `Pattern` via the existing `import module java.base;` — `java.util.regex.Pattern` is in `java.base`, so no additional import needed.

Wire validation into the three generic methods. Replace the bodies you wrote earlier:

```java
public CSP addDirective(String name, String... values) {
  validateDirectiveName(name);
  for (String v : values) {
    validateValue(v);
  }
  List<String> list = directives.computeIfAbsent(name, _ -> new ArrayList<>());
  for (String v : values) {
    if (!list.contains(v)) {
      list.add(v);
    }
  }
  return this;
}

public CSP directive(String name, String... values) {
  validateDirectiveName(name);
  for (String v : values) {
    validateValue(v);
  }
  List<String> list = new ArrayList<>(values.length);
  for (String v : values) {
    list.add(v);
  }
  directives.put(name, list);
  return this;
}

public CSP removeDirective(String name, String... values) {
  validateDirectiveName(name);
  for (String v : values) {
    validateValue(v);
  }
  List<String> list = directives.get(name);
  if (list == null || list.isEmpty()) {
    return this;
  }
  for (String v : values) {
    list.remove(v);
  }
  if (list.isEmpty()) {
    directives.remove(name);
  }
  return this;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: all CSP tests pass (35 total).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/middleware/CSP.java \
        src/test/java/org/lattejava/web/tests/middleware/CSPTest.java
git commit -m "CSP: structural validation on directive names and source values"
```

---

## Task 6: Typed source-list methods (15 directives × replace/add/remove)

The 15 source-list directives, alphabetized by directive name, with the camelCase method name in parentheses:

- `base-uri` (`baseUri`)
- `child-src` (`childSrc`)
- `connect-src` (`connectSrc`)
- `default-src` (`defaultSrc`)
- `font-src` (`fontSrc`)
- `form-action` (`formAction`)
- `frame-ancestors` (`frameAncestors`)
- `frame-src` (`frameSrc`)
- `img-src` (`imgSrc`)
- `manifest-src` (`manifestSrc`)
- `media-src` (`mediaSrc`)
- `object-src` (`objectSrc`)
- `script-src` (`scriptSrc`)
- `style-src` (`styleSrc`)
- `worker-src` (`workerSrc`)

**Files:**
- Modify: `src/main/java/org/lattejava/web/middleware/CSP.java`
- Modify: `src/test/java/org/lattejava/web/tests/middleware/CSPTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CSPTest`. The aim is to verify each typed method maps to the expected directive name, plus representative coverage of the replace/add/remove ops on a few different directives so a bug in one wrapper doesn't hide:

```java
@Test
public void typedReplace_mapsToDirectiveName() {
  assertEquals(CSP.empty().baseUri(CSP.SELF).build(),         "base-uri 'self'");
  assertEquals(CSP.empty().childSrc(CSP.SELF).build(),        "child-src 'self'");
  assertEquals(CSP.empty().connectSrc(CSP.SELF).build(),      "connect-src 'self'");
  assertEquals(CSP.empty().defaultSrc(CSP.SELF).build(),      "default-src 'self'");
  assertEquals(CSP.empty().fontSrc(CSP.SELF).build(),         "font-src 'self'");
  assertEquals(CSP.empty().formAction(CSP.SELF).build(),      "form-action 'self'");
  assertEquals(CSP.empty().frameAncestors(CSP.NONE).build(),  "frame-ancestors 'none'");
  assertEquals(CSP.empty().frameSrc(CSP.SELF).build(),        "frame-src 'self'");
  assertEquals(CSP.empty().imgSrc(CSP.SELF).build(),          "img-src 'self'");
  assertEquals(CSP.empty().manifestSrc(CSP.SELF).build(),     "manifest-src 'self'");
  assertEquals(CSP.empty().mediaSrc(CSP.SELF).build(),        "media-src 'self'");
  assertEquals(CSP.empty().objectSrc(CSP.NONE).build(),       "object-src 'none'");
  assertEquals(CSP.empty().scriptSrc(CSP.SELF).build(),       "script-src 'self'");
  assertEquals(CSP.empty().styleSrc(CSP.SELF).build(),        "style-src 'self'");
  assertEquals(CSP.empty().workerSrc(CSP.SELF).build(),       "worker-src 'self'");
}

@Test
public void typedAdd_appendsToDirective() {
  assertEquals(
      CSP.empty().styleSrc(CSP.SELF).addStyleSrc("https://cdn.example.com").build(),
      "style-src 'self' https://cdn.example.com");
  assertEquals(
      CSP.empty().addScriptSrc(CSP.SELF, CSP.nonce("abc")).build(),
      "script-src 'self' 'nonce-abc'");
}

@Test
public void typedRemove_removesFromDirective() {
  assertEquals(
      CSP.empty()
         .styleSrc(CSP.SELF, "https://a", "https://b")
         .removeStyleSrc("https://a")
         .build(),
      "style-src 'self' https://b");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: compile errors — typed methods do not exist.

- [ ] **Step 3: Add the implementation**

Add to the instance-method block of `CSP`. Each typed method is a one-line wrapper. Alphabetized across the whole instance block. For brevity in this plan, the pattern is shown once and then enumerated by directive name — write all 45 methods (15 × replace/add/remove) explicitly in the file:

Pattern (using `script-src` as the example):

```java
public CSP addScriptSrc(String... sources) {
  return addDirective("script-src", sources);
}

public CSP removeScriptSrc(String... sources) {
  return removeDirective("script-src", sources);
}

public CSP scriptSrc(String... sources) {
  return directive("script-src", sources);
}
```

Generate this triple for each of the 15 directives. Final alphabetic order across all instance methods (after this task is done) will look like:

```
addBaseUri, addChildSrc, addConnectSrc, addDefaultSrc, addDirective, addFontSrc,
addFormAction, addFrameAncestors, addFrameSrc, addImgSrc, addManifestSrc,
addMediaSrc, addObjectSrc, addScriptSrc, addStyleSrc, addWorkerSrc,
baseUri, build, childSrc, connectSrc, defaultSrc, directive,
fontSrc, formAction, frameAncestors, frameSrc, imgSrc, manifestSrc,
mediaSrc, objectSrc, remove, removeBaseUri, removeChildSrc, removeConnectSrc,
removeDefaultSrc, removeDirective, removeFontSrc, removeFormAction,
removeFrameAncestors, removeFrameSrc, removeImgSrc, removeManifestSrc,
removeMediaSrc, removeObjectSrc, removeScriptSrc, removeStyleSrc,
removeWorkerSrc, scriptSrc, styleSrc, workerSrc
```

Add Javadoc only on `directive`, `addDirective`, `removeDirective`, `remove`, and `build` (the underlying primitives). Typed wrappers can be undocumented — their name and signature are self-explanatory.

- [ ] **Step 4: Run tests to verify they pass**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: all CSP tests pass (38 total).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/middleware/CSP.java \
        src/test/java/org/lattejava/web/tests/middleware/CSPTest.java
git commit -m "CSP: typed source-list directive methods (replace, add, remove)"
```

---

## Task 7: Typed non-source-list methods

**Files:**
- Modify: `src/main/java/org/lattejava/web/middleware/CSP.java`
- Modify: `src/test/java/org/lattejava/web/tests/middleware/CSPTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `CSPTest`:

```java
@Test
public void upgradeInsecureRequests_enables() {
  assertEquals(CSP.empty().upgradeInsecureRequests().build(), "upgrade-insecure-requests");
}

@Test
public void upgradeInsecureRequests_trueAddsFalseRemoves() {
  CSP csp = CSP.empty().upgradeInsecureRequests(true);
  assertEquals(csp.build(), "upgrade-insecure-requests");
  csp.upgradeInsecureRequests(false);
  assertEquals(csp.build(), "");
}

@Test
public void sandbox_noArgEnablesEmptyTokenList() {
  assertEquals(CSP.empty().sandbox().build(), "sandbox");
}

@Test
public void sandbox_replaceAddRemove() {
  String out = CSP.empty()
                  .sandbox("allow-forms", "allow-scripts")
                  .addSandbox("allow-same-origin")
                  .removeSandbox("allow-forms")
                  .build();
  assertEquals(out, "sandbox allow-scripts allow-same-origin");
}

@Test
public void reportUri_replaceWithMultipleURIs() {
  assertEquals(
      CSP.empty().reportUri("https://a.example/r", "https://b.example/r").build(),
      "report-uri https://a.example/r https://b.example/r");
}

@Test
public void reportTo_singleGroupName() {
  assertEquals(CSP.empty().reportTo("csp-endpoint").build(), "report-to csp-endpoint");
}

@Test
public void requireTrustedTypesFor_emitsTokens() {
  assertEquals(
      CSP.empty().requireTrustedTypesFor("'script'").build(),
      "require-trusted-types-for 'script'");
}

@Test
public void trustedTypes_emitsPolicyNames() {
  assertEquals(
      CSP.empty().trustedTypes("default", "myPolicy").build(),
      "trusted-types default myPolicy");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: compile errors — methods do not exist.

- [ ] **Step 3: Add the implementation**

Add to the instance-method block of `CSP`, alphabetized (these new names land near `r`/`s`/`t`/`u`):

```java
public CSP addSandbox(String... tokens) {
  return addDirective("sandbox", tokens);
}

public CSP removeSandbox(String... tokens) {
  return removeDirective("sandbox", tokens);
}

public CSP reportTo(String groupName) {
  return directive("report-to", groupName);
}

public CSP reportUri(String... uris) {
  return directive("report-uri", uris);
}

public CSP requireTrustedTypesFor(String... sinks) {
  return directive("require-trusted-types-for", sinks);
}

public CSP sandbox(String... tokens) {
  return directive("sandbox", tokens);
}

public CSP trustedTypes(String... policies) {
  return directive("trusted-types", policies);
}

public CSP upgradeInsecureRequests() {
  return directive("upgrade-insecure-requests");
}

public CSP upgradeInsecureRequests(boolean on) {
  if (on) {
    return directive("upgrade-insecure-requests");
  }
  return remove("upgrade-insecure-requests");
}
```

Note: `reportTo` validates `groupName` via `directive(..., groupName)` — `validateValue` will reject an empty group name. That matches the desired behavior.

- [ ] **Step 4: Run tests to verify they pass**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: all CSP tests pass (46 total).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/middleware/CSP.java \
        src/test/java/org/lattejava/web/tests/middleware/CSPTest.java
git commit -m "CSP: typed non-source-list directives (UIR, sandbox, report, trusted-types)"
```

---

## Task 8: `CSP.defaults()` matching the current SecurityHeaders default

**Files:**
- Modify: `src/main/java/org/lattejava/web/middleware/CSP.java`
- Modify: `src/test/java/org/lattejava/web/tests/middleware/CSPTest.java`

- [ ] **Step 1: Write the failing test**

Add to `CSPTest`. The expected string is the **exact current literal** from `SecurityHeaders.Builder.contentSecurityPolicy`:

```java
@Test
public void defaults_matchesCurrentSecurityHeadersDefaultString() {
  String expected = "default-src 'self'; style-src 'self' https://fonts.googleapis.com; "
      + "font-src 'self' https://fonts.gstatic.com; object-src 'none'; base-uri 'self'; "
      + "frame-ancestors 'none'; form-action 'self'; upgrade-insecure-requests";
  assertEquals(CSP.defaults().build(), expected);
}

@Test
public void defaults_returnsFreshInstance() {
  CSP a = CSP.defaults();
  CSP b = CSP.defaults();
  a.addStyleSrc("https://x");
  assertEquals(b.build(),
      "default-src 'self'; style-src 'self' https://fonts.googleapis.com; "
          + "font-src 'self' https://fonts.gstatic.com; object-src 'none'; base-uri 'self'; "
          + "frame-ancestors 'none'; form-action 'self'; upgrade-insecure-requests");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: compile error — `defaults` does not exist.

- [ ] **Step 3: Add the implementation**

Add to the static-methods block of `CSP`, alphabetized before `empty`:

```java
/**
 * @return A new CSP builder pre-populated to match the default policy that {@link SecurityHeaders} emits. Each call
 *         returns a fresh instance; mutating it does not affect other callers.
 */
public static CSP defaults() {
  return new CSP()
      .defaultSrc(SELF)
      .styleSrc(SELF, "https://fonts.googleapis.com")
      .fontSrc(SELF, "https://fonts.gstatic.com")
      .objectSrc(NONE)
      .baseUri(SELF)
      .frameAncestors(NONE)
      .formAction(SELF)
      .upgradeInsecureRequests();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `latte test --tests org.lattejava.web.tests.middleware.CSPTest`

Expected: all CSP tests pass (48 total).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/middleware/CSP.java \
        src/test/java/org/lattejava/web/tests/middleware/CSPTest.java
git commit -m "CSP: defaults() factory matching current SecurityHeaders default"
```

---

## Task 9: `SecurityHeaders.Builder.contentSecurityPolicy(CSP)` overload

**Files:**
- Modify: `src/main/java/org/lattejava/web/middleware/SecurityHeaders.java`
- Modify: `src/test/java/org/lattejava/web/tests/middleware/SecurityHeadersTest.java`

- [ ] **Step 1: Write the failing test**

Add to `SecurityHeadersTest`:

```java
@Test
public void builder_acceptsCSPOverload() throws Exception {
  try (var web = new Web()) {
    web.install(SecurityHeaders.builder()
                               .contentSecurityPolicy(CSP.defaults()
                                                         .addStyleSrc("https://cdn.example.com"))
                               .build());
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

@Test
public void builder_cspOverloadNullSuppresses() throws Exception {
  try (var web = new Web()) {
    web.install(SecurityHeaders.builder()
                               .contentSecurityPolicy((CSP) null)
                               .build());
    web.get("/x", (_, res) -> res.setStatus(200));
    web.start(PORT);

    HttpResponse<String> response = send("GET", "/x");
    assertFalse(response.headers().firstValue("Content-Security-Policy").isPresent(),
        "Content-Security-Policy should be suppressed");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --tests org.lattejava.web.tests.middleware.SecurityHeadersTest`

Expected: compile error — `contentSecurityPolicy(CSP)` overload does not exist.

- [ ] **Step 3: Add the implementation**

Modify `src/main/java/org/lattejava/web/middleware/SecurityHeaders.java`.

Replace the hard-coded default literal so it goes through `CSP.defaults()` (preserving the byte-for-byte contract enforced by `CSPTest.defaults_matchesCurrentSecurityHeadersDefaultString`):

```java
// before
private String contentSecurityPolicy = "default-src 'self'; style-src 'self' https://fonts.googleapis.com; " +
    "font-src 'self' https://fonts.gstatic.com; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; " +
    "form-action 'self'; upgrade-insecure-requests";

// after
private String contentSecurityPolicy = CSP.defaults().build();
```

Add a new overload to `Builder`, alphabetized adjacent to the existing `contentSecurityPolicy(String)`:

```java
public Builder contentSecurityPolicy(CSP csp) {
  this.contentSecurityPolicy = csp == null ? null : csp.build();
  return this;
}
```

Update the Javadoc on the `Builder` class if it claims defaults are stored as raw strings — current doc only says "pre-populated with their most-secure defaults", which is still accurate. No Javadoc change needed.

- [ ] **Step 4: Run all middleware tests to verify nothing regressed**

Run: `latte test --tests org.lattejava.web.tests.middleware`

Expected: all middleware tests pass, including the existing `SecurityHeadersTest.defaults_emitsAllHeadersWithExpectedValues`, `csp_stripsUpgradeInsecureRequestsForLocalhost`, `csp_stripsUpgradeInsecureRequestsForLoopbackIP`, and `headersPresentOn404`. The two new tests pass too.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/middleware/SecurityHeaders.java \
        src/test/java/org/lattejava/web/tests/middleware/SecurityHeadersTest.java
git commit -m "SecurityHeaders: accept CSP overload and route default through CSP.defaults()"
```

---

## Task 10: Full-suite verification

- [ ] **Step 1: Run the full test suite**

Run: `latte test`

Expected: all tests pass. Watch for any test that asserts the exact CSP default string (notably `SecurityHeadersTest.defaults_emitsAllHeadersWithExpectedValues` and `SecurityHeadersTest.headersPresentOn404`) — these should still match. If any test fails because of a whitespace difference in the rendered default, the `CSP.defaults()` factory or `build()` rendering needs fixing — do not edit the test to match the new output.

- [ ] **Step 2: Final commit only if anything needed touching up**

Only commit if step 1 required a fix. Otherwise skip.

---

## Self-review (already performed by plan author)

- **Spec coverage:** Every spec section maps to a task. Placement and naming: Task 1. Integration with SecurityHeaders: Task 9. Entry points (`empty`, `defaults`): Tasks 1 and 8. Source-keyword constants: Task 3. Nonce/hash helpers: Task 3. Typed source-list methods: Task 6. Typed non-source-list methods: Task 7. Directive-level remove + generic escape hatch: Tasks 2 and 4. `build()` rendering: Tasks 1 and 2. Validation: Task 5. Tests: Tasks 1-10. Non-goals (no semantic validation, no request awareness in CSP, no thread safety, no nonce generation): respected by design.
- **Placeholder scan:** No TBDs, no "implement later," every step shows the code or command.
- **Type consistency:** Method names referenced across tasks match (`directive`, `addDirective`, `removeDirective`, `remove`, `build`, `upgradeInsecureRequests`, `defaults`, `empty`, the typed `xSrc`/`addXSrc`/`removeXSrc` triples).
- **Spec gap discovered:** None.
