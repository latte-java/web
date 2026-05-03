/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
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
  private static OIDC<String> oidc;
  private static OIDCConfig oidcConfig;

  @BeforeClass
  public static void setupOIDC() {
    oidcConfig = OIDCConfig.builder()
                           .issuer(FusionAuthFixture.STANDARD_ISSUER)
                           .clientId(FusionAuthFixture.STANDARD_APP_ID)
                           .clientSecret(FusionAuthFixture.STANDARD_APP_SECRET)
                           .build();
    oidc = OIDC.create(oidcConfig, JWT::subject);
  }

  @Test
  public void login_populatesAuthCookies() throws Exception {
    var webTest = new WebTest(PORT);
    var fixture = new OIDCTestFixture(webTest, oidcConfig);

    fixture.login(FusionAuthFixture.USER_EMAIL, FusionAuthFixture.DEFAULT_PASSWORD, FusionAuthFixture.STANDARD_APP_ID);

    Cookie accessToken = webTest.cookies.get(oidcConfig.accessTokenCookieName());
    Cookie idToken = webTest.cookies.get(oidcConfig.idTokenCookieName());
    Cookie refreshToken = webTest.cookies.get(oidcConfig.refreshTokenCookieName());

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
      web.install(oidc);
      web.prefix("/protected", p -> {
        p.install(oidc.authenticated());
        p.get("/me", (_, res) -> {
          res.setStatus(200);
          res.getWriter().write(oidc.user());
        });
      });
      web.start(PORT);

      var webTest = new WebTest(PORT);
      var fixture = new OIDCTestFixture(webTest, oidcConfig);

      fixture.login(FusionAuthFixture.USER_EMAIL, FusionAuthFixture.DEFAULT_PASSWORD, FusionAuthFixture.STANDARD_APP_ID);

      webTest.get("/protected/me")
             .assertStatus(200)
             .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo(FusionAuthFixture.STANDARD_USER_ID));
    }
  }

  @Test
  public void logout_removesAuthCookies() throws Exception {
    var webTest = new WebTest(PORT);
    var fixture = new OIDCTestFixture(webTest, oidcConfig);

    fixture.login(FusionAuthFixture.USER_EMAIL, FusionAuthFixture.DEFAULT_PASSWORD, FusionAuthFixture.STANDARD_APP_ID);
    fixture.logout();

    assertNull(webTest.cookies.get(oidcConfig.accessTokenCookieName()));
    assertNull(webTest.cookies.get(oidcConfig.idTokenCookieName()));
    assertNull(webTest.cookies.get(oidcConfig.refreshTokenCookieName()));
  }
}
