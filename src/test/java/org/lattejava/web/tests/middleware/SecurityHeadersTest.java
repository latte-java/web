/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.middleware;

import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.testng.Assert.*;

public class SecurityHeadersTest extends BaseWebTest {
  private static void assertHeader(HttpResponse<?> response, String name, String expected) {
    assertEquals(response.headers().firstValue(name).orElse(null), expected, "Header [" + name + "] mismatch");
  }

  @Test
  public void builder_nullSuppressesHeader() throws Exception {
    try (var web = new Web()) {
      web.install(SecurityHeaders.builder()
                                 .strictTransportSecurity(null)
                                 .build());
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      assertFalse(response.headers().firstValue("Strict-Transport-Security").isPresent(),
          "Strict-Transport-Security should be suppressed");
      // Other headers still present
      assertHeader(response, "X-Frame-Options", "DENY");
    }
  }

  @Test
  public void builder_overridesHeaderValue() throws Exception {
    try (var web = new Web()) {
      web.install(SecurityHeaders.builder()
                                 .contentSecurityPolicy("default-src 'self'; script-src 'self' 'nonce-xyz'")
                                 .build());
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      assertHeader(response, "Content-Security-Policy", "default-src 'self'; script-src 'self' 'nonce-xyz'");
      // Other headers still at defaults
      assertHeader(response, "X-Frame-Options", "DENY");
    }
  }

  @Test
  public void defaults_emitsAllHeadersWithExpectedValues() throws Exception {
    try (var web = new Web()) {
      web.install(new SecurityHeaders());
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      assertEquals(response.statusCode(), 200);
      assertHeader(response, "Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload");
      assertHeader(response, "Content-Security-Policy",
          "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'; upgrade-insecure-requests");
      assertHeader(response, "X-Content-Type-Options", "nosniff");
      assertHeader(response, "X-Frame-Options", "DENY");
      assertHeader(response, "X-XSS-Protection", "0");
      assertHeader(response, "Referrer-Policy", "no-referrer");
      assertHeader(response, "Permissions-Policy",
          "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()");
      assertHeader(response, "Cross-Origin-Opener-Policy", "same-origin");
      assertHeader(response, "Cross-Origin-Embedder-Policy", "require-corp");
      assertHeader(response, "Cross-Origin-Resource-Policy", "same-origin");
    }
  }

  @Test
  public void handlerCanOverrideHeader() throws Exception {
    try (var web = new Web()) {
      web.install(new SecurityHeaders());
      web.get("/x", (_, res) -> {
        res.setHeader("X-Frame-Options", "SAMEORIGIN");
        res.setStatus(200);
      });
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      assertHeader(response, "X-Frame-Options", "SAMEORIGIN");
    }
  }

  @Test
  public void headersPresentOn404() throws Exception {
    try (var web = new Web()) {
      web.install(new SecurityHeaders());
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/nope");
      assertEquals(response.statusCode(), 404);
      assertHeader(response, "X-Frame-Options", "DENY");
      assertHeader(response, "Content-Security-Policy",
          "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'; upgrade-insecure-requests");
    }
  }
}
