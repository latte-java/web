/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.middleware;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.testng.Assert.*;

public class ExceptionHandlerTest extends BaseWebTest {

  @Test
  public void defaultRenderer_rendersHTTPExceptionStatusAndMessage() throws Exception {
    try (var web = new Web()) {
      web.install(new ExceptionHandler());
      web.get("/bad", (_, _) -> {
        throw new BadRequestException("nope");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/bad");
      assertEquals(response.statusCode(), 400);
      assertEquals(response.body(), "nope");
    }
  }

  @Test
  public void defaultRenderer_usesCarriedStatusForSubtypes() throws Exception {
    try (var web = new Web()) {
      web.install(new ExceptionHandler());
      web.get("/forbidden", (_, _) -> {
        throw new ForbiddenException("denied");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/forbidden");
      assertEquals(response.statusCode(), 403);
      assertEquals(response.body(), "denied");
    }
  }

  @Test
  public void customDefaultRenderer_rendersAllHTTPExceptions() throws Exception {
    ErrorRenderer json = (_, res, e) -> {
      int status = ((HTTPException) e).status();
      res.setStatus(status);
      res.setHeader("Content-Type", "application/json");
      res.getWriter().write("{\"status\":" + status + ",\"error\":\"" + e.getMessage() + "\"}");
    };

    try (var web = new Web()) {
      web.install(new ExceptionHandler(json));
      web.get("/bad", (_, _) -> {
        throw new BadRequestException("bad input");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/bad");
      assertEquals(response.statusCode(), 400);
      assertEquals(response.body(), "{\"status\":400,\"error\":\"bad input\"}");
    }
  }

  @Test
  public void perTypeRenderer_overridesDefaultForThatType() throws Exception {
    ErrorRenderer fieldErrors = (_, res, _) -> {
      res.setStatus(422);
      res.getWriter().write("field errors");
    };

    try (var web = new Web()) {
      web.install(new ExceptionHandler(Map.of(ValidationException.class, fieldErrors)));
      web.get("/validate", (_, _) -> {
        throw new ValidationException();
      });
      web.get("/bad", (_, _) -> {
        throw new BadRequestException("falls through to default");
      });
      web.start(PORT);

      HttpResponse<String> validate = send("GET", "/validate");
      assertEquals(validate.statusCode(), 422);
      assertEquals(validate.body(), "field errors");

      // A type without a per-type renderer still falls through to the default renderer.
      HttpResponse<String> bad = send("GET", "/bad");
      assertEquals(bad.statusCode(), 400);
      assertEquals(bad.body(), "falls through to default");
    }
  }

  @Test
  public void perTypeRenderer_handlesForeignExceptionWithStatus() throws Exception {
    // A non-HTTPException can be mapped by registering a renderer that sets the status itself.
    ErrorRenderer badRequest = (_, res, _) -> res.setStatus(400);

    try (var web = new Web()) {
      web.install(new ExceptionHandler(Map.of(IllegalArgumentException.class, badRequest)));
      web.get("/bad", (_, _) -> {
        throw new IllegalArgumentException("foreign");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/bad");
      assertEquals(response.statusCode(), 400);
    }
  }

  @Test
  public void perTypeRenderer_mostSpecificMappingWins() throws Exception {
    ErrorRenderer runtime = (_, res, _) -> res.setStatus(500);
    ErrorRenderer iae = (_, res, _) -> res.setStatus(400);

    try (var web = new Web()) {
      // IllegalArgumentException is more specific than RuntimeException; its renderer should win.
      Map<Class<? extends Throwable>, ErrorRenderer> renderers = new HashMap<>();
      renderers.put(RuntimeException.class, runtime);
      renderers.put(IllegalArgumentException.class, iae);
      web.install(new ExceptionHandler(renderers));
      web.get("/specific", (_, _) -> {
        throw new IllegalArgumentException("specific");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/specific");
      assertEquals(response.statusCode(), 400);
    }
  }

  @Test
  public void perTypeRenderer_walksUpHierarchy() throws Exception {
    ErrorRenderer runtime = (_, res, _) -> res.setStatus(500);

    try (var web = new Web()) {
      web.install(new ExceptionHandler(Map.of(RuntimeException.class, runtime)));
      web.get("/subclass", (_, _) -> {
        throw new IllegalStateException("subclass of RuntimeException");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/subclass");
      assertEquals(response.statusCode(), 500);
    }
  }

  @Test
  public void unmappedForeignException_propagates() throws Exception {
    try (var web = new Web()) {
      web.install(new ExceptionHandler(Map.of(IllegalStateException.class, (_, res, _) -> res.setStatus(400))));
      web.get("/unmapped", (_, _) -> {
        throw new RuntimeException("unmapped");
      });
      web.start(PORT);

      // RuntimeException is neither registered nor an HTTPException; HTTPServer default handling returns 500.
      HttpResponse<String> response = send("GET", "/unmapped");
      assertEquals(response.statusCode(), 500);
    }
  }

  @Test
  public void noExceptionPassesThrough() throws Exception {
    try (var web = new Web()) {
      web.install(new ExceptionHandler());
      web.get("/ok", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/ok");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void subclassCanOverrideResolveRenderer() throws Exception {
    // A subclass overrides resolveRenderer() to compute the renderer dynamically.
    var middleware = new ExceptionHandler(Map.of()) {
      @Override
      protected ErrorRenderer resolveRenderer(Class<?> type) {
        return type.getSimpleName().contains("NotFound") ? (_, res, _) -> res.setStatus(404) : null;
      }
    };

    try (var web = new Web()) {
      web.install(middleware);
      web.get("/missing", (_, _) -> {
        throw new NotFoundException("no");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/missing");
      assertEquals(response.statusCode(), 404);
    }
  }

  static class NotFoundException extends RuntimeException {
    NotFoundException(String message) {
      super(message);
    }
  }

  static class ValidationException extends RuntimeException {
  }
}
