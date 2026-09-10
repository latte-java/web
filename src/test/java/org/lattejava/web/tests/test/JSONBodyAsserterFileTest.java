/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.test;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import java.nio.file.Files;

import org.lattejava.web.test.json.*;

import static org.testng.Assert.*;

/**
 * Tests for {@link JSONBodyAsserter#equalToFile(Path, Object...)}. The expected files are checked in under
 * {@code src/test/projects/json-body-asserter}. Tests that generate or rewrite a file work on copies under
 * {@code build/test-scratch} so the checked-in files are never touched. Every test uses one of the two nested
 * subclasses that pin the CI detection, so the suite never depends on the real {@code CI} environment variable.
 */
public class JSONBodyAsserterFileTest {
  private static final Path PROJECT_DIR = Path.of("src/test/projects/json-body-asserter");
  private static final Path SCRATCH_DIR = Path.of("build/test-scratch/json-body-asserter");

  private static Path fixture(String name) {
    return PROJECT_DIR.resolve(name);
  }

  /**
   * Copies a fixture into the scratch directory so a test can rewrite it.
   */
  private static Path scratchCopy(String name) throws IOException {
    Path copy = SCRATCH_DIR.resolve(name);
    Files.copy(fixture(name), copy, StandardCopyOption.REPLACE_EXISTING);
    return copy;
  }

  @Test
  public void equalToFile_anyBooleanPlaceholder() {
    Path file = fixture("any-boolean.json");

    asserterFor("""
        { "flag": true }
        """).equalToFile(file);
    asserterFor("""
        { "flag": false }
        """).equalToFile(file);

    // The string "true" is not a boolean.
    var asserter = asserterFor("""
        { "flag": "true" }
        """);
    expectThrows(AssertionError.class, () -> asserter.equalToFile(file));
  }

  @Test
  public void equalToFile_anyInstantPlaceholder() {
    Path file = fixture("any-instant.json");

    asserterFor("""
        { "created": "2026-08-14T12:34:56Z" }
        """).equalToFile(file);

    // Not parseable by Instant.parse.
    var asserter = asserterFor("""
        { "created": "2026-08-14 12:34:56" }
        """);
    expectThrows(AssertionError.class, () -> asserter.equalToFile(file));
  }

  @Test
  public void equalToFile_anyInstantRange() {
    // ISO 8601 interval notation: slash separator, since instants contain colons.
    Path file = fixture("any-instant-range.json");

    asserterFor("""
        { "created": "2026-08-15T12:00:00Z" }
        """).equalToFile(file);

    // Bounds are inclusive.
    asserterFor("""
        { "created": "2026-01-01T00:00:00Z" }
        """).equalToFile(file);
    asserterFor("""
        { "created": "2026-12-31T23:59:59Z" }
        """).equalToFile(file);

    var before = asserterFor("""
        { "created": "2025-12-31T23:59:59Z" }
        """);
    expectThrows(AssertionError.class, () -> before.equalToFile(file));

    var after = asserterFor("""
        { "created": "2027-01-01T00:00:00Z" }
        """);
    expectThrows(AssertionError.class, () -> after.equalToFile(file));

    // Open-ended start: anything up to the end instant.
    Path openStart = fixture("any-instant-open-start.json");
    asserterFor("""
        { "created": "1999-01-01T00:00:00Z" }
        """).equalToFile(openStart);

    var late = asserterFor("""
        { "created": "2030-01-01T00:00:00Z" }
        """);
    expectThrows(AssertionError.class, () -> late.equalToFile(openStart));
  }

  @Test
  public void equalToFile_anyNumberPlaceholder() {
    Path file = fixture("any-number.json");

    asserterFor("""
        { "count": 42 }
        """).equalToFile(file);
    asserterFor("""
        { "count": 3.14 }
        """).equalToFile(file);

    // The string "42" is not a JSON number.
    var asserter = asserterFor("""
        { "count": "42" }
        """);
    expectThrows(AssertionError.class, () -> asserter.equalToFile(file));
  }

