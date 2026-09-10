/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import io.avaje.inject.BeanScope;
import org.lattejava.web.tests.BaseWebTest;

import static org.testng.Assert.*;

public class RequestContextTest extends BaseWebTest {
  @Test
  public void handler() throws Exception {
    try (var web = new Web()) {
      web.get("/check", (req, res) -> res.setStatus(sameAs(req, res) ? 204 : 500))
         .start(PORT);

      assertEquals(send("GET", "/check").statusCode(), 204);
    }
  }

  @Test
  public void injected() throws Exception {
    try (var scope = BeanScope.builder().build(); var web = new Web()) {
      web.injector(scope::get)
         .get("/users/{id}", web.inject(InjectedRequestHandler.class, InjectedRequestHandler::handle))
         .start(PORT);

      HttpResponse<String> response = send("GET", "/users/42");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), "Hello, 42");
    }
  }

  @Test
  public void middlewareAndMissingHandler() throws Exception {
    try (var web = new Web()) {
      web.install((req, res, chain) -> {
           res.setHeader("X-Bound", String.valueOf(sameAs(req, res)));
           chain.next(req, res);
         })
         .missingHandler((req, res) -> res.setStatus(sameAs(req, res) ? 404 : 500))
         .start(PORT);

      HttpResponse<String> response = send("GET", "/nope");
      assertEquals(response.statusCode(), 404);
      assertEquals(response.headers().firstValue("X-Bound").orElse(null), "true");
    }
  }

  @Test
  public void unbound() {
    IllegalStateException request = expectThrows(IllegalStateException.class, RequestContext::request);
    assertTrue(request.getMessage().contains("No request is bound"));

    IllegalStateException response = expectThrows(IllegalStateException.class, RequestContext::response);
    assertTrue(response.getMessage().contains("No response is bound"));
  }

  private static boolean sameAs(HTTPRequest req, HTTPResponse res) {
    return RequestContext.request() == req && RequestContext.response() == res;
  }
}
