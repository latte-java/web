/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.testng.Assert.*;

/**
 * Verifies that {@link HeaderTokenReader} reads the access token from {@code Authorization: Bearer} and the refresh
 * from {@code X-Refresh-Token}, and that {@link HeaderTokenWriter} writes response headers and does nothing on clear.
 *
 * @author Brian Pontarelli
 */
public class HeaderTransportTest extends BaseWebTest {
  @Test
  public void clearIsNoOp() throws Exception {
    var writer = new HeaderTokenWriter("X-Access-Token", "X-Refresh-Token");

    try (var web = new Web()) {
      web.get("/clear", (req, res) -> {
        writer.clear(req, res);
        res.setStatus(200);
      });
      web.start(PORT);

      HttpResponse<String> res = get("/clear", null);
      assertEquals(res.statusCode(), 200);
      // No Set-Cookie headers should appear
      assertTrue(res.headers().allValues("Set-Cookie").isEmpty(), "Expected no Set-Cookie on clear");
    }
  }

  @Test
  public void readsAccessTokenFromBearerHeader() throws Exception {
    var reader = new HeaderTokenReader("Authorization", "X-Refresh-Token");

    try (var web = new Web()) {
      web.get("/read", (req, res) -> {
        Tokens t = reader.read(req);
        res.setStatus(200);
        res.getWriter().write(
            (t.accessToken() != null ? t.accessToken() : "null") + ","
                + (t.refreshToken() != null ? t.refreshToken() : "null"));
      });
      web.start(PORT);

      try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/read"))
                                     .header("Authorization", "Bearer MY_ACCESS")
                                     .header("X-Refresh-Token", "MY_REFRESH")
                                     .GET()
                                     .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(res.statusCode(), 200);
        assertEquals(res.body(), "MY_ACCESS,MY_REFRESH");
      }
    }
  }

  @Test
  public void writesSetsResponseHeaders() throws Exception {
    var writer = new HeaderTokenWriter("X-Access-Token", "X-Refresh-Token");

    try (var web = new Web()) {
      web.get("/write", (req, res) -> {
        writer.write(req, res, new Tokens("NEW_ACCESS", "NEW_REFRESH", null, null));
        res.setStatus(200);
      });
      web.start(PORT);

      HttpResponse<String> res = get("/write", null);
      assertEquals(res.statusCode(), 200);
      assertEquals(res.headers().firstValue("X-Access-Token").orElse(null), "NEW_ACCESS");
      assertEquals(res.headers().firstValue("X-Refresh-Token").orElse(null), "NEW_REFRESH");
    }
  }
}