  @Test
  public void equalToFile_anyNumberRange() {
    Path file = fixture("any-number-range.json");

    asserterFor("""
        { "count": 42 }
        """).equalToFile(file);
    asserterFor("""
        { "count": 99.5 }
        """).equalToFile(file);

    // Bounds are inclusive.
    asserterFor("""
        { "count": -10 }
        """).equalToFile(file);
    asserterFor("""
        { "count": 100 }
        """).equalToFile(file);

    var low = asserterFor("""
        { "count": -10.5 }
        """);
    expectThrows(AssertionError.class, () -> low.equalToFile(file));

    var high = asserterFor("""
        { "count": 101 }
        """);
    expectThrows(AssertionError.class, () -> high.equalToFile(file));

    // A string is not a number, even if its text form is in range.
    var string = asserterFor("""
        { "count": "42" }
        """);
    expectThrows(AssertionError.class, () -> string.equalToFile(file));

    // Open-ended end: any non-negative number.
    Path openEnd = fixture("any-number-open-end.json");
    asserterFor("""
        { "count": 123456 }
        """).equalToFile(openEnd);

    var negative = asserterFor("""
        { "count": -1 }
        """);
    expectThrows(AssertionError.class, () -> negative.equalToFile(openEnd));
  }

  @Test
  public void equalToFile_anyStringPlaceholder() {
    Path file = fixture("any-string.json");

    asserterFor("""
        { "name": "Jane" }
        """).equalToFile(file);

    // A JSON number is not a string.
    var asserter = asserterFor("""
        { "name": 42 }
        """);
    expectThrows(AssertionError.class, () -> asserter.equalToFile(file));
  }

  @Test
  public void equalToFile_anyStringRange() {
    // The range bounds the string's length, inclusive on both ends.
    Path file = fixture("any-string-range.json");

    asserterFor("""
        { "name": "abc" }
        """).equalToFile(file);
    asserterFor("""
        { "name": "ab" }
        """).equalToFile(file);
    asserterFor("""
        { "name": "abcde" }
        """).equalToFile(file);

    var tooShort = asserterFor("""
        { "name": "a" }
        """);
    expectThrows(AssertionError.class, () -> tooShort.equalToFile(file));

    var tooLong = asserterFor("""
        { "name": "abcdef" }
        """);
    expectThrows(AssertionError.class, () -> tooLong.equalToFile(file));

    // A number is not a string, even if its text form's length is in range.
    var number = asserterFor("""
        { "name": 123 }
        """);
    expectThrows(AssertionError.class, () -> number.equalToFile(file));

    // Open-ended start: any string up to 3 characters, including empty.
    Path openStart = fixture("any-string-open-start.json");
    asserterFor("""
        { "name": "" }
        """).equalToFile(openStart);

    var overflow = asserterFor("""
        { "name": "abcd" }
        """);
    expectThrows(AssertionError.class, () -> overflow.equalToFile(openStart));
  }

  @Test
  public void equalToFile_anyUUIDPlaceholder() {
    Path file = fixture("any-uuid.json");

    asserterFor("""
        { "id": "%s" }
        """.formatted(UUID.randomUUID())).equalToFile(file);

    var asserter = asserterFor("""
        { "id": "not-a-uuid" }
        """);
    expectThrows(AssertionError.class, () -> asserter.equalToFile(file));
  }

  @Test
  public void equalToFile_arrayOrderIsAlwaysPositional() {
    Path file = fixture("array-order.json");

    // equalToFile ignores the unorderedArrays setting — order is part of the wire format.
    var asserter = asserterFor("""
        { "tags": ["c", "b", "a"] }
        """).unorderedArrays(true);
    expectThrows(AssertionError.class, () -> asserter.equalToFile(file));

    // The same asserter with the elements in file order matches.
    asserter.body("""
        { "tags": ["a", "b", "c"] }
        """.getBytes(StandardCharsets.UTF_8));
    asserter.equalToFile(file);
  }

