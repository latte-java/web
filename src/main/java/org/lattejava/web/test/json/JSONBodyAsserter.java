/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.test.json;

import module java.base;

import org.lattejava.web.test.*;

/**
 * A {@link BodyAsserter} that parses the response body as JSON and offers assertions over the parsed tree. Parsing uses
 * the Latte <code>json</code> library; values are held in their natural Java shapes ({@link Map}, {@link List},
 * {@link String}, {@link Long}, {@link BigInteger}, {@link BigDecimal}, {@link Boolean}, and {@code null}).
 * <p>
 * Element paths are <a href="https://www.rfc-editor.org/rfc/rfc6901">JSON Pointers</a> (RFC 6901): the empty string
 * refers to the root, {@code "/foo"} refers to the {@code foo} property, {@code "/items/0/id"} indexes into an array,
 * and {@code "/"} characters embedded in a property name are escaped as {@code "~1"}.
 * <p>
 * Equality comparisons always treat JSON objects as unordered (per JSON semantics). Arrays are unordered multisets by
 * default ({@code [1, 2, 3]} matches {@code [3, 2, 1]}, but {@code [1, 1, 2]} does not match {@code [1, 2, 2]}); pass
 * {@code false} to {@link #JSONBodyAsserter(boolean)} or call {@link #unorderedArrays(boolean)} to switch to strict
 * positional array comparison. Element types are always compared strictly — {@code 1} (int) does not match {@code 1.0}
 * (float).
 * <p>
 * File-based (golden-file) assertions are available via {@link #equalToFile(Path, Object...)}: the body is compared
 * against a committed JSON file that may contain substitution and placeholder tokens, with bootstrap and update modes
 * for maintaining the files.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class JSONBodyAsserter extends BodyAsserter {
  private boolean parsed;
  private Object root;
  private boolean unorderedArrays;

  public JSONBodyAsserter() {
    this(true);
  }

  public JSONBodyAsserter(boolean unorderedArrays) {
    this.unorderedArrays = unorderedArrays;
  }

  @Override
  public void body(byte[] body) {
    super.body(body);
    this.parsed = false;
    this.root = null;
  }

  /**
   * Asserts that the body is JSON-equivalent to the given expected string. Whitespace and key ordering are ignored.
   *
   * @param expected The expected JSON document.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter equalTo(String expected) {
    Object actual = parseActual();
    Object expectedTree = JSONTools.parse(expected, "expected");
    if (!deepEquals(actual, expectedTree, unorderedArrays)) {
      Assertions.failNotEqual(JSONTools.stringify(actual), JSONTools.stringify(expectedTree), "JSON body does not match");
    }
    return this;
  }

  /**
   * Asserts that the body, parsed as JSON, is equal to the JSON form of the given Java object. The expected object is
   * converted to its JSON shape the same way the Latte <code>json</code> library would serialize it: {@link Map}s
   * become JSON objects, {@link Iterable}s and arrays become JSON arrays, numbers, booleans, strings, and {@code null}
   * map to their JSON scalar forms, enums use their {@code name()}, and {@link UUID}, {@link URI}, {@link URL}, and the
   * ISO-8601 {@code java.time} types use their string forms. Records and POJOs with public fields are converted via
   * reflection; {@code null} components and fields are omitted, mirroring the library's {@code omitNulls} default.
   * Object-key ordering is always ignored; array ordering follows the {@link #unorderedArrays(boolean)} setting.
   * <p>
   * Reflection over a record or POJO in a named module requires its package to be opened (or exported) to
   * {@code org.lattejava.web}.
   *
   * @param expected The expected value.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter equalTo(Object expected) {
    Object actual = parseActual();
    Object expectedTree = JSONTools.toJSONObject(expected);
    if (!deepEquals(actual, expectedTree, unorderedArrays)) {
      Assertions.failNotEqual(JSONTools.stringify(actual), JSONTools.stringify(expectedTree), "JSON body does not match");
    }
    return this;
  }

  /**
   * Asserts that the body, parsed as JSON, is equal to the JSON form of the given Java object. The expected object is
   * converted to its JSON shape the same way the Latte <code>json</code> library would serialize it: {@link Map}s
   * become JSON objects, {@link Iterable}s and arrays become JSON arrays, numbers, booleans, strings, and {@code null}
   * map to their JSON scalar forms, enums use their {@code name()}, and {@link UUID}, {@link URI}, {@link URL}, and the
   * ISO-8601 {@code java.time} types use their string forms. Records and POJOs with public fields are converted via
   * reflection; {@code null} components and fields are omitted, mirroring the library's {@code omitNulls} default.
   * Object-key ordering is always ignored; array ordering follows the {@link #unorderedArrays(boolean)} setting.
   * <p>
   * Reflection over a record or POJO in a named module requires its package to be opened (or exported) to
   * {@code org.lattejava.web}.
   *
   * @param expected The expected value.
   * @return This asserter for chaining.
   */
  public <T> JSONBodyAsserter equalTo(Function<byte[], T> converter, T expected) {
    if (body == null || body.length == 0) {
      Assertions.fail("JSON body was null");
    }

    T value = converter.apply(body);
    Assertions.assertEquals(value, expected, "JSON did not match the expected object");
    return this;
  }

  /**
   * Asserts that the body is JSON-equivalent to the expected JSON file (the golden file, by convention committed under
   * {@code src/test/json}), read as UTF-8. Object keys are unordered per JSON semantics, but arrays are always compared
   * positionally — array order is part of the wire format, so this method ignores the {@link #unorderedArrays(boolean)}
   * setting.
   * <p>
   * The expected file is plain JSON plus two kinds of tokens, each occupying part or all of a JSON string node:
   * <ul>
   *   <li><strong>Substitutions</strong> — values the test knows, referenced as {@code "${name}"} and supplied as
   *   name/value varargs pairs ({@code equalToFile(path, "applicationId", id)}). A string node that is exactly one
   *   token is replaced by the value with its JSON type intact. For example, {@code "foo": "${number}"} becomes
   *   {@code "foo": 42} if the replacement is a number. If it's replacement is a String, it remains a String. This
   *   allows IDEs and editors to validate the JSON file correctly. A string node with embedded tokens
   *   ({@code "http://localhost:${port}/"}) splices in the value's text form and remains a string.</li>
   *   <li><strong>Placeholders</strong> — values the test cannot know, each of which must be the entire string node:
   *   {@code "${anyBoolean}"}, {@code "${anyInstant}"}, {@code "${anyNumber}"}, {@code "${anyString}"},
   *   {@code "${anyUUID}"}, and {@code "${regex:PATTERN}"} (a scalar whose text form fully matches the pattern, which
   *   runs to the closing brace at the very end of the string node). A placeholder asserts type and shape at its
   *   position; it never matches a missing key or a JSON {@code null}.
   *   <p>
   *   A colon after the placeholder name starts an argument: {@code regex} takes its pattern bare, while
   *   {@code anyNumber}, {@code anyString}, and {@code anyInstant} take an inclusive range —
   *   {@code "${anyNumber:[-10:100]}"} bounds the value, {@code "${anyString:[0:100]}"} bounds the length, and
   *   {@code "${anyInstant:[2026-01-01T00:00:00Z/2026-12-31T00:00:00Z]}"} bounds the instant using ISO 8601 interval
   *   notation (a slash separator, because instants contain colons). Either bound may be empty for an open end, but
   *   not both.</li>
   * </ul>
   * <p>
   * NOTE: {@code anyInstant} is an ISO instant, not a milliseconds since Epoch.
   * <p>
   * A literal <code>${</code> in expected text is escaped as <code>$${</code>. Any other unescaped <code>${</code>
   * occurrence (an unknown name, a malformed or unterminated token) fails the assertion, as does a supplied
   * substitution that the file never references.
   * <p>
   * When the file does not exist, it is generated from the actual response (pretty-printed with a two-space indent in
   * wire key order) and the assertion fails with a reminder to review and commit it. When the file exists but does not
   * match and the {@code latte.web.json.update} system property is {@code true}, the file is rewritten from the actual
   * response — preserving tokens that still describe the actual values — and the assertion passes; {@code git diff} is
   * the review mechanism. When the {@code CI} environment variable is set, files are never written: a missing file
   * fails the test, and setting the update property fails the test on its own.
   *
   * @param file          The expected JSON file.
   * @param substitutions Substitution name/value pairs; each name must be a {@link String}.
   * @return This asserter for chaining.
   * @throws IllegalArgumentException If the substitutions are not name/value pairs or a name is not a String.
   */
  public JSONBodyAsserter equalToFile(Path file, Object... substitutions) {
    if (substitutions.length % 2 != 0) {
      throw new IllegalArgumentException("substitutions must be name/value pairs but [" + substitutions.length + "] arguments were provided");
    }

    var values = new LinkedHashMap<String, Object>();
    for (int i = 0; i < substitutions.length; i += 2) {
      if (!(substitutions[i] instanceof String name)) {
        throw new IllegalArgumentException("Substitution name at index [" + i + "] must be a String but was [" + substitutions[i] + "]");
      }
      values.put(name, substitutions[i + 1]);
    }

    boolean update = Boolean.getBoolean("latte.web.json.update");
    if (update && ci()) {
      throw new AssertionError("The system property [latte.web.json.update] must not be set in CI");
    }

    Object actual = parseActual();
    ExpectedJSONFile expected = ExpectedJSONFile.load(file, values);
    if (expected == null) {
      throw bootstrap(file, actual);
    }

    if (deepEquals(actual, expected.tree, false)) {
      return this;
    }

    if (update) {
      JSONTools.write(file, updateTree(expected.original, actual, expected));
      return this;
    }

    Assertions.failNotEqual(JSONTools.prettify(actual, false), JSONTools.prettify(expected.tree, false), "JSON body does not match the expected file [" + file + "]");
    return this;
  }

  /**
   * Asserts that the JSON tree contains a node at the given JSON Pointer.
   *
   * @param pointer The JSON Pointer (e.g. {@code "/user/name"} or {@code "/items/0/id"}).
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter hasElement(String pointer) {
    Object node = JSONTools.at(parseActual(), pointer);
    Assertions.assertTrue(node != JSONTools.MISSING, "JSON body is missing element at pointer [" + pointer + "]");
    return this;
  }

  /**
   * Asserts that the JSON tree does not contain a node at the given JSON Pointer.
   *
   * @param pointer The JSON Pointer.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter hasNoElement(String pointer) {
    Object node = JSONTools.at(parseActual(), pointer);
    Assertions.assertTrue(node == JSONTools.MISSING, "JSON body unexpectedly contains element at pointer [" + pointer + "]");
    return this;
  }

  /**
   * Asserts that the JSON value at the given JSON Pointer equals the expected string (compared via the value's text
   * form, so the JSON {@code 33} matches the expected {@code "33"} here).
   *
   * @param pointer  The JSON Pointer.
   * @param expected The expected text value.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter hasValue(String pointer, String expected) {
    Object node = JSONTools.at(parseActual(), pointer);
    String actual = (node == JSONTools.MISSING || node == null) ? null : JSONTools.asText(node);
    Assertions.assertEquals(actual, expected, "JSON value at pointer [" + pointer + "] does not match");
    return this;
  }

  /**
   * Asserts that the JSON value at the given JSON Pointer equals the JSON form of the given Java value. The expected
   * value is converted to its JSON shape as described on {@link #equalTo(Object)}, so numbers, booleans, strings,
   * {@code null}, {@link Map}s, {@link List}s, records, and POJOs are all accepted. Comparison is strict on JSON type —
   * {@code 33} (an int) does not match the JSON string {@code "33"}; use the {@code String} overload if you want loose
   * text comparison.
   * <p>
   * A missing pointer is treated as a JSON {@code null} for this comparison, so {@code hasValue("/missing", null)}
   * succeeds whether the pointer is missing or explicitly {@code null}. Array ordering follows the
   * {@link #unorderedArrays(boolean)} setting.
   *
   * @param pointer  The JSON Pointer.
   * @param expected The expected value.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter hasValue(String pointer, Object expected) {
    Object node = JSONTools.at(parseActual(), pointer);
    if (node == JSONTools.MISSING) {
      node = null;
    }
    Object expectedTree = JSONTools.toJSONObject(expected);
    if (!deepEquals(node, expectedTree, unorderedArrays)) {
      Assertions.failNotEqual(JSONTools.stringify(node), JSONTools.stringify(expectedTree), "JSON value at pointer [" + pointer + "] does not match");
    }
    return this;
  }

  /**
   * Returns whether array equality is currently order-insensitive.
   *
   * @return {@code true} if arrays are compared as multisets, {@code false} for strict positional comparison.
   */
  public boolean unorderedArrays() {
    return unorderedArrays;
  }

  /**
   * Toggles whether array equality ignores element order. May be called at any time, including between assertions on
   * the same instance.
   *
   * @param unorderedArrays {@code true} to compare arrays as multisets, {@code false} to require positional equality.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter unorderedArrays(boolean unorderedArrays) {
    this.unorderedArrays = unorderedArrays;
    return this;
  }

  /**
   * Detects a continuous-integration environment, in which {@link #equalToFile(Path, Object...)} never writes files. CI
   * is detected when the {@code CI} environment variable is set and non-empty; override in a subclass to change the
   * detection.
   *
   * @return {@code true} when running in CI.
   */
  protected boolean ci() {
    String ci = System.getenv("CI");
    return ci != null && !ci.isEmpty();
  }

  /**
   * Handles a missing expected JSON file: outside CI, the file is generated from the actual response so the developer
   * can review and commit it; in CI, nothing is written. Always returns the {@link AssertionError} for the caller to
   * throw.
   */
  private AssertionError bootstrap(Path file, Object actual) {
    if (ci()) {
      return new AssertionError("The expected JSON file [" + file + "] does not exist and expected files are never generated in CI. Generate the file locally, review it, and commit it.");
    }

    JSONTools.write(file, actual);
    return new AssertionError("The expected JSON file [" + file + "] did not exist, so it was generated from the actual response. Review the file and commit it.");
  }

  /**
   * Deep equality between two JSON values. JSON objects are always compared as unordered (compare key sets, recurse on
   * values). JSON arrays compare as multisets when {@link #unorderedArrays} is {@code true}, or positionally when
   * {@code false}. Decimals compare numerically (scale-insensitive), but cross-type comparisons stay strict (an integer
   * never equals a decimal, and a number never equals a string).
   */
  private boolean deepEquals(Object actual, Object expected, boolean unorderedArrays) {
    if (expected instanceof ExpectedJSONFile.Placeholder placeholder) {
      return matches(placeholder, actual);
    }

    if (actual == expected) {
      return true;
    }

    if (actual == null || expected == null) {
      return false;
    }

    switch (actual) {
      case Map<?, ?> mapA when expected instanceof Map<?, ?> mapE -> {
        if (mapA.size() != mapE.size()) {
          return false;
        }

        for (Map.Entry<?, ?> entry : mapA.entrySet()) {
          if (!mapE.containsKey(entry.getKey()) || !deepEquals(entry.getValue(), mapE.get(entry.getKey()), unorderedArrays)) {
            return false;
          }
        }

        return true;
      }
      case List<?> listA when expected instanceof List<?> listE -> {
        int size = listA.size();
        if (size != listE.size()) {
          return false;
        }

        if (unorderedArrays) {
          boolean[] used = new boolean[size];
          for (Object elementA : listA) {
            boolean found = false;
            for (int j = 0; j < size; j++) {
              if (!used[j] && deepEquals(elementA, listE.get(j), true)) {
                used[j] = true;
                found = true;
                break;
              }
            }

            if (!found) {
              return false;
            }
          }

          return true;
        }

        for (int i = 0; i < size; i++) {
          if (!deepEquals(listA.get(i), listE.get(i), false)) {
            return false;
          }
        }

        return true;
      }
      case BigDecimal decimalA when expected instanceof BigDecimal decimalE -> {
        return decimalA.compareTo(decimalE) == 0;
      }
      default -> {
      }
    }

    // Fallback that just compares the two objects directly
    return actual.equals(expected);
  }

  private boolean isInstant(String s) {
    try {
      Instant.parse(s);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private boolean isUUID(String s) {
    try {
      UUID.fromString(s);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Whether the actual value satisfies a placeholder, including its range when one was supplied ({@code anyNumber}
   * ranges bound the value, {@code anyString} ranges bound the length, and {@code anyInstant} ranges bound the
   * instant). Placeholders never match JSON {@code null}.
   */
  private boolean matches(ExpectedJSONFile.Placeholder placeholder, Object actual) {
    if (actual == null) {
      return false;
    }

    ExpectedJSONFile.Range range = placeholder.range();
    return switch (placeholder.name()) {
      case "anyBoolean" -> actual instanceof Boolean;
      case "anyInstant" -> actual instanceof String s && isInstant(s) &&
          (range == null || range.contains(ExpectedJSONFile.epochNanos(Instant.parse(s))));
      case "anyNumber" -> (actual instanceof Long || actual instanceof BigInteger || actual instanceof BigDecimal) &&
          (range == null || range.contains(new BigDecimal(actual.toString())));
      case "anyString" -> actual instanceof String s && (range == null || range.contains(BigDecimal.valueOf(s.length())));
      case "anyUUID" -> actual instanceof String s && isUUID(s);
      case "regex" -> !(actual instanceof Map) && !(actual instanceof List) &&
          placeholder.pattern().matcher(JSONTools.asText(actual)).matches();
      default -> false;
    };
  }

  private Object parseActual() {
    if (!parsed) {
      root = JSONTools.parse(body == null ? null : new String(body, StandardCharsets.UTF_8), "body");
      parsed = true;
    }
    return root;
  }

  /**
   * Builds the tree written by update mode: the actual tree, with tokens from the original (un-substituted) expected
   * tree preserved wherever they still describe the actual value. Maps follow the actual's key order (expected-only
   * keys drop, actual-only keys are written literally), arrays are positional (extra actual elements are literal), and
   * anything that no longer corresponds is replaced by the literal actual value.
   */
  private Object updateTree(Object expected, Object actual, ExpectedJSONFile source) {
    if (expected instanceof String s) {
      Object substituted = source.resolve(s);
      if (substituted instanceof ExpectedJSONFile.Placeholder placeholder) {
        return matches(placeholder, actual) ? new JSONTools.Verbatim(s) : actual;
      }

      return deepEquals(actual, substituted, false) ? new JSONTools.Verbatim(s) : actual;
    }

    if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
      var tree = new LinkedHashMap<String, Object>();
      for (Map.Entry<?, ?> entry : actualMap.entrySet()) {
        String key = String.valueOf(entry.getKey());
        tree.put(key, expectedMap.containsKey(key)
            ? updateTree(expectedMap.get(key), entry.getValue(), source)
            : entry.getValue());
      }
      return tree;
    }

    if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
      var tree = new ArrayList<>();
      for (int i = 0; i < actualList.size(); i++) {
        tree.add(i < expectedList.size()
            ? updateTree(expectedList.get(i), actualList.get(i), source)
            : actualList.get(i));
      }
      return tree;
    }

    return actual;
  }

}
