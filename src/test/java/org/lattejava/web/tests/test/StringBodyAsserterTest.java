/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.test;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

@Test
public class StringBodyAsserterTest {

  @Test
  public void chaining_multipleAssertions() {
    asserterFor("Hello, World!")
        .contains("Hello")
        .contains("World")
        .doesNotContain("Goodbye")
        .matches(".*World.*")
        .notEqualTo("Goodbye")
        .isNotEmpty();
  }

  @Test
  public void contains_absentFails() {
    var asserter = asserterFor("Hello");
    expectAssertionError(() -> asserter.contains("World"), "Body does not contain substring");
  }

  @Test
  public void contains_nullBodyFails() {
    var asserter = new StringBodyAsserter();
    asserter.body(null);
    expectAssertionError(() -> asserter.contains("anything"), "Body does not contain substring");
  }

  @Test
  public void contains_present() {
    asserterFor("Hello, World!")
        .contains("Hello")
        .contains("World")
        .contains(", ");
  }

  @Test
  public void doesNotContain_absent() {
    asserterFor("Hello").doesNotContain("World")
                        .doesNotContain("Goodbye");
  }

  @Test
  public void doesNotContain_nullBodyPasses() {
    var asserter = new StringBodyAsserter();
    asserter.body(null);
    asserter.doesNotContain("anything");
  }

  @Test
  public void doesNotContain_presentFails() {
    var asserter = asserterFor("Hello, World!");
    expectAssertionError(() -> asserter.doesNotContain("World"),
        "Body unexpectedly contains substring [World]");
  }

  @Test
  public void equalTo_emptyMatchesEmpty() {
    asserterFor("").equalTo("");
  }

  @Test
  public void equalTo_match() {
    asserterFor("Hello, World!").equalTo("Hello, World!");
  }

  @Test
  public void equalTo_mismatchUsesTestNGFormat() {
    var asserter = asserterFor("Hello");
    expectAssertionError(() -> asserter.equalTo("Goodbye"),
        "Body does not match - expected [Goodbye] but found [Hello]");
  }

  @Test
  public void equalTo_nullBodyFailsWithFormat() {
    var asserter = new StringBodyAsserter();
    asserter.body(null);
    expectAssertionError(() -> asserter.equalTo("Hello"),
        "expected [Hello] but found [null]");
  }

  @Test
  public void isEmpty_emptyPasses() {
    asserterFor("").isEmpty();
  }

  @Test
  public void isEmpty_nonEmptyFails() {
    var asserter = asserterFor("Hello");
    expectAssertionError(asserter::isEmpty, "Body is not empty");
  }

  @Test
  public void isEmpty_nullPasses() {
    var asserter = new StringBodyAsserter();
    asserter.body(null);
    asserter.isEmpty();
  }

  @Test
  public void isNotEmpty_emptyFails() {
    var asserter = asserterFor("");
    expectAssertionError(asserter::isNotEmpty, "Body is empty");
  }

  @Test
  public void isNotEmpty_nonEmptyPasses() {
    asserterFor("Hello").isNotEmpty();
  }

  @Test
  public void isNotEmpty_nullFails() {
    var asserter = new StringBodyAsserter();
    asserter.body(null);
    expectAssertionError(asserter::isNotEmpty, "Body is empty");
  }

  @Test
  public void matches_anchorBehaviorIsFullMatch() {
    // Pattern.compile().matcher().matches() requires the entire string to match — a partial match is not enough.
    var asserter = asserterFor("prefix-hello-123-suffix");
    expectAssertionError(() -> asserter.matches("[a-z]+-\\d+"),
        "Body does not match regex");
  }

  @Test
  public void matches_fullMatch() {
    asserterFor("hello-123").matches("[a-z]+-\\d+");
  }

  @Test
  public void matches_nullBodyFails() {
    var asserter = new StringBodyAsserter();
    asserter.body(null);
    expectAssertionError(() -> asserter.matches(".*"), "Body does not match regex [.*]");
  }

  @Test
  public void notEqualTo_different() {
    asserterFor("Hello").notEqualTo("Goodbye");
  }

  @Test
  public void notEqualTo_sameFailsWithFormat() {
    var asserter = asserterFor("Hello");
    expectAssertionError(() -> asserter.notEqualTo("Hello"),
        "Body unexpectedly matches - did not expect [Hello] but found [Hello]");
  }

  @Test
  public void rebody_overridesPreviousBytes() {
    var asserter = asserterFor("first");
    asserter.equalTo("first");

    asserter.body("second".getBytes(StandardCharsets.UTF_8));
    asserter.equalTo("second")
            .doesNotContain("first");
  }

  @Test
  public void utf8Decoding_handlesNonASCII() {
    // Build the bytes from a UTF-8 string, then verify the asserter decodes them back to the same characters.
    String original = "café — 日本語";
    var asserter = new StringBodyAsserter();
    asserter.body(original.getBytes(StandardCharsets.UTF_8));
    asserter.equalTo(original)
            .contains("café")
            .contains("日本語");
  }

  private StringBodyAsserter asserterFor(String body) {
    var asserter = new StringBodyAsserter();
    asserter.body(body.getBytes(StandardCharsets.UTF_8));
    return asserter;
  }

  private void expectAssertionError(Runnable runnable, String expectedMessageFragment) {
    try {
      runnable.run();
      fail("Expected an AssertionError but none was thrown");
    } catch (AssertionError e) {
      String message = e.getMessage();
      assertNotNull(message, "AssertionError message must not be null");
      assertTrue(message.contains(expectedMessageFragment),
          "AssertionError message [" + message + "] does not contain expected fragment ["
              + expectedMessageFragment + "]");
    }
  }
}
