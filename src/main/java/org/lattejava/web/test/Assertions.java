/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.test;

/**
 * Internal helpers that format {@link AssertionError} messages so they match TestNG's wire format.
 * <p>
 * IntelliJ IDEA's TestNG plugin (and the TestNG runner) recognize the {@code expected [...] but found [...]} message
 * pattern and offer a side-by-side comparison view when a test fails. By emitting the same string layout from this
 * library's asserters, IDE error highlighting works the same as it does for native {@code org.testng.Assert} calls.
 * <p>
 * The constants below mirror those in {@code org.testng.internal.EclipseInterface} verbatim.
 *
 * @author Brian Pontarelli
 */
final class Assertions {
  static final String ASSERT_EQUAL_LEFT = "expected [";
  static final String ASSERT_MIDDLE = "] but found [";
  static final String ASSERT_RIGHT = "]";
  static final String ASSERT_UNEQUAL_LEFT = "did not expect [";

  private Assertions() {
  }

  /**
   * Asserts that the two values are equal, throwing an {@link AssertionError} formatted in TestNG's wire format if not.
   *
   * @param actual   The actual value.
   * @param expected The expected value.
   * @param message  An optional prefix describing what was being checked (may be {@code null}).
   */
  static void assertEquals(Object actual, Object expected, String message) {
    if (areEqual(actual, expected)) {
      return;
    }
    throw new AssertionError(format(actual, expected, message, true));
  }

  /**
   * Asserts that the two values are not equal, throwing an {@link AssertionError} if they are.
   *
   * @param actual   The actual value.
   * @param expected The value the actual is expected to differ from.
   * @param message  An optional prefix describing what was being checked.
   */
  static void assertNotEquals(Object actual, Object expected, String message) {
    if (!areEqual(actual, expected)) {
      return;
    }
    throw new AssertionError(format(actual, expected, message, false));
  }

  /**
   * Asserts that {@code condition} is true, throwing an {@link AssertionError} otherwise.
   *
   * @param condition The condition to check.
   * @param message   An optional prefix describing what was being checked.
   */
  static void assertTrue(boolean condition, String message) {
    if (condition) {
      return;
    }
    throw new AssertionError(format(false, true, message, true));
  }

  /**
   * Throws an {@link AssertionError} with the given message.
   *
   * @param message The failure message.
   */
  static void fail(String message) {
    throw new AssertionError(message);
  }

  /**
   * Throws an {@link AssertionError} formatted as {@code <message> expected [<expected>] but found [<actual>]}. Used
   * when equality has already been determined by the caller (e.g. with a custom comparator).
   *
   * @param actual   The actual value.
   * @param expected The expected value.
   * @param message  An optional prefix describing what was being checked.
   */
  static void failNotEqual(Object actual, Object expected, String message) {
    throw new AssertionError(format(actual, expected, message, true));
  }

  private static boolean areEqual(Object actual, Object expected) {
    if (actual == null && expected == null) {
      return true;
    }
    if (actual == null || expected == null) {
      return false;
    }
    return actual.equals(expected) && expected.equals(actual);
  }

  private static String format(Object actual, Object expected, String message, boolean isAssertEquals) {
    StringBuilder builder = new StringBuilder();
    if (message != null) {
      builder.append(message).append(" - ");
    }
    builder.append(isAssertEquals ? ASSERT_EQUAL_LEFT : ASSERT_UNEQUAL_LEFT)
           .append(expected)
           .append(ASSERT_MIDDLE)
           .append(actual)
           .append(ASSERT_RIGHT);
    return builder.toString();
  }
}
