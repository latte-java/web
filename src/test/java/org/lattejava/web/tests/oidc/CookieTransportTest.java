/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.oidc.Tokens;
import org.lattejava.web.oidc.internal.CookieTokenReader;
import org.lattejava.web.oidc.internal.CookieTokenWriter;
import org.lattejava.web.tests.*;

import static org.testng.Assert.*;

/**
 * Verifies that {@link CookieTokenReader} correctly reads three tokens from cookies and that {@link CookieTokenWriter}
 * writes three cookies with the right names and clears them with Max-Age=0.
 *
 * @author Brian Pontarelli
 */
public class CookieTransportTest extends BaseWebTest {
  @Test
  public void clearDeletesThreeCookies() throws Exception {
    var writer = new CookieTokenWriter("access_token", "refresh_token", "id_token", Duration.ofDays(30));

    try (var web = new Web()) {
      web.get("/clear", (req, res) -> {
        writer.clear(req, res);
        res.setStatus(200);
      });
      web.start(PORT);

      HttpResponse<String> res = get("/clear", null);
      assertEquals(res.statusCode(), 200);

      for (String name : List.of("access_token", "refresh_token", "id_token")) {
        Cookie c = getCookie(res, name);
        assertNotNull(c, "Expected cleared cookie for [" + name + "]");
        assertEquals(c.getMaxAge(), Long.valueOf(0L), "Expected Max-Age=0 for [" + name + "]");
      }
    }
  }

  @Test
  public void writesSetsThreeCookies() throws Exception {
    var writer = new CookieTokenWriter("access_token", "refresh_token", "id_token", Duration.ofSeconds(900));

    try (var web = new Web()) {
      web.get("/write", (req, res) -> {
        writer.write(req, res, new Tokens("AT", "RT", "IT", 900L));
        res.setStatus(200);
      });
      web.start(PORT);

      HttpResponse<String> res = get("/write", null);
      assertEquals(res.statusCode(), 200);

      for (String[] pair : new String[][]{{"access_token", "AT"}, {"id_token", "IT"}, {"refresh_token", "RT"}}) {
        Cookie c = getCookie(res, pair[0]);
        assertNotNull(c, "Expected Set-Cookie for [" + pair[0] + "]");
        assertEquals(c.getValue(), pair[1], "Expected value [" + pair[1] + "] for cookie [" + pair[0] + "]");
      }
    }
  }

  @Test
  public void readsThreeTokensFromCookies() throws Exception {
    var reader = new CookieTokenReader("access_token", "refresh_token", "id_token");

    try (var web = new Web()) {
      web.get("/read", (req, res) -> {
        Tokens t = reader.read(req);
        res.setStatus(200);
        res.getWriter().write(t.accessToken() + "," + t.refreshToken() + "," + t.idToken());
      });
      web.start(PORT);

      HttpResponse<String> res = get("/read", "access_token=AT; refresh_token=RT; id_token=IT");
      assertEquals(res.statusCode(), 200);
      assertEquals(res.body(), "AT,RT,IT");
    }
  }
}
