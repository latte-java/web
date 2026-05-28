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

import static org.testng.Assert.*;

/**
 * Exercises the SameSite-Strict / cross-site-request recovery path in {@link Authentication}: when the access token is
 * invalid and no refresh token cookie is visible (the browser withheld the SameSite=Strict refresh token on a
 * cross-site entry navigation), the middleware serves a same-site meta-refresh interstitial so the refresh token is
 * sent on the follow-up request. The {@code csroidcredirect} guard parameter must ensure this happens at most once so a
 * missing/expired refresh token can never produce an infinite redirect loop.
 */
public class CrossSiteRefreshTest extends BaseOIDCTest {
  private Web web;

  @AfterMethod
  public void closeWeb() {
    if (web != null) {
      web.close();
      web = null;
    }
  }

  /**
   * End-to-end loop check: replay the exact follow-up request the browser would make after the interstitial. Two hops
   * maximum — interstitial, then login redirect — never a third interstitial.
   */
  @Test
  public void fullFlow_interstitialThenLogin_isAtMostTwoHops() throws Exception {
    startProtectedServer();

    // Hop 1: cross-site entry, refresh token withheld → interstitial.
    HttpResponse<String> hop1 = get("/protected/page", "access_token=tampered");
    assertEquals(hop1.statusCode(), 200);
    assertTrue(hop1.body().contains("http-equiv=\"refresh\""));

    // Extract the URL the browser would navigate to from the meta-refresh tag.
    Matcher m = Pattern.compile("url=([^\"]+)\"").matcher(hop1.body());
    assertTrue(m.find(), "Could not find meta-refresh URL in: " + hop1.body());
    String followUp = m.group(1).substring(BASE_URL.length());

    // Hop 2: same-site follow-up, refresh token still missing → terminal login redirect, NOT another interstitial.
    HttpResponse<String> hop2 = get(followUp, "access_token=tampered");
    assertEquals(hop2.statusCode(), 302);
    assertEquals(hop2.headers().firstValue("Location").orElse(null), "/login");
  }

  /**
   * The core guarantee: when the browser follows the interstitial back to the same URL but the refresh token is still
   * absent (genuinely deleted/expired rather than merely withheld by SameSite), the guard parameter must stop a second
   * interstitial and fall through to the normal unauthorized handling. Without the guard this would loop forever.
   */
  @Test
  public void guardParamPresent_noRefreshToken_doesNotLoop_fallsThroughToLogin() throws Exception {
    startProtectedServer();

    HttpResponse<String> res = get("/protected/page?" + RedirectChallenge.CSR_REDIRECT_PARAM + "=1", "access_token=tampered");

    // No second interstitial — the loop is broken.
    assertNotEquals(res.statusCode(), 200, "Guard param must prevent a second meta-refresh interstitial");
    assertFalse(res.body().contains("http-equiv=\"refresh\""), "Must not serve another meta-refresh, got: " + res.body());

    // Terminates at the normal unauthorized path.
    assertEquals(res.statusCode(), 302);
    assertEquals(res.headers().firstValue("Location").orElse(null), "/login");
    for (String name : List.of("access_token", "id_token", "refresh_token")) {
      Cookie c = getCookie(res, name);
      assertNotNull(c, "Expected cleared [" + name + "] cookie");
      assertEquals(c.getMaxAge(), Long.valueOf(0L));
    }
  }

  @Test
  public void invalidAccessToken_noRefreshToken_firstRequest_writesMetaRefreshWithGuardParam() throws Exception {
    startProtectedServer();

    HttpResponse<String> res = get("/protected/page", "access_token=tampered");

    // Not a redirect — an interstitial HTML page that the browser re-navigates same-site.
    assertEquals(res.statusCode(), 200);
    assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("text/html"),
        "Expected an HTML interstitial, got Content-Type [" + res.headers().firstValue("Content-Type").orElse("") + "]");
    assertTrue(res.headers().firstValue("Location").isEmpty(), "Interstitial must not be an HTTP redirect");
    assertTrue(res.body().contains("http-equiv=\"refresh\""), "Expected a meta-refresh body, got: " + res.body());
    assertTrue(res.body().contains("url=" + BASE_URL + "/protected/page?" + RedirectChallenge.CSR_REDIRECT_PARAM + "=1"),
        "Meta-refresh must target the original URL with the guard parameter, got: " + res.body());
  }

  @Test
  public void metaRefresh_appendsGuardParam_whenURLAlreadyHasQueryString() throws Exception {
    startProtectedServer();

    HttpResponse<String> res = get("/protected/page?foo=bar", "access_token=tampered");

    assertEquals(res.statusCode(), 200);
    assertTrue(res.body().contains("url=" + BASE_URL + "/protected/page?foo=bar&" + RedirectChallenge.CSR_REDIRECT_PARAM + "=1"),
        "Guard param must be appended with [&] when a query string is already present, got: " + res.body());
  }

  /**
   * When a refresh token cookie is visible the interstitial must be skipped entirely — the middleware attempts a
   * refresh directly. A failed refresh still terminates (login redirect), never an interstitial.
   */
  @Test
  public void refreshTokenPresent_skipsInterstitial_attemptsRefreshDirectly() throws Exception {
    startProtectedServer();

    HttpResponse<String> res = get("/protected/page", "access_token=tampered; refresh_token=tampered");

    assertNotEquals(res.statusCode(), 200, "Interstitial must be skipped when a refresh token cookie is present");
    assertFalse(res.body().contains("http-equiv=\"refresh\""), "Must not serve a meta-refresh when a refresh token is present");
    assertEquals(res.statusCode(), 302);
    assertEquals(res.headers().firstValue("Location").orElse(null), "/login");
  }

  private void startProtectedServer() {
    web = new Web();
    web.install(sessionEndpoints);
    web.prefix("/protected", p -> {
      p.install(ssr.authenticated());
      p.get("/page", (_, res) -> res.setStatus(200));
    });
    web.start(PORT);
  }
}