  @Test
  public void equalToFile_bootstrapEscapesLiteralDollarBrace() throws IOException {
    Path file = SCRATCH_DIR.resolve("escaped.json");
    var asserter = asserterFor("""
        { "message": "Use ${name} to interpolate" }
        """);

    // This generates the file - escaped.json
    expectThrows(AssertionError.class, () -> asserter.equalToFile(file));

    // The literal ${ in the actual response must be escaped in the generated file so it is not read as a token.
    String content = Files.readString(file);
    assertTrue(content.contains("$${name}"),
        "Generated file [" + content + "] should escape the literal token text as [$${name}]");
    asserter.equalToFile(file);
  }

  @Test
  public void equalToFile_bootstrapOnCIFailsWithoutWriting() {
    Path file = SCRATCH_DIR.resolve("missing.json");
    var asserter = ciAsserterFor("""
        { "name": "Jane" }
        """);

    // This does NOT generate the file - missing.json - since it is a CI run
    expectThrows(AssertionError.class, () -> asserter.equalToFile(file));
    assertTrue(Files.notExists(file), "On CI a missing expected file must never be generated");
  }

  @Test
  public void equalToFile_bootstrapWritesMissingFile() throws IOException {
    // The parent directory does not exist either; bootstrap must create it.
    Path file = SCRATCH_DIR.resolve("golden").resolve("response.json");
    var asserter = asserterFor("""
        { "user": { "name": "Jane", "age": 33 } }
        """);

    AssertionError error = expectThrows(AssertionError.class, () -> asserter.equalToFile(file));
    assertTrue(error.getMessage().contains(file.toString()),
        "Bootstrap message [" + error.getMessage() + "] should contain the file path [" + file + "]");
    assertTrue(Files.exists(file), "Bootstrap should have generated the expected file");

    String content = Files.readString(file);
    assertTrue(content.endsWith("\n"), "Generated file should end with a newline");
    assertTrue(content.contains("\n  \"user\""), "Generated file should be pretty-printed with a 2-space indent");

    // The generated file matches the response it was generated from.
    asserter.equalToFile(file);
  }

  @Test
  public void equalToFile_embeddedSubstitutionInterpolates() {
    asserterFor("""
        { "url": "http://localhost:9012/" }
        """).equalToFile(fixture("embedded-substitution.json"), "port", 9012);
  }

  @Test
  public void equalToFile_escapedDollarBraceMatchesLiteral() {
    // The $${ escape means the actual value must contain the literal text ${name}, not a substitution.
    asserterFor("""
        { "message": "Use ${name} to interpolate" }
        """).equalToFile(fixture("escaped-dollar-brace.json"));
  }

  @Test
  public void equalToFile_exactMatchPasses() {
    asserterFor("""
        {
          "user": {
            "name": "Jane",
            "age": 33,
            "active": true,
            "nickname": null,
            "tags": ["admin", "user"]
          }
        }
        """).equalToFile(fixture("exact-match.json"));
  }

  @Test
  public void equalToFile_extraFieldInActualFails() {
    var asserter = asserterFor("""
        { "name": "Jane", "extra": 1 }
        """);
    expectThrows(AssertionError.class, () -> asserter.equalToFile(fixture("name.json")));
  }

  @Test
  public void equalToFile_failureMessageContainsFilePath() {
    Path file = fixture("name.json");
    var asserter = asserterFor("""
        { "name": "Bob" }
        """);
    expectAssertionError(() -> asserter.equalToFile(file), file.toString());
  }

