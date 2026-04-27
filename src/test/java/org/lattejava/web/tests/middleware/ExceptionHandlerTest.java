/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
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
  public void exceptionMiddleware_globalInstall_mapsExceptionForAnyRoute() throws Exception {
    try (var web = new Web()) {
      web.install(new ExceptionHandler(Map.of(NotFoundException.class, 404)));
      web.get("/missing", (_, _) -> {
        throw new NotFoundException("not here");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/missing");
      assertEquals(response.statusCode(), 404);
    }
  }

  @Test
  public void exceptionMiddleware_mapsThrownException() throws Exception {
    try (var web = new Web()) {
      web.install(new ExceptionHandler(Map.of(IllegalArgumentException.class, 400)));
      web.get("/bad", (_, _) -> {
        throw new IllegalArgumentException("nope");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/bad");
      assertEquals(response.statusCode(), 400);
    }
  }

  @Test
  public void exceptionMiddleware_mostSpecificMappingWins() throws Exception {
    try (var web = new Web()) {
      // Map both RuntimeException (500) and IllegalArgumentException (400).
      // When IAE is thrown, the 400 mapping should win because it's more specific.
      web.install(new ExceptionHandler(Map.of(
          RuntimeException.class, 500,
          IllegalArgumentException.class, 400
      )));
      web.get("/specific", (_, _) -> {
        throw new IllegalArgumentException("specific");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/specific");
      assertEquals(response.statusCode(), 400);
    }
  }

  @Test
  public void exceptionMiddleware_noExceptionPassesThrough() throws Exception {
    try (var web = new Web()) {
      web.install(new ExceptionHandler(Map.of(RuntimeException.class, 500)));
      web.get("/ok", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/ok");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void exceptionMiddleware_subclassCanOverrideLookupStatus() throws Exception {
    // A subclass overrides lookupStatus() to compute the status dynamically.
    var middleware = new ExceptionHandler(Map.of()) {
      @Override
      protected Integer lookupStatus(Class<?> type) {
        // Any exception whose class name contains "NotFound" → 404; everything else falls through
        return type.getSimpleName().contains("NotFound") ? 404 : null;
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

  @Test
  public void exceptionMiddleware_subclassCanWriteBody() throws Exception {
    // A subclass overrides writeBody() to emit a response body for mapped exceptions.
    var middleware = new ExceptionHandler(Map.of(IllegalArgumentException.class, 400)) {
      @Override
      protected void writeBody(HTTPRequest req, HTTPResponse res, Exception exception, int status) throws Exception {
        res.setHeader("X-Error-Class", exception.getClass().getSimpleName());
        res.setHeader("X-Error-Status", String.valueOf(status));
        res.getWriter().write("error: " + exception.getMessage());
      }
    };

    try (var web = new Web()) {
      web.install(middleware);
      web.get("/err", (_, _) -> {
        throw new IllegalArgumentException("bad input");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/err");
      assertEquals(response.statusCode(), 400);
      assertEquals(response.headers().firstValue("X-Error-Class").orElse(null), "IllegalArgumentException");
      assertEquals(response.headers().firstValue("X-Error-Status").orElse(null), "400");
      assertEquals(response.body(), "error: bad input");
    }
  }

  @Test
  public void exceptionMiddleware_subclassWalksUpHierarchy() throws Exception {
    try (var web = new Web()) {
      web.install(new ExceptionHandler(Map.of(RuntimeException.class, 500)));
      web.get("/subclass", (_, _) -> {
        throw new IllegalStateException("subclass of RuntimeException");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/subclass");
      assertEquals(response.statusCode(), 500);
    }
  }

  @Test
  public void exceptionMiddleware_unmappedExceptionPropagates() throws Exception {
    try (var web = new Web()) {
      web.install(new ExceptionHandler(Map.of(IllegalStateException.class, 400)));
      web.get("/unmapped", (_, _) -> {
        throw new RuntimeException("unmapped");
      });
      web.start(PORT);

      // RuntimeException isn't mapped; HTTPServer default handling returns 500
      HttpResponse<String> response = send("GET", "/unmapped");
      assertEquals(response.statusCode(), 500);
    }
  }

  static class NotFoundException extends RuntimeException {
    NotFoundException(String message) {
      super(message);
    }
  }
}
