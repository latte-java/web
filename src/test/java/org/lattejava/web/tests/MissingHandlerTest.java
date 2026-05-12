/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

public class MissingHandlerTest extends BaseWebTest {
  @Test
  public void missingHandler_defaultBehaviorPreservedWhenNotSet() throws Exception {
    try (var web = new Web()) {
      web.get("/exists", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/missing");
      assertEquals(response.statusCode(), 404);
      assertEquals(response.body(), "");
    }
  }

  @Test
  public void missingHandler_doesNotAffect405() throws Exception {
    try (var web = new Web()) {
      web.get("/x", (_, res) -> res.setStatus(200));
      web.missingHandler((_, res) -> {
        res.setStatus(418);
        res.setHeader("X-Missing-Handler", "ran");
      });
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/x");
      assertEquals(response.statusCode(), 405);
      assertEquals(response.headers().firstValue("Allow").orElse(null), "GET");
      assertFalse(response.headers().firstValue("X-Missing-Handler").isPresent(),
          "missingHandler must not run for 405");
    }
  }

  @Test
  public void missingHandler_invokedWhenNoRouteMatches() throws Exception {
    try (var web = new Web()) {
      web.get("/exists", (_, res) -> res.setStatus(200));
      web.missingHandler((req, res) -> {
        res.setStatus(410);
        res.setHeader("X-Missing-Path", req.getPath());
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/nope");
      assertEquals(response.statusCode(), 410);
      assertEquals(response.headers().firstValue("X-Missing-Path").orElse(null), "/nope");
    }
  }

  @Test
  public void missingHandler_rejectsCallAfterStart() throws Exception {
    try (var web = new Web()) {
      web.start(PORT);
      assertThrows(IllegalStateException.class,
          () -> web.missingHandler((_, res) -> res.setStatus(404)));
    }
  }

  @Test
  public void missingHandler_rejectsCallOnChildWeb() {
    try (var web = new Web()) {
      AtomicReference<Throwable> caught = new AtomicReference<>();
      web.prefix("/api", child -> {
        try {
          child.missingHandler((_, res) -> res.setStatus(404));
        } catch (Throwable t) {
          caught.set(t);
        }
      });
      assertNotNull(caught.get(), "Expected IllegalStateException, got nothing");
      assertTrue(caught.get() instanceof IllegalStateException,
          "Expected IllegalStateException, got: [" + caught.get() + "]");
    }
  }

  @Test
  public void missingHandler_rejectsNull() {
    try (var web = new Web()) {
      assertThrows(NullPointerException.class, () -> web.missingHandler(null));
    }
  }

  @Test
  public void missingHandler_runsAfterPrefixMiddlewares() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", child -> child.install((req, res, chain) -> {
        res.setHeader("X-Prefix-Middleware", "ran");
        chain.next(req, res);
      }));
      web.missingHandler((_, res) -> {
        res.setStatus(410);
        res.setHeader("X-Missing-Handler", "ran");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/api/nope");
      assertEquals(response.statusCode(), 410);
      assertEquals(response.headers().firstValue("X-Prefix-Middleware").orElse(null), "ran",
          "Prefix middleware must run before missingHandler");
      assertEquals(response.headers().firstValue("X-Missing-Handler").orElse(null), "ran",
          "missingHandler must still run after prefix middleware");
    }
  }
}
