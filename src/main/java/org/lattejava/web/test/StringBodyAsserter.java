/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.test;

import module java.base;

/**
 * A {@link BodyAsserter} that asserts the response body as a plain string.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class StringBodyAsserter extends BodyAsserter {
  private String string;

  @Override
  public void body(byte[] body) {
    super.body(body);
    this.string = body == null ? null : new String(body, StandardCharsets.UTF_8);
  }

  /**
   * Asserts that the body contains the given substring.
   *
   * @param part The substring the body must contain.
   * @return This asserter for chaining.
   */
  public StringBodyAsserter contains(String part) {
    Assertions.assertTrue(string != null && string.contains(part), "Body does not contain substring");
    return this;
  }

  /**
   * Asserts that the body does not contain the given substring.
   *
   * @param part The substring that must not appear in the body.
   * @return This asserter for chaining.
   */
  public StringBodyAsserter doesNotContain(String part) {
    Assertions.assertTrue(string == null || !string.contains(part), "Body unexpectedly contains substring [" + part + "]");
    return this;
  }

  /**
   * Asserts that the body equals the given expected string.
   *
   * @param expected The expected body.
   * @return This asserter for chaining.
   */
  public StringBodyAsserter equalTo(String expected) {
    Assertions.assertEquals(string, expected, "Body does not match");
    return this;
  }

  /**
   * Asserts that the body is empty (null or zero length).
   *
   * @return This asserter for chaining.
   */
  public StringBodyAsserter isEmpty() {
    Assertions.assertTrue(string == null || string.isEmpty(), "Body is not empty");
    return this;
  }

  /**
   * Asserts that the body is not empty.
   *
   * @return This asserter for chaining.
   */
  public StringBodyAsserter isNotEmpty() {
    Assertions.assertTrue(string != null && !string.isEmpty(), "Body is empty");
    return this;
  }

  /**
   * Asserts that the body matches the given regular expression in full.
   *
   * @param regex The regular expression.
   * @return This asserter for chaining.
   */
  public StringBodyAsserter matches(String regex) {
    Assertions.assertTrue(string != null && Pattern.compile(regex).matcher(string).matches(),
        "Body does not match regex [" + regex + "]");
    return this;
  }

  /**
   * Asserts that the body does not equal the given string.
   *
   * @param expected The string the body must differ from.
   * @return This asserter for chaining.
   */
  public StringBodyAsserter notEqualTo(String expected) {
    Assertions.assertNotEquals(string, expected, "Body unexpectedly matches");
    return this;
  }
}
