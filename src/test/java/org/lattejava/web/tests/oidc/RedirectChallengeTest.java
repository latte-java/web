/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;
import module org.testng;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

/**
 * Verifies the SSR redirect challenge through the public ssr profile: unauthenticated without the guard marker writes
 * a meta-refresh interstitial; with the marker it redirects to the login path and clears the token cookies. Custom
 * forbiddenHandler pages are invoked for authorization failures.
 *
 * @author Brian Pontarelli
 */
public class RedirectChallengeTest extends BaseOIDCTest {
  /**
   * The guard parameter the redirect challenge appends to interstitial follow-up URLs. Pinned here because it is part
   * of the observable wire format.
   */
  private static final String CSR_REDIRECT_PARAM = "csroidcredirect";

  private static final int MOCK_PORT = 9097;

  @Test
  public void customForbiddenHandler_invoked() throws Exception {
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD).accessToken();
    var settings = BrowserSettings.builder()
                                  .forbiddenHandler((_, res) -> {
                                    res.setStatus(403);
                                    res.setContentType("text/plain");
                                    res.getWriter().write("custom forbidden");
                                  })
                                  .build();
    OIDC<String> customSsr = OIDC.ssr(OIDCConfig.builder()
                                                .issuer(STANDARD_ISSUER)
                                                .clientId(STANDARD_APP_ID)
                                                .clientSecret(STANDARD_APP_SECRET)
                                                .build(),
        settings,
        JWT::subject);

    try (var web = new Web()) {
      web.prefix("/secured", p -> {
        p.install(customSsr.authenticated());
        p.install(customSsr.authorized((_, _) -> false));
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/secured/page", "access_token=" + accessToken);
      assertEquals(res.statusCode(), 403);
      assertEquals(res.body(), "custom forbidden");
    }
  }

  @Test
  public void unauthenticated_markerPresent_clearsAndRedirectsToLogin() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT); var web = new Web()) {
      OIDC<?> mockSsr = OIDC.ssr(mockConfig(mock));
      web.prefix("/secured", p -> {
        p.install(mockSsr.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      // Send an invalid access token with the guard marker present — must redirect, not interstitial.
      HttpResponse<String> res = get("/secured/page?" + CSR_REDIRECT_PARAM + "=1", "access_token=bad");
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
    try (MockIdP mock = new MockIdP(MOCK_PORT); var web = new Web()) {
      OIDC<?> mockSsr = OIDC.ssr(mockConfig(mock));
      web.prefix("/secured", p -> {
        p.install(mockSsr.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      // An invalid access token with no refresh token is retryable: the browser may simply have withheld the
      // SameSite=Strict refresh cookie, so the challenge serves the interstitial instead of redirecting.
      HttpResponse<String> res = get("/secured/page", "access_token=bad");
      assertEquals(res.statusCode(), 200);
      assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("text/html"),
          "Expected an HTML interstitial");
      assertTrue(res.body().contains("http-equiv=\"refresh\""), "Expected meta-refresh in body");
      assertTrue(res.body().contains(CSR_REDIRECT_PARAM + "=1"), "Expected guard parameter in refresh URL");
    }
  }

  private OIDCConfig mockConfig(MockIdP mock) {
    return OIDCConfig.builder()
                     .issuer(mock.issuer())
                     .clientId("c")
                     .clientSecret("s")
                     .build();
  }
}
