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
  public void csp_stripsUpgradeInsecureRequestsForLocalhost() throws Exception {
    try (var web = new Web()) {
      web.install(new SecurityHeaders());
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      String csp = response.headers().firstValue("Content-Security-Policy").orElse(null);
      assertNotNull(csp, "Content-Security-Policy should be set");
      assertFalse(csp.contains("upgrade-insecure-requests"),
          "CSP should not contain upgrade-insecure-requests when host is [localhost]: [" + csp + "]");
      assertTrue(csp.contains("default-src 'self'"),
          "Rest of CSP should be intact: [" + csp + "]");
    }
  }

  @Test
  public void csp_stripsUpgradeInsecureRequestsForLoopbackIP() throws Exception {
    try (var web = new Web()) {
      web.install(new SecurityHeaders());
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(URI.create("http://127.0.0.1:" + PORT + "/x"))
                                       .GET()
                                       .build();
      HttpResponse<String> response = HttpClient.newHttpClient()
                                                .send(request, HttpResponse.BodyHandlers.ofString());
      String csp = response.headers().firstValue("Content-Security-Policy").orElse(null);
      assertNotNull(csp, "Content-Security-Policy should be set");
      assertFalse(csp.contains("upgrade-insecure-requests"),
          "CSP should not contain upgrade-insecure-requests when host is [127.0.0.1]: [" + csp + "]");
      assertTrue(csp.contains("default-src 'self'"),
          "Rest of CSP should be intact: [" + csp + "]");
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
          "default-src 'self'; style-src 'self' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
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
          "default-src 'self'; style-src 'self' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'");
    }
  }

  @Test
  public void upstreamMiddlewareHeaderIsKept() throws Exception {
    try (var web = new Web()) {
      web.install((req, res, chain) -> {
        res.setHeader("Content-Security-Policy", "default-src 'none'");
        res.setHeader("Cross-Origin-Embedder-Policy", "unsafe-none");
        res.setHeader("Cross-Origin-Opener-Policy", "unsafe-none");
        res.setHeader("Cross-Origin-Resource-Policy", "cross-origin");
        res.setHeader("Permissions-Policy", "geolocation=(self)");
        res.setHeader("Referrer-Policy", "origin");
        res.setHeader("Strict-Transport-Security", "max-age=0");
        res.setHeader("X-Content-Type-Options", "foo");
        res.setHeader("X-Frame-Options", "SAMEORIGIN");
        res.setHeader("X-XSS-Protection", "1");
        chain.next(req, res);
      });
      web.install(new SecurityHeaders());
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/x");
      assertHeader(response, "Content-Security-Policy", "default-src 'none'");
      assertHeader(response, "Cross-Origin-Embedder-Policy", "unsafe-none");
      assertHeader(response, "Cross-Origin-Opener-Policy", "unsafe-none");
      assertHeader(response, "Cross-Origin-Resource-Policy", "cross-origin");
      assertHeader(response, "Permissions-Policy", "geolocation=(self)");
      assertHeader(response, "Referrer-Policy", "origin");
      assertHeader(response, "Strict-Transport-Security", "max-age=0");
      assertHeader(response, "X-Content-Type-Options", "foo");
      assertHeader(response, "X-Frame-Options", "SAMEORIGIN");
      assertHeader(response, "X-XSS-Protection", "1");
    }
  }
}
