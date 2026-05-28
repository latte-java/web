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

import org.lattejava.web.tests.*;

import static org.testng.Assert.*;

/**
 * Verifies the SSR redirect challenge: unauthenticated without the marker writes a meta-refresh interstitial; with the
 * marker it redirects to the login path with a return-to cookie. Custom forbiddenHandler pages are invoked for
 * authorization failures.
 *
 * @author Brian Pontarelli
 */
public class RedirectChallengeTest extends BaseWebTest {
  @Test
  public void customForbiddenHandler_invoked() throws Exception {
    var settings = BrowserSettings.builder()
                                  .forbiddenHandler((_, res) -> {
                                    res.setStatus(403);
                                    res.setContentType("text/plain");
                                    res.getWriter().write("custom forbidden");
                                  })
                                  .build();
    var challenge = new RedirectChallenge(settings);

    try (var web = new Web()) {
      web.get("/forbidden", challenge::forbidden);
      web.start(PORT);

      HttpResponse<String> res = get("/forbidden", null);
      assertEquals(res.statusCode(), 403);
      assertEquals(res.body(), "custom forbidden");
    }
  }

  @Test
  public void unauthenticated_markerPresent_clearsAndRedirectsToLogin() throws Exception {
    var settings = BrowserSettings.builder().build();
    var writer = new CookieTokenWriter("access_token", "refresh_token", "id_token", Duration.ofDays(30));
    var challenge = new RedirectChallenge(settings);

    try (var web = new Web()) {
      web.get("/secured", (req, res) -> challenge.unauthenticated(req, res, writer, true));
      web.start(PORT);

      // Send with the guard marker present — must redirect, not interstitial.
      HttpResponse<String> res = get("/secured?" + RedirectChallenge.CSR_REDIRECT_PARAM + "=1",
          "access_token=bad");
      assertEquals(res.statusCode(), 302);
      assertEquals(res.headers().firstValue("Location").orElse(null), "/login");

      // Cookies should be cleared.
      for (String name : List.of("access_token", "refresh_token", "id_token")) {
        Cookie c = getCookie(res, name);
        assertNotNull(c, "Expected cleared cookie for [" + name + "]");
        assertEquals(c.getMaxAge(), Long.valueOf(0L), "Expected Max-Age=0 for [" + name + "]");
      }
    }
  }

  @Test
  public void unauthenticated_noMarker_retryable_writesMetaRefreshInterstitial() throws Exception {
    var settings = BrowserSettings.builder().build();
    var writer = new CookieTokenWriter("access_token", "refresh_token", "id_token", Duration.ofDays(30));
    var challenge = new RedirectChallenge(settings);

    try (var web = new Web()) {
      web.get("/secured", (req, res) -> challenge.unauthenticated(req, res, writer, true));
      web.start(PORT);

      HttpResponse<String> res = get("/secured", null);
      assertEquals(res.statusCode(), 200);
      assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("text/html"),
          "Expected an HTML interstitial");
      assertTrue(res.body().contains("http-equiv=\"refresh\""), "Expected meta-refresh in body");
      assertTrue(res.body().contains(RedirectChallenge.CSR_REDIRECT_PARAM + "=1"),
          "Expected guard parameter in refresh URL");
    }
  }
}
