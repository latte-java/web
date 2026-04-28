/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.log;

import module java.base;
import module org.lattejava.http;

/**
 * A {@link PrintStream}-backed logger whose timestamp prefix is an ISO-8601 offset date-time formatted in the system
 * default time zone (e.g. {@code 2026-04-27T13:45:23.689-04:00}). Defaults to {@link System#out}; tests can inject a
 * different stream (e.g. one wrapping a {@link ByteArrayOutputStream}) without redirecting {@code System.out}.
 *
 * @author Brian Pontarelli
 */
public class WebPrintStreamLogger extends BaseLogger {
  private static final DateTimeFormatter TIMESTAMP_FORMATTER = new DateTimeFormatterBuilder()
      .append(DateTimeFormatter.ISO_LOCAL_DATE)
      .appendLiteral('T')
      .appendValue(ChronoField.HOUR_OF_DAY, 2)
      .appendLiteral(':')
      .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
      .appendLiteral(':')
      .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
      .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
      .appendOffsetId()
      .toFormatter();

  private final PrintStream out;

  public WebPrintStreamLogger() {
    this(System.out);
  }

  public WebPrintStreamLogger(PrintStream out) {
    Objects.requireNonNull(out, "out must not be null");
    this.out = out;
  }

  @Override
  protected void handleMessage(String message) {
    out.println(message);
  }

  @Override
  protected String timestamp() {
    return OffsetDateTime.now().format(TIMESTAMP_FORMATTER) + " ";
  }
}
