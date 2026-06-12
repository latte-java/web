/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.json;

import module java.base;
import module java.net.http;
import module org.lattejava.json;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;
import org.lattejava.web.tests.json.internal.UserJSON;

import static org.testng.Assert.*;

public class JSONBodySupplierTest extends BaseWebTest {
  @Test
  public void jsonBodySupplier_emptyBody_skipsFunction() throws Exception {
    try (var web = new Web()) {
      web.post("/users", (_, res, user) -> {
        res.setStatus(200);
        res.setHeader("X-Body-Null", String.valueOf(user == null));
      }, JSONBodySupplier.of(UserJSON::fromJSON));
      web.start(PORT);

      HttpResponse<String> response = postJSON("");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Body-Null").orElse(null), "true");
    }
  }

  @Test
  public void jsonBodySupplier_ignoresUnknownFields_byDefault() throws Exception {
    // The json library is lenient by default (@JSON(strict = false)): unknown keys are ignored,
    // so the extra field does not cause a parse error.
    try (var web = new Web()) {
      web.post("/users", (_, res, _) -> res.setStatus(200), JSONBodySupplier.of(UserJSON::fromJSON));
      web.start(PORT);

      HttpResponse<String> response = postJSON("""
          {"name":"alice","age":30,"extra":true}
          """);

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test
  public void jsonBodySupplier_invalidJSON_returns400() throws Exception {
    try (var web = new Web()) {
      web.post("/users", (_, res, _) -> {
        // Handler should not be invoked
        res.setStatus(200);
        res.setHeader("X-Handler-Ran", "yes");
      }, JSONBodySupplier.of(UserJSON::fromJSON));
      web.start(PORT);

      HttpResponse<String> response = postJSON("{not valid json");
      assertEquals(response.statusCode(), 400);
      assertNull(response.headers().firstValue("X-Handler-Ran").orElse(null));
    }
  }

  @Test
  public void jsonBodySupplier_parsesValidJSON() throws Exception {
    try (var web = new Web()) {
      web.post("/users", (_, res, user) -> {
        res.setStatus(200);
        res.setHeader("X-Name", user.name);
        res.setHeader("X-Age", String.valueOf(user.age));
      }, JSONBodySupplier.of(UserJSON::fromJSON));
      web.start(PORT);

      HttpResponse<String> response = postJSON("""
          {"name":"alice","age":30}
          """);
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Name").orElse(null), "alice");
      assertEquals(response.headers().firstValue("X-Age").orElse(null), "30");
    }
  }

  @Test
  public void jsonBodySupplier_typeMismatch_returns400() throws Exception {
    // JSON is valid but doesn't match User's shape (array instead of object)
    try (var web = new Web()) {
      web.post("/users", (_, res, _) -> res.setStatus(200), JSONBodySupplier.of(UserJSON::fromJSON));
      web.start(PORT);

      HttpResponse<String> response = postJSON("[1, 2, 3]");
      assertEquals(response.statusCode(), 400);
    }
  }

  private HttpResponse<String> postJSON(String json) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(BASE_URL + "/users"))
                                     .header("Content-Type", "application/json")
                                     .method("POST", HttpRequest.BodyPublishers.ofString(json))
                                     .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  // Test type. The @JSON annotation processor generates the UserJSON companion in the
  // org.lattejava.web.tests.json.internal package at compile time.
  @JSON
  public static class User {
    public int age;
    public String name;

    public User() {
    }
  }
}
