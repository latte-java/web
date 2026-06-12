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
import org.lattejava.web.tests.*;

import static org.testng.Assert.*;

/**
 * Verifies the default browser cookie transport (the {@link TokenReader} / {@link TokenWriter} pair that
 * {@link BrowserSettings} resolves to): the reader pulls three tokens from the {@code access_token},
 * {@code refresh_token}, and {@code id_token} cookies, and the writer sets those three cookies and clears them with
 * Max-Age=0.
 *
 * @author Brian Pontarelli
 */
public class CookieTransportTest extends BaseWebTest {
  @Test
  public void clearDeletesThreeCookies() throws Exception {
    var writer = BrowserSettings.builder().build().tokenWriter();

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
    var writer = BrowserSettings.builder().build().tokenWriter();

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
    var reader = BrowserSettings.builder().build().tokenReader();

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
