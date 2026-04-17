/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class WebTest {
  private static final int PORT = 8080;
  private static final String BASE_URL = "http://localhost:" + PORT;

  @Test
  public void childPrefix_afterParentStart_throws() throws Exception {
    try (var web = new Web()) {
      var captured = new Web[1];
      web.prefix("/api", r -> captured[0] = r);
      web.start(PORT);

      try {
        captured[0].get("/users", (req, res) -> res.setStatus(200));
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  // -------------------------------------------------------------------------
  // Category A: Static exception tests — no server needed
  // -------------------------------------------------------------------------

  @Test
  public void delete() throws Exception {
    try (var web = new Web()) {
      web.delete("/test", (req, res) -> res.setStatus(204));
      web.start(PORT);

      HttpResponse<String> response = send("DELETE", "/test");
      assertEquals(response.statusCode(), 204);
    }
  }

  @Test
  public void get() throws Exception {
    try (var web = new Web()) {
      web.get("/test", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/test");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void head() throws Exception {
    try (var web = new Web()) {
      web.head("/test", (req, res) -> res.setStatus(205));
      web.start(PORT);

      HttpResponse<String> response = send("HEAD", "/test");
      assertEquals(response.statusCode(), 205);
    }
  }

  @Test
  public void options() throws Exception {
    try (var web = new Web()) {
      web.options("/test", (req, res) -> res.setStatus(206));
      web.start(PORT);

      HttpResponse<String> response = send("OPTIONS", "/test");
      assertEquals(response.statusCode(), 206);
    }
  }

  @Test
  public void patch() throws Exception {
    try (var web = new Web()) {
      web.patch("/test", (req, res) -> res.setStatus(203));
      web.start(PORT);

      HttpResponse<String> response = send("PATCH", "/test");
      assertEquals(response.statusCode(), 203);
    }
  }

  @Test
  public void post() throws Exception {
    try (var web = new Web()) {
      web.post("/test", (req, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/test");
      assertEquals(response.statusCode(), 201);
    }
  }

  @Test
  public void prefix() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", r -> {
        r.get("/users", (req, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/api/users");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void prefix_afterStart_throws() throws Exception {
    try (var web = new Web()) {
      web.start(PORT);

      try {
        web.prefix("/api", r -> r.get("/users", (req, res) -> res.setStatus(200)));
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test
  public void prefix_chaining() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", r -> {
        r.get("/users", (req, res) -> res.setStatus(200));
      }).get("/health", (req, res) -> res.setStatus(204));
      web.start(PORT);

      // Verify the prefixed route
      HttpResponse<String> response1 = send("GET", "/api/users");
      assertEquals(response1.statusCode(), 200);

      // Verify the chained route is NOT prefixed
      HttpResponse<String> response2 = send("GET", "/health");
      assertEquals(response2.statusCode(), 204);
    }
  }

  @Test
  public void prefix_nested() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", r -> {
        r.prefix("/admin", r2 -> {
          r2.get("/stats", (req, res) -> res.setStatus(200));
        });
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/api/admin/stats");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void prefix_withPathParams() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", r -> {
        r.get("/users/{id}", (req, res) -> {
          res.setStatus(200);
          res.setHeader("X-User-Id", (String) req.getAttribute("id"));
        });
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/api/users/42");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-User-Id").orElse(""), "42");
    }
  }

  @Test
  public void put() throws Exception {
    try (var web = new Web()) {
      web.put("/test", (req, res) -> res.setStatus(202));
      web.start(PORT);

      HttpResponse<String> response = send("PUT", "/test");
      assertEquals(response.statusCode(), 202);
    }
  }

  @Test
  public void route_405_aggregatesAllowedMethods() throws Exception {
    try (var web = new Web()) {
      web.get("/resource", (req, res) -> res.setStatus(200));
      web.put("/resource", (req, res) -> res.setStatus(200));
      web.delete("/resource", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/resource");
      assertEquals(response.statusCode(), 405);
      assertEquals(response.headers().firstValue("Allow").orElse(""), "GET, PUT, DELETE");
    }
  }

  @Test
  public void route_404_hasEmptyBody() throws Exception {
    try (var web = new Web()) {
      web.get("/exists", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/missing");
      assertEquals(response.statusCode(), 404);
      assertEquals(response.body(), "");
      assertEquals(response.headers().firstValue("Content-Length").orElse(null), "0");
    }
  }

  @Test
  public void route_405_hasEmptyBody() throws Exception {
    try (var web = new Web()) {
      web.get("/test", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/test");
      assertEquals(response.statusCode(), 405);
      assertEquals(response.body(), "");
      assertEquals(response.headers().firstValue("Content-Length").orElse(null), "0");
    }
  }

  @Test
  public void route_afterStart_throws() throws Exception {
    try (var web = new Web()) {
      web.get("/before", (req, res) -> res.setStatus(200));
      web.start(PORT);

      try {
        web.get("/after", (req, res) -> res.setStatus(200));
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }

      try {
        web.route(List.of("GET"), "/another", (req, res) -> res.setStatus(200));
        fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  @Test
  public void route_backtrackingFromStaticDeadEnd() throws Exception {
    try (var web = new Web()) {
      web.get("/users/{id}/posts", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-Id", (String) req.getAttribute("id"));
      });
      web.get("/users/new/profile", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/users/new/posts");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Id").orElse(""), "new");
    }
  }

  // -------------------------------------------------------------------------
  // Category B: Server-lifecycle tests — use PORT, no HTTP calls
  // -------------------------------------------------------------------------

  @Test
  public void route_conflictingParamNames_throws() {
    var web = new Web();
    web.get("/users/{id}", (req, res) -> res.setStatus(200));
    try {
      web.get("/users/{userId}", (req, res) -> res.setStatus(200));
      fail("Expected IllegalStateException");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  @Test
  public void route_consecutiveSlashes() throws Exception {
    // RFC 3986 allows empty segments: /foo//bar has three segments [foo, "", bar]
    try (var web = new Web()) {
      web.get("/foo//bar", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/foo//bar");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void route_consecutiveSlashes_doNotMatchNormalizedPath() throws Exception {
    // Verify /foo//bar and /foo/bar are distinct routes (no implicit normalization)
    try (var web = new Web()) {
      web.get("/foo//bar", (req, res) -> res.setStatus(200));
      web.start(PORT);

      // Request /foo/bar should NOT match /foo//bar
      HttpResponse<String> response = send("GET", "/foo/bar");
      assertEquals(response.statusCode(), 404);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_duplicateParamNames_throws() {
    new Web().get("/users/{id}/posts/{id}", (req, res) -> {
    });
  }

  // -------------------------------------------------------------------------
  // Category C: HTTP integration tests
  // -------------------------------------------------------------------------

  @Test(expectedExceptions = IllegalStateException.class)
  public void route_duplicateRegistration_throws() {
    var web = new Web();
    web.get("/test", (req, res) -> res.setStatus(200));
    web.get("/test", (req, res) -> res.setStatus(201));
  }

  @Test
  public void route_duplicateMethodInList_isDeduplicated() throws Exception {
    // Passing duplicate methods should be silently deduplicated to a single registration.
    try (var web = new Web()) {
      web.route(java.util.List.of("GET", "GET", "get"), "/test", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/test");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_emptyMethods_throws() {
    new Web().route(List.of(), "/test", (req, res) -> res.setStatus(200));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_nullMethodEntry_throws() {
    var list = new java.util.ArrayList<String>();
    list.add(null);
    new Web().route(list, "/test", (req, res) -> {});
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_blankMethodEntry_throws() {
    new Web().route(java.util.List.of("  "), "/test", (req, res) -> {});
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_methodWithWhitespace_throws() {
    new Web().route(java.util.List.of("GE T"), "/test", (req, res) -> {});
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_methodWithInvalidChar_throws() {
    new Web().route(java.util.List.of("GET!"), "/test", (req, res) -> {});
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_methodWithDigits_throws() {
    new Web().route(java.util.List.of("GET1"), "/test", (req, res) -> {});
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_emptyParamName_throws() {
    new Web().get("/users/{}", (req, res) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_emptyPath_throws() {
    new Web().get("", (req, res) -> {
    });
  }

  @Test
  public void route_literalBeatsParam() throws Exception {
    try (var web = new Web()) {
      web.get("/users/{id}", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-Match", "param");
      });
      web.get("/users/new", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-Match", "literal");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/users/new");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Match").orElse(""), "literal");
    }
  }

  @Test
  public void route_literalMethodMismatch_fallsThroughToParam() throws Exception {
    // Static path matches but method doesn't; param path matches with correct method
    try (var web = new Web()) {
      web.get("/users/new", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-Match", "literal");
      });
      web.post("/users/{id}", (req, res) -> {
        res.setStatus(201);
        res.setHeader("X-Match", "param");
        res.setHeader("X-Id", (String) req.getAttribute("id"));
      });
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/users/new");
      assertEquals(response.statusCode(), 201);
      assertEquals(response.headers().firstValue("X-Match").orElse(null), "param");
      assertEquals(response.headers().firstValue("X-Id").orElse(null), "new");
    }
  }

  @Test
  public void route_bothBranchesMethodMismatch_aggregatesAllow() throws Exception {
    // Static has GET, param has DELETE; requesting POST should return 405 with both
    try (var web = new Web()) {
      web.get("/users/new", (req, res) -> res.setStatus(200));
      web.delete("/users/{id}", (req, res) -> res.setStatus(204));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/users/new");
      assertEquals(response.statusCode(), 405);
      String allow = response.headers().firstValue("Allow").orElse("");
      // Must contain both methods — order is static-first, param-second
      assertEquals(allow, "GET, DELETE");
    }
  }

  @Test
  public void route_matchesMethod() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("GET"), "/test", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/test");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void route_methodsAreUpperCased() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("get"), "/test", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/test");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_mixedLiteralAndParam_throws() {
    new Web().get("/users/foo{id}", (req, res) -> {
    });
  }

  @Test
  public void route_multipleMethodsOnSameRoute() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("GET", "POST"), "/form", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response1 = send("GET", "/form");
      assertEquals(response1.statusCode(), 200);

      HttpResponse<String> response2 = send("POST", "/form");
      assertEquals(response2.statusCode(), 200);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_noLeadingSlash_throws() {
    new Web().get("users", (req, res) -> {
    });
  }

  @Test
  public void route_noPathMatch_returns404() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("GET"), "/test", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/other");
      assertEquals(response.statusCode(), 404);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_paramNameStartsWithDigit_throws() {
    new Web().get("/users/{1abc}", (req, res) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_paramNameWithHyphen_throws() {
    new Web().get("/users/{my-id}", (req, res) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_paramNameWithSpace_throws() {
    new Web().get("/users/{ id }", (req, res) -> {
    });
  }

  @Test
  public void route_pathParameter() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("GET"), "/users/{id}", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-User-Id", (String) req.getAttribute("id"));
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/users/42");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-User-Id").orElse(""), "42");
    }
  }

  @Test
  public void route_pathParameter_capturesEncodedValueVerbatim() throws Exception {
    // Client sends percent-encoded segment; framework captures it as-is without decoding.
    // Decoding is the application's responsibility.
    try (var web = new Web()) {
      web.get("/users/{id}", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-Captured-Id", (String) req.getAttribute("id"));
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/users/john%20doe");
      assertEquals(response.statusCode(), 200);
      // Raw encoded form, NOT decoded to "john doe"
      assertEquals(response.headers().firstValue("X-Captured-Id").orElse(null), "john%20doe");
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_percentInPathSpec_throws() {
    // The PathParser rejects '%' in pathSpec (our allowed char set is RFC 3986 pchar minus '%').
    new Web().get("/emoji/%F0%9F%98%80", (req, res) -> {});
  }

  @Test
  public void route_literalSegmentMatchesEncodedRequestPath() throws Exception {
    // A request with percent-encoded bytes in a literal position does NOT match a route
    // that doesn't have the same encoded form in its pathSpec — because no implicit decoding occurs.
    try (var web = new Web()) {
      web.get("/hello", (req, res) -> res.setStatus(200));
      web.start(PORT);

      // Request /hello%20 — the trailing %20 is part of the segment, path becomes "hello%20" != "hello"
      HttpResponse<String> response = send("GET", "/hello%20");
      assertEquals(response.statusCode(), 404);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_pathWithControlChar_throws() {
    new Web().get("/hello\tworld", (req, res) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_pathWithSpace_throws() {
    new Web().get("/hello world", (req, res) -> {
    });
  }

  @Test
  public void route_rootPath() throws Exception {
    try (var web = new Web()) {
      web.get("/", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_unclosedParam_throws() {
    new Web().get("/users/{id", (req, res) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_unopenedParam_throws() {
    new Web().get("/users/id}", (req, res) -> {
    });
  }

  @Test
  public void route_validComplexPath_succeeds() throws Exception {
    try (var web = new Web()) {
      web.get("/api-v1/~bob/posts/{postId}/comments/{commentId}", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-Post", (String) req.getAttribute("postId"));
        res.setHeader("X-Comment", (String) req.getAttribute("commentId"));
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/api-v1/~bob/posts/42/comments/99");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Post").orElse(""), "42");
      assertEquals(response.headers().firstValue("X-Comment").orElse(""), "99");
    }
  }

  @Test
  public void route_validSubDelims_succeeds() throws Exception {
    try (var web = new Web()) {
      web.get("/path$with&sub-delims", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/path$with&sub-delims");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void route_wrongMethod_returns405() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("GET"), "/test", (req, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/test");
      assertEquals(response.statusCode(), 405);
      assertEquals(response.headers().firstValue("Allow").orElse(""), "GET");
    }
  }

  @Test
  public void start_calledTwice_throws() throws Exception {
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
  public void start_portInUse_serverSilentlyFailsAndStartedRemainsTrue() throws Exception {
    // HTTPServer.start() swallows BindException internally and returns normally (does not throw).
    // As a result, Web.start() completes without error and started=true even though the underlying
    // listener never bound. This test documents that behaviour: route registration is locked after
    // the call returns, and a second call to start() throws the "already started" guard.
    try (var blocker = new Web()) {
      blocker.get("/b", (req, res) -> res.setStatus(200));
      blocker.start(PORT);

      try (var web = new Web()) {
        web.get("/a", (req, res) -> res.setStatus(200));

        // HTTPServer.start() does NOT throw on port conflict — it logs and returns.
        // Therefore Web.start() returns normally with started=true.
        web.start(PORT);

        // started=true: route registration is now locked
        try {
          web.get("/c", (req, res) -> res.setStatus(200));
          fail("Expected IllegalStateException — started flag should be true");
        } catch (IllegalStateException expected) {
          // expected
        }

        // started=true: a second call to start() throws the "already started" guard
        try {
          web.start(PORT);
          fail("Expected IllegalStateException — already started");
        } catch (IllegalStateException expected) {
          // expected
        }
      }
    }
  }

  @Test
  public void route_trailingSlashDistinct_withSlashMatches() throws Exception {
    try (var web = new Web()) {
      web.get("/foo/", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-Match", "with-slash");
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/foo/");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Match").orElse(null), "with-slash");
    }
  }

  @Test
  public void route_trailingSlashDistinct_withoutSlashReturns404() throws Exception {
    try (var web = new Web()) {
      web.get("/foo/", (req, res) -> res.setStatus(200));
      web.start(PORT);

      // Only /foo/ is registered; /foo should return 404
      HttpResponse<String> response = send("GET", "/foo");
      assertEquals(response.statusCode(), 404);
    }
  }

  @Test
  public void route_trailingSlashDistinct_bothRegistered() throws Exception {
    try (var web = new Web()) {
      web.get("/foo", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-Match", "no-slash");
      });
      web.get("/foo/", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-Match", "with-slash");
      });
      web.start(PORT);

      HttpResponse<String> response1 = send("GET", "/foo");
      assertEquals(response1.statusCode(), 200);
      assertEquals(response1.headers().firstValue("X-Match").orElse(null), "no-slash");

      HttpResponse<String> response2 = send("GET", "/foo/");
      assertEquals(response2.statusCode(), 200);
      assertEquals(response2.headers().firstValue("X-Match").orElse(null), "with-slash");
    }
  }

  @Test
  public void close_calledTwice_isIdempotent() throws Exception {
    var web = new Web();
    web.get("/test", (req, res) -> res.setStatus(200));
    web.start(PORT);
    web.close();
    web.close();  // Should not throw
  }

  @Test
  public void close_beforeStart_isSafe() {
    var web = new Web();
    web.close();  // Should not throw — server was never started
  }

  private HttpResponse<String> send(String method, String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(BASE_URL + path))
                                     .method(method, HttpRequest.BodyPublishers.noBody())
                                     .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
