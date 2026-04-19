/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import module com.fasterxml.jackson.databind;
import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;

import org.testng.annotations.*;

import static org.testng.Assert.*;

public class JSONBodySupplierTest extends BaseWebTest {
  @Test
  public void jsonBodySupplier_customObjectMapper_isUsed() throws Exception {
    // Verify the ObjectMapper constructor overload works by passing a mapper configured to
    // ignore unknown fields.
    var mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    try (var web = new Web()) {
      web.post("/users", (_, res, user) -> {
        res.setStatus(200);
        res.setHeader("X-Name", user.name);
      }, JSONBodySupplier.of(User.class, mapper));
      web.start(PORT);

      HttpResponse<String> response = postJSON("""
          {"name":"alice","age":30,"extra":true}
          """);
      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Name").orElse(null), "alice");
    }
  }

  @Test
  public void jsonBodySupplier_emptyBody_returns400() throws Exception {
    try (var web = new Web()) {
      web.post("/users", (_, res, _) -> {
        res.setStatus(200);
      }, JSONBodySupplier.of(User.class));
      web.start(PORT);

      HttpResponse<String> response = postJSON("");
      assertEquals(response.statusCode(), 400);
    }
  }

  @Test
  public void jsonBodySupplier_ignoresUnknownFields_byDefault() throws Exception {
    // Jackson by default FAILS on unknown properties. Verify our documented behavior:
    // with a default ObjectMapper, unknown fields cause a parse error (400).
    try (var web = new Web()) {
      web.post("/users", (req, res, user) -> {
        res.setStatus(200);
      }, JSONBodySupplier.of(User.class));
      web.start(PORT);

      HttpResponse<String> response = postJSON("""
          {"name":"alice","age":30,"extra":true}
          """);

      // Jackson's default is to fail on unknown properties → 400.
      assertEquals(response.statusCode(), 400);
    }
  }

  @Test
  public void jsonBodySupplier_invalidJSON_returns400() throws Exception {
    try (var web = new Web()) {
      web.post("/users", (_, res, _) -> {
        // Handler should not be invoked
        res.setStatus(200);
        res.setHeader("X-Handler-Ran", "yes");
      }, JSONBodySupplier.of(User.class));
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
      }, JSONBodySupplier.of(User.class));
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
      web.post("/users", (_, res, _) -> {
        res.setStatus(200);
      }, JSONBodySupplier.of(User.class));
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

  // Test type. Public class with public fields + no-arg constructor; Jackson uses reflection,
  // which works because the test module-info opens this package to Jackson.
  public static class User {
    public int age;
    public String name;

    public User() {
    }
  }
}
