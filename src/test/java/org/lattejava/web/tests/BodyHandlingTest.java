/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

public class BodyHandlingTest extends BaseWebTest {

  // Helper: a BodySupplier that reads the raw bytes as a UTF-8 String
  private static final BodySupplier<String> STRING_SUPPLIER =
      (req, _) -> new String(req.getBodyBytes(), StandardCharsets.UTF_8);

  @Test
  public void body_bodyVerbsHaveOverload() throws Exception {
    // Body-handling overloads are provided only for POST, PUT, and PATCH per RFC 9110 guidance.
    // GET, DELETE, and OPTIONS SHOULD NOT carry request bodies; use route() if you really need a
    // body on those verbs. HEAD is handled entirely by the HTTP server (routed to GET, body
    // stripped) and is never visible to the framework.
    try (var web = new Web()) {
      BodyHandler<String> h = (_, res, _) -> res.setStatus(200);
      web.post("/b", h, STRING_SUPPLIER);
      web.put("/c", h, STRING_SUPPLIER);
      web.patch("/e", h, STRING_SUPPLIER);
      web.start(PORT);

      HttpResponse<String> response = sendWithBody("POST", "/b", "x");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void body_handlerReceivesParsedBody() throws Exception {
    try (var web = new Web()) {
      BodyHandler<String> handler = (_, res, body) -> {
        res.setStatus(200);
        res.setHeader("X-Body-Length", String.valueOf(body.length()));
        res.setHeader("X-Body-Content", body);
      };

      web.post("/echo", handler, STRING_SUPPLIER);
      web.start(PORT);

      HttpResponse<String> response = sendWithBody("POST", "/echo", "hello world");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Body-Length").orElse(null), "11");
      assertEquals(response.headers().firstValue("X-Body-Content").orElse(null), "hello world");
    }
  }

  @Test
  public void body_middlewareRunsBeforeSupplier() throws Exception {
    try (var web = new Web()) {
      var order = new java.util.ArrayList<String>();
      web.post("/traced",
          (_, res, body) -> {
            order.add("handler:" + body);
            res.setStatus(200);
          },
          (req, _) -> {
            order.add("supplier");
            return new String(req.getBodyBytes(), StandardCharsets.UTF_8);
          },
          (req, res, chain) -> {
            order.add("middleware");
            chain.next(req, res);
          }
      );
      web.start(PORT);

      sendWithBody("POST", "/traced", "payload");
      assertEquals(order, List.of("middleware", "supplier", "handler:payload"));
    }
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void body_nullHandler_throws() {
    new Web().post("/x", null, STRING_SUPPLIER);
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void body_nullSupplier_throws() {
    new Web().post("/x", (_, _, _) -> {
    }, (BodySupplier<String>) null);
  }

  @Test
  public void body_pathParametersAvailable() throws Exception {
    try (var web = new Web()) {
      web.post("/users/{id}", (req, res, body) -> {
        res.setStatus(200);
        res.setHeader("X-Id", (String) req.getAttribute("id"));
        res.setHeader("X-Body", body);
      }, STRING_SUPPLIER);
      web.start(PORT);

      HttpResponse<String> response = sendWithBody("POST", "/users/42", "user-data");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Id").orElse(null), "42");
      assertEquals(response.headers().firstValue("X-Body").orElse(null), "user-data");
    }
  }

  @Test
  public void body_perRouteMiddlewareAfterSupplier() throws Exception {
    // Verify that the middleware varargs still work when using the body-handler form
    try (var web = new Web()) {
      web.post("/wrapped",
          (_, res, body) -> {
            res.setStatus(200);
            res.setHeader("X-Body", body);
          },
          STRING_SUPPLIER,
          (req, res, chain) -> {
            res.setHeader("X-Pre", "set");
            chain.next(req, res);
            res.setHeader("X-Post", "set");
          }
      );
      web.start(PORT);

      HttpResponse<String> response = sendWithBody("POST", "/wrapped", "data");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Pre").orElse(null), "set");
      assertEquals(response.headers().firstValue("X-Post").orElse(null), "set");
      assertEquals(response.headers().firstValue("X-Body").orElse(null), "data");
    }
  }

  @Test
  public void body_routeMethod_acceptsMultipleVerbs() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("POST", "PUT"), "/multi",
          (req, res, body) -> {
            res.setStatus(200);
            res.setHeader("X-Method", req.getMethod().name());
            res.setHeader("X-Body", body);
          },
          STRING_SUPPLIER
      );
      web.start(PORT);

      HttpResponse<String> resp = sendWithBody("POST", "/multi", "p");
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.headers().firstValue("X-Method").orElse(null), "POST");
      assertEquals(resp.headers().firstValue("X-Body").orElse(null), "p");

      resp = sendWithBody("PUT", "/multi", "q");
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.headers().firstValue("X-Method").orElse(null), "PUT");
      assertEquals(resp.headers().firstValue("X-Body").orElse(null), "q");
    }
  }

  @Test
  public void body_route_escapeHatchForNonBodyVerbs() throws Exception {
    // Developers who explicitly want a body on DELETE (or any non-standard verb) use route().
    try (var web = new Web()) {
      web.route(List.of("DELETE"), "/items/{id}",
          (_, res, body) -> {
            res.setStatus(200);
            res.setHeader("X-Body", body);
          },
          STRING_SUPPLIER
      );
      web.start(PORT);

      HttpResponse<String> response = sendWithBody("DELETE", "/items/42", "reason=cleanup");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Body").orElse(null), "reason=cleanup");
    }
  }

  @Test
  public void body_supplierException_propagatesToServer() throws Exception {
    // If the supplier throws, the exception propagates to HTTPServer which returns 500.
    try (var web = new Web()) {
      web.post("/boom", (_, res, _) -> res.setStatus(200), (_, _) -> {
        throw new RuntimeException("supplier failed");
      });
      web.start(PORT);

      HttpResponse<String> response = sendWithBody("POST", "/boom", "data");
      assertEquals(response.statusCode(), 500);
    }
  }

  @Test
  public void body_supplierReturnsNull_handlerNotCalled() throws Exception {
    try (var web = new Web()) {
      BodySupplier<String> rejectingSupplier = (_, res) -> {
        res.setStatus(400);
        res.setHeader("X-Rejected", "yes");
        return null;
      };
      web.post("/validated", (_, res, _) -> {
        // Should never be invoked
        res.setStatus(200);
        res.setHeader("X-Handler-Ran", "yes");
      }, rejectingSupplier);
      web.start(PORT);

      HttpResponse<String> response = sendWithBody("POST", "/validated", "anything");
      assertEquals(response.statusCode(), 400);
      assertEquals(response.headers().firstValue("X-Rejected").orElse(null), "yes");
      assertNull(response.headers().firstValue("X-Handler-Ran").orElse(null));
    }
  }

  // Helper: a POST with a string body
  private HttpResponse<String> sendWithBody(String method, String path, String body) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(BASE_URL + path))
                                     .method(method, HttpRequest.BodyPublishers.ofString(body))
                                     .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
