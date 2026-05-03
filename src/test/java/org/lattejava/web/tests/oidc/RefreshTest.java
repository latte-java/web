/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class RefreshTest extends BaseWebTest {
  private static final FusionAuthFixture FIXTURE = new FusionAuthFixture();
  private static final int MOCK_PORT = 9099;

  private static OIDC<?> fastOIDC;
  private static OIDC<?> rotatingOIDC;
  private static OIDC<?> standardOIDC;

  @BeforeClass
  public static void setupOIDC() {
    fastOIDC = OIDC.create(OIDCConfig.builder()
                                     .issuer(STANDARD_ISSUER)
                                     .clientId(FAST_APP_ID)
                                     .clientSecret(FAST_APP_SECRET)
                                     .build());
    rotatingOIDC = OIDC.create(OIDCConfig.builder()
                                         .issuer(STANDARD_ISSUER)
                                         .clientId(ROTATING_APP_ID)
                                         .clientSecret(ROTATING_APP_SECRET)
                                         .build());
    standardOIDC = OIDC.create(OIDCConfig.builder()
                                         .issuer(STANDARD_ISSUER)
                                         .clientId(STANDARD_APP_ID)
                                         .clientSecret(STANDARD_APP_SECRET)
                                         .build());
  }

  private static void assertLoginRedirect(HttpResponse<String> res) {
    assertEquals(res.statusCode(), 302);
    assertEquals(res.headers().firstValue("Location").orElse(null), "/login");
    Cookie returnTo = getCookie(res, "oidc_return_to");
    assertNotNull(returnTo, "Expected oidc_return_to cookie");
    assertEquals(returnTo.getValue(), BASE_URL + "/protected/page");
  }

  @Test
  public void expiredAccessToken_validRefreshToken_refreshSucceeds_andSetsNewAccessTokenCookie() throws Exception {
    Tokens tokens = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, FAST_APP_ID);
    assertNotNull(tokens.refreshToken(), "fast app should issue a refresh token");
    Thread.sleep(2000);

    try (var web = new Web()) {
      web.install(fastOIDC);
      web.prefix("/protected", p -> {
        p.install(fastOIDC.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/protected/page", "access_token=" + tokens.accessToken() + "; refresh_token=" + tokens.refreshToken());
      assertEquals(res.statusCode(), 200);

      Cookie newAccess = getCookie(res, "access_token");
      assertNotNull(newAccess, "Expected refreshed access_token cookie");
      assertNotEquals(newAccess.getValue(), tokens.accessToken(), "Refreshed access token should differ from the original");
    }
  }

  @Test
  public void invalidAccessToken_invalidRefreshToken_clearsAuthCookies_redirectsToLogin() throws Exception {
    try (var web = new Web()) {
      web.install(standardOIDC);
      web.prefix("/protected", p -> {
        p.install(standardOIDC.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/protected/page", "access_token=tampered; refresh_token=tampered");
      assertLoginRedirect(res);

      for (String name : List.of("access_token", "id_token", "refresh_token")) {
        Cookie c = getCookie(res, name);
        assertNotNull(c, "Expected cleared [" + name + "] cookie");
        assertEquals(c.getMaxAge(), Long.valueOf(0L));
      }
    }
  }

  @Test
  public void invalidAccessToken_noRefreshTokenCookie_clearsAuthCookies_redirectsToLogin() throws Exception {
    try (var web = new Web()) {
      web.install(standardOIDC);
      web.prefix("/protected", p -> {
        p.install(standardOIDC.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/protected/page", "access_token=tampered");
      assertLoginRedirect(res);
    }
  }

  @Test
  public void rotationEnabled_refreshIssuesNewRefreshTokenCookie_withRefreshTokenMaxAge() throws Exception {
    Tokens tokens = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, ROTATING_APP_ID);
    assertNotNull(tokens.refreshToken());

    try (var web = new Web()) {
      web.install(rotatingOIDC);
      web.prefix("/protected", p -> {
        p.install(rotatingOIDC.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/protected/page", "access_token=tampered; refresh_token=" + tokens.refreshToken());
      assertEquals(res.statusCode(), 200);

      Cookie newRefresh = getCookie(res, "refresh_token");
      assertNotNull(newRefresh, "Expected refreshed refresh_token cookie");
      assertNotEquals(newRefresh.getValue(), tokens.refreshToken(), "OneTimeUse rotation should issue a different refresh token");
      assertEquals(newRefresh.getMaxAge(), Long.valueOf(Duration.ofDays(30).toSeconds()));
      assertTrue(newRefresh.isHttpOnly());
    }
  }

  @Test
  public void successfulRefresh_setsNewIdTokenCookie() throws Exception {
    Tokens tokens = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID);
    assertNotNull(tokens.idToken());

    try (var web = new Web()) {
      web.install(standardOIDC);
      web.prefix("/protected", p -> {
        p.install(standardOIDC.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/protected/page", "access_token=tampered; refresh_token=" + tokens.refreshToken());
      assertEquals(res.statusCode(), 200);

      Cookie newId = getCookie(res, "id_token");
      assertNotNull(newId, "Expected refreshed id_token cookie");
      assertFalse(newId.isHttpOnly(), "id_token should not be HttpOnly");
    }
  }

  @Test
  public void tokenEndpointReturns5xx_clearsAuthCookies_redirectsToLogin() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      OIDC<?> mockOIDC = OIDC.create(OIDCConfig.builder()
                                               .issuer(mock.issuer())
                                               .clientId("c")
                                               .clientSecret("s")
                                               .build());
      mock.onTokenEndpoint(500, "{\"error\":\"server_error\"}");

      try (var web = new Web()) {
        web.install(mockOIDC);
        web.prefix("/protected", p -> {
          p.install(mockOIDC.authenticated());
          p.get("/page", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpResponse<String> res = get("/protected/page", "access_token=tampered; refresh_token=any");
        assertLoginRedirect(res);

        for (String name : List.of("access_token", "id_token", "refresh_token")) {
          Cookie c = getCookie(res, name);
          assertNotNull(c, "Expected cleared [" + name + "] cookie");
          assertEquals(c.getMaxAge(), Long.valueOf(0L));
        }
      }
    }
  }
}
