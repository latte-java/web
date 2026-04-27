/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.middleware;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.testng.Assert.*;

public class OriginChecksTest extends BaseWebTest {
  @Test(expectedExceptions = IllegalArgumentException.class)
  public void constructor_rejectsEmptyList() {
    new OriginChecks(List.of());
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void constructor_rejectsURIWithFragment() {
    new OriginChecks(List.of(URI.create("https://example.com#frag")));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void constructor_rejectsURIWithPath() {
    new OriginChecks(List.of(URI.create("https://example.com/admin")));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void constructor_rejectsURIWithQuery() {
    new OriginChecks(List.of(URI.create("https://example.com?x=1")));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void constructor_rejectsURIWithUserInfo() {
    new OriginChecks(List.of(URI.create("https://user@example.com")));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void constructor_rejectsURIWithoutHost() {
    new OriginChecks(List.of(URI.create("https:///path")));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void constructor_rejectsURIWithoutScheme() {
    new OriginChecks(List.of(URI.create("//example.com")));
  }

  @Test
  public void delete_foreignOrigin_returns403() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks());
      web.delete("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("DELETE", "/x", "Origin", "https://evil.com");
      assertEquals(response.statusCode(), 403);
    }
  }

  @Test
  public void get_foreignOrigin_skipsCheck() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks(true));
      web.get("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("GET", "/x", "Origin", "https://evil.com");
      assertEquals(response.statusCode(), 201);
    }
  }

  @Test
  public void head_foreignOrigin_skipsCheck() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks(true));
      web.get("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("HEAD", "/x", "Origin", "https://evil.com");
      assertEquals(response.statusCode(), 201);
    }
  }

  @Test
  public void options_foreignOrigin_skipsCheck() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks(true));
      web.options("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("OPTIONS", "/x", "Origin", "https://evil.com");
      assertEquals(response.statusCode(), 201);
    }
  }

  @Test
  public void patch_foreignOrigin_returns403() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks());
      web.patch("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("PATCH", "/x", "Origin", "https://evil.com");
      assertEquals(response.statusCode(), 403);
    }
  }

  @Test
  public void post_autoDerive_withXForwardedHost_passesThrough() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks());
      web.post("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      // Proxy rewrites Host and scheme; Origin matches the proxy's public origin.
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(URI.create(BASE_URL + "/x"))
                                       .header("X-Forwarded-Host", "repo.lattejava.org")
                                       .header("X-Forwarded-Proto", "https")
                                       .header("Origin", "https://repo.lattejava.org")
                                       .method("POST", HttpRequest.BodyPublishers.noBody())
                                       .build();
      HttpResponse<String> response = HttpClient.newHttpClient()
                                                .send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(response.statusCode(), 201);
    }
  }

  @Test
  public void post_foreignOrigin_autoDerive_returns403() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks());
      web.post("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("POST", "/x", "Origin", "https://evil.com");
      assertEquals(response.statusCode(), 403);
    }
  }

  @Test
  public void post_foreignOrigin_explicitList_returns403() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks(List.of(URI.create("https://foo.example.com"))));
      web.post("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("POST", "/x", "Origin", "https://bar.example.com");
      assertEquals(response.statusCode(), 403);
    }
  }

  @Test
  public void post_malformedOrigin_returns403() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks());
      web.post("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("POST", "/x", "Origin", "not a url");
      assertEquals(response.statusCode(), 403);
    }
  }

  @Test
  public void post_matchingOrigin_autoDerive_passesThrough() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks());
      web.post("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("POST", "/x", "Origin", BASE_URL);
      assertEquals(response.statusCode(), 201);
    }
  }

  @Test
  public void post_matchingOrigin_explicitList_passesThrough() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks(List.of(
          URI.create("https://foo.example.com"),
          URI.create("https://bar.example.com"))));
      web.post("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> a = sendWithHeader("POST", "/x", "Origin", "https://foo.example.com");
      assertEquals(a.statusCode(), 201);

      HttpResponse<String> b = sendWithHeader("POST", "/x", "Origin", "https://bar.example.com");
      assertEquals(b.statusCode(), 201);
    }
  }

  @Test
  public void post_noOrigin_requireOriginFalse_passesThrough() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks());
      web.post("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/x");
      assertEquals(response.statusCode(), 201);
    }
  }

  @Test
  public void post_noOrigin_requireOriginTrue_returns403() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks(true));
      web.post("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = send("POST", "/x");
      assertEquals(response.statusCode(), 403);
    }
  }

  @Test
  public void post_normalization_caseAndTrailingSlashAndDefaultPort() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks(List.of(URI.create("https://example.com"))));
      web.post("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      // Trailing slash
      assertEquals(sendWithHeader("POST", "/x", "Origin", "https://example.com/").statusCode(), 201);
      // Uppercase scheme and host
      assertEquals(sendWithHeader("POST", "/x", "Origin", "HTTPS://EXAMPLE.COM").statusCode(), 201);
      // Explicit default port
      assertEquals(sendWithHeader("POST", "/x", "Origin", "https://example.com:443").statusCode(), 201);
    }
  }

  @Test
  public void post_originNullLiteral_returns403() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks());
      web.post("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("POST", "/x", "Origin", "null");
      assertEquals(response.statusCode(), 403);
    }
  }

  @Test
  public void put_foreignOrigin_returns403() throws Exception {
    try (var web = new Web()) {
      web.install(new OriginChecks());
      web.put("/x", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = sendWithHeader("PUT", "/x", "Origin", "https://evil.com");
      assertEquals(response.statusCode(), 403);
    }
  }

  private HttpResponse<String> sendWithHeader(String method, String path, String name, String value) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(BASE_URL + path))
                                     .header(name, value)
                                     .method(method, HttpRequest.BodyPublishers.noBody())
                                     .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
