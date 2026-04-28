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
  public void constructor_default_doesNotThrow() {
    new WebPrintStreamLogger();
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void constructor_nullStream_throws() {
    new WebPrintStreamLogger(null);
  }

  @Test
  public void error_withThrowable_includesStackTrace() {
    var logger = new WebPrintStreamLogger(stream);
    logger.error("boom", new RuntimeException("kapow"));

    String out = captured.toString(StandardCharsets.UTF_8);
    assertTrue(out.contains("boom"), "Got [" + out + "]");
    assertTrue(out.contains("java.lang.RuntimeException: kapow"), "Got [" + out + "]");
  }

  @Test
  public void info_belowLevel_emitsNothing() {
    var logger = new WebPrintStreamLogger(stream);
    logger.setLevel(Level.Error);
    logger.info("hello");

    assertEquals(captured.toString(StandardCharsets.UTF_8), "");
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
}
