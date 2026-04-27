/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

public class LifecycleTest extends BaseWebTest {

  @Test
  public void childPrefix_afterParentStart_throws() {
    try (var web = new Web()) {
      var captured = new Web[1];
      web.prefix("/api", r -> captured[0] = r);
      web.start(PORT);

      try {
        captured[0].get("/users", (_, res) -> res.setStatus(200));
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test
  public void close_beforeStart_isSafe() {
    var web = new Web();
    web.close();  // Should not throw — server was never started
  }

  @Test
  public void close_calledTwice_isIdempotent() {
    var web = new Web();
    web.get("/test", (_, res) -> res.setStatus(200));
    web.start(PORT);
    web.close();
    web.close();  // Should not throw
  }

  @Test
  public void prefix_afterStart_throws() {
    try (var web = new Web()) {
      web.start(PORT);

      try {
        web.prefix("/api", r -> r.get("/users", (_, res) -> res.setStatus(200)));
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test
  public void route_afterStart_throws() {
    try (var web = new Web()) {
      web.get("/before", (_, res) -> res.setStatus(200));
      web.start(PORT);

      try {
        web.get("/after", (_, res) -> res.setStatus(200));
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }

      try {
        web.route(List.of("GET"), "/another", (_, res) -> res.setStatus(200));
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test
  public void start_calledTwice_throws() {
    try (var web = new Web()) {
      web.start(PORT);

      try {
        web.start(PORT);
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test
  public void start_portInUse_throwsAndLeavesWebRegisterable() {
    // HTTPServer.start() throws IllegalStateException when the port is in use. Web.start()
    // propagates the exception and does NOT set the started flag, so the caller can register
    // more routes and retry start() with a different port.
    try (var blocker = new Web()) {
      blocker.get("/b", (_, res) -> res.setStatus(200));
      blocker.start(PORT);

      try (var web = new Web()) {
        web.get("/a", (_, res) -> res.setStatus(200));

        try {
          web.start(PORT);
          fail("Expected IllegalStateException — port should be in use");
        } catch (IllegalStateException expected) {
          // expected
        }

        // started is still false: route registration continues to work
        web.get("/c", (_, res) -> res.setStatus(200));
      }
    }
  }
}
