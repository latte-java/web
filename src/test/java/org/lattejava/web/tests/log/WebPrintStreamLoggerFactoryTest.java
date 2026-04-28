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
