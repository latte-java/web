/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.log;

import module java.base;
import module org.lattejava.http;

/**
 * A {@link LoggerFactory} that always returns a single shared {@link WebPrintStreamLogger} writing to a configurable
 * {@link PrintStream} (defaulting to {@link System#out}). This is the default factory used by
 * {@link org.lattejava.web.Web} when none is configured.
 *
 * @author Brian Pontarelli
 */
public class WebPrintStreamLoggerFactory implements LoggerFactory {
  public static final WebPrintStreamLoggerFactory FACTORY = new WebPrintStreamLoggerFactory();

  private final WebPrintStreamLogger logger;

  public WebPrintStreamLoggerFactory() {
    this(System.out);
  }

  public WebPrintStreamLoggerFactory(PrintStream out) {
    this.logger = new WebPrintStreamLogger(out);
  }

  @Override
  public Logger getLogger(Class<?> klass) {
    return logger;
  }
}