  @Test
  public void equalToFile_malformedRanges() {
    // Missing brackets.
    expectAssertionError(() -> asserterFor("{}").equalToFile(fixture("malformed/missing-brackets.json")),
        "Malformed range");

    // Both bounds empty.
    expectAssertionError(() -> asserterFor("{}").equalToFile(fixture("malformed/empty-bounds.json")),
        "at least one bound");

    // Minimum greater than maximum.
    expectAssertionError(() -> asserterFor("{}").equalToFile(fixture("malformed/min-exceeds-max.json")),
        "minimum exceeds the maximum");

    // String length bounds must be non-negative integers.
    expectAssertionError(() -> asserterFor("{}").equalToFile(fixture("malformed/negative-string-bound.json")),
        "Invalid range bound");

    // Instant ranges use ISO 8601 interval notation (slash), not a colon separator.
    expectAssertionError(() -> asserterFor("{}").equalToFile(fixture("malformed/instant-colon-separator.json")),
        "Malformed range");

    // Instant bounds must be parseable by Instant.parse.
    expectAssertionError(() -> asserterFor("{}").equalToFile(fixture("malformed/instant-invalid-bound.json")),
        "Invalid range bound");

    // anyBoolean and anyUUID take no arguments.
    expectAssertionError(() -> asserterFor("{}").equalToFile(fixture("malformed/boolean-with-range.json")),
        "Malformed token");

    // Parameterized placeholders must be the entire string node.
    expectAssertionError(() -> asserterFor("{}").equalToFile(fixture("malformed/embedded-parameterized.json")),
        "must be the entire string node");
  }

