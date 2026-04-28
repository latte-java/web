/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.log.RecordingLoggerFactory;

import static org.testng.Assert.*;

public class LoggingTest extends BaseWebTest {
  @Test
  public void loggerFactory_afterStart_throws() {
    try (var web = new Web()) {
      web.start(PORT);
      try {
        web.loggerFactory(new RecordingLoggerFactory());
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void loggerFactory_null_throws() {
    new Web().loggerFactory(null);
  }

  @Test
  public void loggerFactory_swapped_routesHTTPServerLogs() {
    var recording = new RecordingLoggerFactory();
    try (var web = new Web()) {
      web.loggerFactory(recording).start(PORT);
    }

    // HTTPServer emits at least one info line during startup. Whatever it logs must have flowed through
    // the configured factory — i.e., into the recording logger's entries — rather than to stdout via
    // the bundled SystemOutLoggerFactory.
    assertFalse(recording.logger.entries.isEmpty(),
        "Expected HTTPServer log lines to be captured by the configured factory");
  }

  @Test
  public void logLevel_afterStart_throws() {
    try (var web = new Web()) {
      web.start(PORT);
      try {
        web.logLevel(Level.Debug);
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test
  public void logLevel_atError_suppressesInfo() {
    var recording = new RecordingLoggerFactory();
    try (var web = new Web()) {
      web.loggerFactory(recording).logLevel(Level.Error).start(PORT);
    }

    // HTTPServer emits info-level messages during start. With logLevel set to Error before start(),
    // those info lines must not appear in the recorded entries.
    assertTrue(recording.logger.messagesAtLevel(Level.Info).isEmpty(),
        "Expected no Info entries; got " + recording.logger.messagesAtLevel(Level.Info));
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void logLevel_null_throws() {
    new Web().logLevel(null);
  }

  @Test
  public void start_intPort_logsHTTPLocalhostURL() {
    var recording = new RecordingLoggerFactory();
    try (var web = new Web()) {
      web.loggerFactory(recording).start(PORT);
    }

    String expected = "Web application is available at [http://localhost:" + PORT + "]";
    List<String> infos = recording.logger.messagesAtLevel(Level.Info);
    assertTrue(infos.contains(expected),
        "Expected info message [" + expected + "] in " + infos);
  }

  @Test
  public void start_specificIPv4_logsThatHost() throws Exception {
    var recording = new RecordingLoggerFactory();
    var listener = new HTTPListenerConfiguration(InetAddress.getByName("127.0.0.1"), PORT);
    try (var web = new Web()) {
      web.loggerFactory(recording).start(listener);
    }

    String expected = "Web application is available at [http://127.0.0.1:" + PORT + "]";
    List<String> infos = recording.logger.messagesAtLevel(Level.Info);
    assertTrue(infos.contains(expected),
        "Expected info message [" + expected + "] in " + infos);
  }

  @Test
  public void start_ipv6Loopback_bracketsAddress() throws Exception {
    var recording = new RecordingLoggerFactory();
    var listener = new HTTPListenerConfiguration(InetAddress.getByName("::1"), PORT);
    try (var web = new Web()) {
      web.loggerFactory(recording).start(listener);
    }

    String expected = "Web application is available at [http://[0:0:0:0:0:0:0:1]:" + PORT + "]";
    List<String> infos = recording.logger.messagesAtLevel(Level.Info);
    assertTrue(infos.contains(expected),
        "Expected info message [" + expected + "] in " + infos);
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void start_nullListener_throws() {
    new Web().start((HTTPListenerConfiguration) null);
  }
}
