/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import java.net.http.HttpResponse;

import org.lattejava.web.Middleware;
import org.lattejava.web.Web;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class MiddlewareTest extends BaseWebTest {

  @Test
  public void middleware_canModifyResponseAfterHandler() throws Exception {
    try (var web = new Web()) {
      web.install((req, res, chain) -> {
        chain.next(req, res);
        // After handler ran
        res.setHeader("X-Post-Handler", "yes");
      });
      web.get("/test", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/test");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Post-Handler").orElse(null), "yes");
    }
  }

  @Test
  public void middleware_executionOrder_globalThenPerRoute() throws Exception {
    try (var web = new Web()) {
      var order = new java.util.ArrayList<String>();
      web.install((req, res, chain) -> {
        order.add("g1");
        chain.next(req, res);
      });
      web.install((req, res, chain) -> {
        order.add("g2");
        chain.next(req, res);
      });
      web.get("/test",
          (_, res) -> {
            order.add("handler");
            res.setStatus(200);
          },
          (req, res, chain) -> {
            order.add("r1");
            chain.next(req, res);
          },
          (req, res, chain) -> {
            order.add("r2");
            chain.next(req, res);
          }
      );
      web.start(PORT);

      send("GET", "/test");
      assertEquals(order, java.util.List.of("g1", "g2", "r1", "r2", "handler"));
    }
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void middleware_installAfterStart_throws() {
    try (var web = new Web()) {
      web.start(PORT);
      web.install((req, res, chain) -> chain.next(req, res));
    }
  }

  @Test
  public void middleware_installedGlobal_runsForAllRoutes() throws Exception {
    try (var web = new Web()) {
      var order = new java.util.ArrayList<String>();
      web.install((req, res, chain) -> {
        order.add("global");
        chain.next(req, res);
      });
      web.get("/a", (_, res) -> {
        order.add("handler-a");
        res.setStatus(200);
      });
      web.get("/b", (_, res) -> {
        order.add("handler-b");
        res.setStatus(200);
      });
      web.start(PORT);

      send("GET", "/a");
      send("GET", "/b");

      assertEquals(order, java.util.List.of("global", "handler-a", "global", "handler-b"));
    }
  }

  @Test
  public void middleware_multipleInstallCalls_appendInOrder() throws Exception {
    try (var web = new Web()) {
      var order = new java.util.ArrayList<String>();
      web.install(
          (req, res, chain) -> {
            order.add("first");
            chain.next(req, res);
          }
      );
      web.install(
          (req, res, chain) -> {
            order.add("second");
            chain.next(req, res);
          },
          (req, res, chain) -> {
            order.add("third");
            chain.next(req, res);
          }
      );
      web.get("/test", (_, res) -> {
        order.add("handler");
        res.setStatus(200);
      });
      web.start(PORT);

      send("GET", "/test");
      assertEquals(order, java.util.List.of("first", "second", "third", "handler"));
    }
  }

  @Test
  public void middleware_noMiddlewares_stillWorks() throws Exception {
    try (var web = new Web()) {
      web.get("/test", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/test");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void middleware_nullEntry_throws() {
    new Web().install((Middleware) null);
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void middleware_nullRouteMiddleware_throws() {
    new Web().get("/test", (_, _) -> {
    }, (Middleware) null);
  }

  @Test
  public void middleware_perRoute_runsOnlyForThatRoute() throws Exception {
    try (var web = new Web()) {
      var order = new java.util.ArrayList<String>();
      Middleware m = (req, res, chain) -> {
        order.add("m");
        chain.next(req, res);
      };
      web.get("/a", (_, res) -> {
        order.add("handler-a");
        res.setStatus(200);
      }, m);
      web.get("/b", (_, res) -> {
        order.add("handler-b");
        res.setStatus(200);
      });
      web.start(PORT);

      send("GET", "/a");
      send("GET", "/b");

      assertEquals(order, java.util.List.of("m", "handler-a", "handler-b"));
    }
  }

  @Test
  public void middleware_seesPathParameters() throws Exception {
    try (var web = new Web()) {
      var captured = new java.util.ArrayList<String>();
      web.install((req, res, chain) -> {
        captured.add((String) req.getAttribute("id"));
        chain.next(req, res);
      });
      web.get("/users/{id}", (_, res) -> res.setStatus(200));
      web.start(PORT);

      send("GET", "/users/42");
      assertEquals(captured, java.util.List.of("42"));
    }
  }

  @Test
  public void middleware_shortCircuits_whenNextNotCalled() throws Exception {
    try (var web = new Web()) {
      web.install((_, res, _) -> {
        res.setStatus(401);
        // Intentionally does not call chain.next()
      });
      web.get("/secured", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/secured");
      assertEquals(response.statusCode(), 401);
    }
  }
}