  @Test
  public void equalToFile_missingFieldInActualFails() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);
    expectThrows(AssertionError.class, () -> asserter.equalToFile(fixture("name-and-email.json")));
  }

  @Test
  public void equalToFile_nonStringSubstitutionNameThrows() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);
    expectThrows(IllegalArgumentException.class, () -> asserter.equalToFile(fixture("name.json"), 42, "value"));
  }

  @Test
  public void equalToFile_objectKeyOrderIgnored() {
    asserterFor("""
        { "a": 1, "b": 2, "c": 3 }
        """).equalToFile(fixture("object-key-order.json"));
  }

  @Test
  public void equalToFile_oddSubstitutionCountThrows() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);
    expectThrows(IllegalArgumentException.class, () -> asserter.equalToFile(fixture("name.json"), "lonely"));
  }

  @Test
  public void equalToFile_placeholderFailsForJSONNull() {
    var asserter = asserterFor("""
        { "id": null }
        """);
    expectThrows(AssertionError.class, () -> asserter.equalToFile(fixture("any-string-id.json")));
  }

  @Test
  public void equalToFile_placeholderFailsForMissingKey() {
    var asserter = asserterFor("{}");
    expectThrows(AssertionError.class, () -> asserter.equalToFile(fixture("any-string-id.json")));
  }

  @Test
  public void equalToFile_regexPlaceholder() {
    // The pattern runs to the trailing } of the node, so brace quantifiers are usable. The file contains the
    // JSON-escaped form \\d, which the parser turns back into \d.
    Path file = fixture("regex.json");

    asserterFor("""
        { "code": "abc-123" }
        """).equalToFile(file);

    var wrong = asserterFor("""
        { "code": "ab-123" }
        """);
    expectThrows(AssertionError.class, () -> wrong.equalToFile(file));

    // The pattern must match the entire text form, not just a prefix.
    var partial = asserterFor("""
        { "code": "abc-1234" }
        """);
    expectThrows(AssertionError.class, () -> partial.equalToFile(file));
  }

  @Test
  public void equalToFile_substitutionReplacesWholeNodeTyped() {
    UUID id = UUID.randomUUID();

    // A string node that is exactly one token takes the JSON type of the supplied value.
    asserterFor("""
        {
          "active": true,
          "count": 42,
          "id": "%s"
        }
        """.formatted(id)).equalToFile(fixture("substitution-typed.json"), "active", true, "count", 42, "id", id);
  }

  @Test
  public void equalToFile_typeMismatchFails() {
    var asserter = asserterFor("""
        { "n": "1" }
        """);
    expectThrows(AssertionError.class, () -> asserter.equalToFile(fixture("type-mismatch.json")));
  }

  @Test
  public void equalToFile_unknownTokenFails() {
    var asserter = asserterFor("""
        { "id": "abc" }
        """);
    expectAssertionError(() -> asserter.equalToFile(fixture("unknown-token.json")), "anyGuid");
  }

  @Test
  public void equalToFile_unusedSubstitutionFails() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);
    expectAssertionError(() -> asserter.equalToFile(fixture("name.json"), "orphan", 1), "orphan");
  }

  @Test
  public void equalToFile_updateModeOnCIFailsWithoutWriting() throws IOException {
    Path file = scratchCopy("update-mode-ci.json");
    String before = Files.readString(file);
    var asserter = ciAsserterFor("""
        { "version": "1.0.0" }
        """);

    System.setProperty("latte.web.json.update", "true");
    try {
      expectThrows(AssertionError.class, () -> asserter.equalToFile(file));
    } finally {
      System.clearProperty("latte.web.json.update");
    }

    assertEquals(Files.readString(file), before, "On CI the update flag must never rewrite the expected file");
  }

  @Test
  public void equalToFile_updateModeRewritesFilePreservingTokens() throws IOException {
    UUID id = UUID.randomUUID();
    Path file = scratchCopy("update-mode.json");
    var asserter = asserterFor("""
        {
          "created": "2026-08-14T12:00:00Z",
          "id": "%s",
          "url": "http://localhost:9012/",
          "version": "1.0.0"
        }
        """.formatted(id));

    System.setProperty("latte.web.json.update", "true");
    try {
      // Mismatch on "version", but update mode rewrites the file and passes.
      asserter.equalToFile(file, "id", id, "port", 9012);
    } finally {
      System.clearProperty("latte.web.json.update");
    }

    String content = Files.readString(file);
    assertTrue(content.contains("${id}"), "Substitution token whose value still matches must be preserved");
    assertTrue(content.contains("${anyInstant}"), "Placeholder that still matches must be preserved");
    assertTrue(content.contains("http://localhost:${port}/"),
        "Template string whose interpolation still matches must be preserved");
    assertTrue(content.contains("1.0.0"), "Stale value must be rewritten from the actual response");
    assertFalse(content.contains("0.9.0"), "Stale value must no longer appear in the rewritten file");
  }

  /**
   * Starts each test with an empty scratch directory so generated files never exist beforehand.
   */
  @BeforeMethod
  public void setUp() throws IOException {
    if (Files.exists(SCRATCH_DIR)) {
      try (var stream = Files.walk(SCRATCH_DIR)) {
        stream.sorted(Comparator.reverseOrder()).forEach(path -> {
          try {
            Files.delete(path);
          } catch (IOException ignored) {
          }
        });
      }
    }
    Files.createDirectories(SCRATCH_DIR);
  }

  private JSONBodyAsserter asserterFor(String json) {
    var asserter = new NonCIAsserter();
    asserter.body(json.getBytes(StandardCharsets.UTF_8));
    return asserter;
  }

  private JSONBodyAsserter ciAsserterFor(String json) {
    var asserter = new CIAsserter();
    asserter.body(json.getBytes(StandardCharsets.UTF_8));
    return asserter;
  }

  private void expectAssertionError(ThrowingRunnable runnable, String expectedMessageFragment) {
    AssertionError error = expectThrows(AssertionError.class, runnable);
    assertNotNull(error.getMessage(), "AssertionError message must not be null");
    assertTrue(
        error.getMessage().contains(expectedMessageFragment),
        "AssertionError message [" + error.getMessage() + "] does not contain expected fragment [" + expectedMessageFragment + "]"
    );
  }

  private static class CIAsserter extends JSONBodyAsserter {
    @Override
    protected boolean ci() {
      return true;
    }
  }

  private static class NonCIAsserter extends JSONBodyAsserter {
    @Override
    protected boolean ci() {
      return false;
    }
  }
}
