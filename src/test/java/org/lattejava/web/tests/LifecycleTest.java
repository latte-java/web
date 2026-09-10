/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

public class LifecycleTest extends BaseWebTest {

  @Test
  public void childPrefix_afterParentStart_throws() {
    try (var web = new Web()) {
      var captured = new Web[1];
      web.prefix("/api", r -> captured[0] = r)
         .start(PORT);

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
    var web = new Web().get("/test", (_, res) -> res.setStatus(200))
                       .start(PORT);
    web.close();
    web.close();  // Should not throw
  }

  @Test
  public void close_withShutdownTasks() {
    AtomicBoolean shutdown = new AtomicBoolean(false);
    var web = new Web().addShutdownTask(() -> shutdown.set(true))
                       .start(PORT);
    web.close();
    assertTrue(shutdown.get());
  }

  @Test
  public void prefix_afterStart_throws() {
    try (var web = new Web().start(PORT)) {
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
    try (var web = new Web().get("/before", (_, res) -> res.setStatus(200))
                            .start(PORT)) {
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
  public void start_calledAfterStart_throws() {
    try (var web = new Web().start(PORT)) {
      assertThrows(IllegalStateException.class, web::start);
    }
  }

  @Test
  public void start_calledTwice_throws() {
    try (var web = new Web().start(PORT)) {
      try {
        web.start(PORT);
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test
  public void start_noListeners_throws() {
    try (var web = new Web()) {
      assertThrows(IllegalStateException.class, web::start);
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

      try (var web = new Web().get("/a", (_, res) -> res.setStatus(200))) {
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

  @Test
  public void start_withConfiguration() throws Exception {
    try (var _ = new Web().withListener(new HTTPListenerConfiguration(PORT))
                          .withSendDateHeader(false)
                          .get("/ok", (_, res) -> res.setStatus(200))
                          .start()) {
      var response = send("GET", "/ok");
      assertEquals(response.statusCode(), 200);
      assertTrue(response.headers().firstValue("Date").isEmpty(), "Configuration settings should reach the server");
    }
  }

  @Test
  public void start_withConfiguration_baseDirFromConfiguration() throws Exception {
    try (var _ = new Web().withListener(new HTTPListenerConfiguration(PORT))
                          .withBaseDir(Path.of("src/test/projects/static-resources"))
                          .files("/assets")
                          .start()) {
      assertEquals(send("GET", "/assets/app.css").statusCode(), 200);
    }
  }

  @Test
  public void start_withConfiguration_baseDirFromWeb() throws Exception {
    try (var _ = new Web().withListener(new HTTPListenerConfiguration(PORT))
                          .withBaseDir(Path.of("nowhere"))
                          .baseDir(Path.of("src/test/projects/static-resources"))
                          .files("/assets")
                          .start()) {
      assertEquals(send("GET", "/assets/app.css").statusCode(), 200);
    }
  }

  @Test
  public void start_withConfiguration_child_throws() {
    try (var web = new Web()) {
      web.prefix("/api", r -> {
        assertThrows(IllegalStateException.class, r::start);
        assertThrows(IllegalStateException.class, () -> r.start(8080));
      });
    }
  }
}
