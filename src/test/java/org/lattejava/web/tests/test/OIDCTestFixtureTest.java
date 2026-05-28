/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.test;

import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;
import module org.lattejava.web.tests;
import module org.testng;

import org.lattejava.web.tests.oidc.*;

import static org.testng.Assert.*;

public class OIDCTestFixtureTest extends BaseWebTest {
  // Default cookie names from BrowserSettings.Builder defaults
  private static final String ACCESS_COOKIE = "access_token";
  private static final String ID_COOKIE = "id_token";
  private static final String REFRESH_COOKIE = "refresh_token";

  private static OIDC<String> ssr;
  private static Middleware sessionEndpoints;
  private static OIDCConfig oidcConfig;

  @BeforeClass
  public static void setupOIDC() {
    oidcConfig = OIDCConfig.builder()
                           .issuer(FusionAuthFixture.STANDARD_ISSUER)
                           .clientId(FusionAuthFixture.STANDARD_APP_ID)
                           .clientSecret(FusionAuthFixture.STANDARD_APP_SECRET)
                           .build();
    ssr = OIDC.ssr(oidcConfig, JWT::subject);
    sessionEndpoints = OIDC.sessionEndpoints(oidcConfig);
  }

  @Test
  public void login_populatesAuthCookies() throws Exception {
    var webTest = new WebTest(PORT);
    var fixture = new OIDCTestFixture(webTest, oidcConfig);

    fixture.login(FusionAuthFixture.USER_EMAIL, FusionAuthFixture.DEFAULT_PASSWORD);

    Cookie accessToken = webTest.cookies.get(ACCESS_COOKIE);
    Cookie idToken = webTest.cookies.get(ID_COOKIE);
    Cookie refreshToken = webTest.cookies.get(REFRESH_COOKIE);

    assertNotNull(accessToken, "Expected access_token cookie in jar");
    assertNotNull(idToken, "Expected id_token cookie in jar");
    assertNotNull(refreshToken, "Expected refresh_token cookie in jar");
    assertFalse(accessToken.value.isBlank());
    assertFalse(idToken.value.isBlank());
    assertFalse(refreshToken.value.isBlank());
  }

  @Test
  public void login_thenRequestProtectedRoute_succeeds() throws Exception {
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.prefix("/protected", p -> {
        p.install(ssr.authenticated());
        p.get("/me", (_, res) -> {
          res.setStatus(200);
          res.getWriter().write(ssr.user());
        });
      });
      web.start(PORT);

      var webTest = new WebTest(PORT);
      var fixture = new OIDCTestFixture(webTest, oidcConfig);

      fixture.login(FusionAuthFixture.USER_EMAIL, FusionAuthFixture.DEFAULT_PASSWORD);

      webTest.get("/protected/me")
             .assertStatus(200)
             .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo(FusionAuthFixture.STANDARD_USER_ID));
    }
  }

  @Test
  public void logout_removesAuthCookies() throws Exception {
    var webTest = new WebTest(PORT);
    var fixture = new OIDCTestFixture(webTest, oidcConfig);

    fixture.login(FusionAuthFixture.USER_EMAIL, FusionAuthFixture.DEFAULT_PASSWORD);
    fixture.logout();

    assertNull(webTest.cookies.get(ACCESS_COOKIE));
    assertNull(webTest.cookies.get(ID_COOKIE));
    assertNull(webTest.cookies.get(REFRESH_COOKIE));
  }
}
