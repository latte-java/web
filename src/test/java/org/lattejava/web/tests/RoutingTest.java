/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import java.net.http.HttpResponse;
import java.util.List;

import org.lattejava.web.Web;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class RoutingTest extends BaseWebTest {

  @Test
  public void route_matchesMethod() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("GET"), "/test", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/test");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void route_wrongMethod_returns405() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("GET"), "/test", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/test");
      assertEquals(response.statusCode(), 405);
      assertEquals(response.headers().firstValue("Allow").orElse(""), "GET");
    }
  }

  @Test
  public void route_noPathMatch_returns404() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("GET"), "/test", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/other");
      assertEquals(response.statusCode(), 404);
    }
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

  @Test
  public void route_literalSegmentMatchesEncodedRequestPath() throws Exception {
    // A request with percent-encoded bytes in a literal position does NOT match a route
    // that doesn't have the same encoded form in its pathSpec — because no implicit decoding occurs.
    try (var web = new Web()) {
      web.get("/hello", (_, res) -> res.setStatus(200));
      web.start(PORT);

      // Request /hello%20 — the trailing %20 is part of the segment, path becomes "hello%20" != "hello"
      HttpResponse<String> response = send("GET", "/hello%20");
      assertEquals(response.statusCode(), 404);
    }
  }

  @Test
  public void route_405_aggregatesAllowedMethods() throws Exception {
    try (var web = new Web()) {
      web.get("/resource", (_, res) -> res.setStatus(200));
      web.put("/resource", (_, res) -> res.setStatus(200));
      web.delete("/resource", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/resource");
      assertEquals(response.statusCode(), 405);
      assertEquals(response.headers().firstValue("Allow").orElse(""), "GET, PUT, DELETE");
    }
  }

  @Test
  public void route_405_hasEmptyBody() throws Exception {
    try (var web = new Web()) {
      web.get("/test", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/test");
      assertEquals(response.statusCode(), 405);
      assertEquals(response.body(), "");
      assertEquals(response.headers().firstValue("Content-Length").orElse(null), "0");
    }
  }

  @Test
  public void route_404_hasEmptyBody() throws Exception {
    try (var web = new Web()) {
      web.get("/exists", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/missing");
      assertEquals(response.statusCode(), 404);
      assertEquals(response.body(), "");
      assertEquals(response.headers().firstValue("Content-Length").orElse(null), "0");
    }
  }

  @Test
  public void route_multipleMethodsOnSameRoute() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("GET", "POST"), "/form", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response1 = send("GET", "/form");
      assertEquals(response1.statusCode(), 200);

      HttpResponse<String> response2 = send("POST", "/form");
      assertEquals(response2.statusCode(), 200);
    }
  }

  @Test
  public void route_literalBeatsParam() throws Exception {
    try (var web = new Web()) {
      web.get("/users/{id}", (_, res) -> {
        res.setStatus(200);
        res.setHeader("X-Match", "param");
      });
      web.get("/users/new", (_, res) -> {
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
      web.get("/users/new", (_, res) -> {
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
      web.get("/users/new", (_, res) -> res.setStatus(200));
      web.delete("/users/{id}", (_, res) -> res.setStatus(204));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/users/new");
      assertEquals(response.statusCode(), 405);
      String allow = response.headers().firstValue("Allow").orElse("");
      // Must contain both methods — order is static-first, param-second
      assertEquals(allow, "GET, DELETE");
    }
  }

  @Test
  public void route_backtrackingFromStaticDeadEnd() throws Exception {
    try (var web = new Web()) {
      web.get("/users/{id}/posts", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-Id", (String) req.getAttribute("id"));
      });
      web.get("/users/new/profile", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/users/new/posts");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Id").orElse(""), "new");
    }
  }

  @Test
  public void route_rootPath() throws Exception {
    try (var web = new Web()) {
      web.get("/", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void route_consecutiveSlashes() throws Exception {
    // RFC 3986 allows empty segments: /foo//bar has three segments [foo, "", bar]
    try (var web = new Web()) {
      web.get("/foo//bar", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/foo//bar");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void route_consecutiveSlashes_doNotMatchNormalizedPath() throws Exception {
    // Verify /foo//bar and /foo/bar are distinct routes (no implicit normalization)
    try (var web = new Web()) {
      web.get("/foo//bar", (_, res) -> res.setStatus(200));
      web.start(PORT);

      // Request /foo/bar should NOT match /foo//bar
      HttpResponse<String> response = send("GET", "/foo/bar");
      assertEquals(response.statusCode(), 404);
    }
  }

  @Test
  public void route_trailingSlashDistinct_bothRegistered() throws Exception {
    try (var web = new Web()) {
      web.get("/foo", (_, res) -> {
        res.setStatus(200);
        res.setHeader("X-Match", "no-slash");
      });
      web.get("/foo/", (_, res) -> {
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
  public void route_trailingSlashDistinct_withSlashMatches() throws Exception {
    try (var web = new Web()) {
      web.get("/foo/", (_, res) -> {
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
      web.get("/foo/", (_, res) -> res.setStatus(200));
      web.start(PORT);

      // Only /foo/ is registered; /foo should return 404
      HttpResponse<String> response = send("GET", "/foo");
      assertEquals(response.statusCode(), 404);
    }
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
      web.get("/path$with&sub-delims", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/path$with&sub-delims");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void route_duplicateMethodInList_isDeduplicated() throws Exception {
    // Passing duplicate methods should be silently deduplicated to a single registration.
    try (var web = new Web()) {
      web.route(java.util.List.of("GET", "GET", "get"), "/test", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/test");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void route_methodsAreUpperCased() throws Exception {
    try (var web = new Web()) {
      web.route(List.of("get"), "/test", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/test");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void get() throws Exception {
    try (var web = new Web()) {
      web.get("/test", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/test");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void post() throws Exception {
    try (var web = new Web()) {
      web.post("/test", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/test");
      assertEquals(response.statusCode(), 201);
    }
  }

  @Test
  public void put() throws Exception {
    try (var web = new Web()) {
      web.put("/test", (_, res) -> res.setStatus(202));
      web.start(PORT);

      HttpResponse<String> response = send("PUT", "/test");
      assertEquals(response.statusCode(), 202);
    }
  }

  @Test
  public void delete() throws Exception {
    try (var web = new Web()) {
      web.delete("/test", (_, res) -> res.setStatus(204));
      web.start(PORT);

      HttpResponse<String> response = send("DELETE", "/test");
      assertEquals(response.statusCode(), 204);
    }
  }

  @Test
  public void patch() throws Exception {
    try (var web = new Web()) {
      web.patch("/test", (_, res) -> res.setStatus(203));
      web.start(PORT);

      HttpResponse<String> response = send("PATCH", "/test");
      assertEquals(response.statusCode(), 203);
    }
  }

  @Test
  public void head() throws Exception {
    try (var web = new Web()) {
      web.head("/test", (_, res) -> res.setStatus(205));
      web.start(PORT);

      HttpResponse<String> response = send("HEAD", "/test");
      assertEquals(response.statusCode(), 205);
    }
  }

  @Test
  public void options() throws Exception {
    try (var web = new Web()) {
      web.options("/test", (_, res) -> res.setStatus(206));
      web.start(PORT);

      HttpResponse<String> response = send("OPTIONS", "/test");
      assertEquals(response.statusCode(), 206);
    }
  }

  @Test
  public void prefix() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", r -> r.get("/users", (_, res) -> res.setStatus(200)));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/api/users");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void prefix_nested() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", r -> r.prefix("/admin", r2 -> r2.get("/stats", (_, res) -> res.setStatus(200))));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/api/admin/stats");
      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void prefix_chaining() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", r -> r.get("/users", (_, res) -> res.setStatus(200))).get("/health", (_, res) -> res.setStatus(204));
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
  public void prefix_withPathParams() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", r -> r.get("/users/{id}", (req, res) -> {
        res.setStatus(200);
        res.setHeader("X-User-Id", (String) req.getAttribute("id"));
      }));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/api/users/42");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-User-Id").orElse(""), "42");
    }
  }
}
