/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class UserinfoValidationTest extends BaseWebTest {
  private static final int MOCK_PORT = 9099;

  private static OIDC<?> userinfoOIDC;

  @BeforeClass
  public static void setupOIDC() {
    var config = OIDCConfig.builder()
                           .issuer(STANDARD_ISSUER)
                           .clientId(STANDARD_APP_ID)
                           .clientSecret(STANDARD_APP_SECRET)
                           .validateAccessToken(false)
                           .build();
    userinfoOIDC = OIDC.create(config);
  }

  @Test
  public void userinfoMode_invalidAccessToken_redirectsToLogin() throws Exception {
    try (var web = new Web()) {
      web.install(userinfoOIDC);
      web.prefix("/protected", p -> {
        p.install(userinfoOIDC.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/protected/page", "access_token=tampered");
      assertEquals(res.statusCode(), 302);
      assertEquals(res.headers().firstValue("Location").orElse(null), "/login");
    }
  }

  @Test
  public void userinfoMode_userinfoReturns5xx_returns503() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      OIDC<?> mockOIDC = OIDC.create(OIDCConfig.builder()
                                               .issuer(mock.issuer())
                                               .clientId("c")
                                               .clientSecret("s")
                                               .validateAccessToken(false)
                                               .build());
      mock.onUserinfoEndpoint(500, "{\"error\":\"server_error\"}");

      try (var web = new Web()) {
        web.install(mockOIDC);
        web.prefix("/protected", p -> {
          p.install(mockOIDC.authenticated());
          p.get("/page", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpResponse<String> res = get("/protected/page", "access_token=any-opaque-token");
        assertEquals(res.statusCode(), 503);
      }
    }
  }

  @Test
  public void userinfoMode_validAccessToken_passesThroughToHandler() throws Exception {
    String accessToken = login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();

    try (var web = new Web()) {
      web.install(userinfoOIDC);
      web.prefix("/protected", p -> {
        p.install(userinfoOIDC.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/protected/page", "access_token=" + accessToken);
      assertEquals(res.statusCode(), 200);
    }
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void validateAccessTokenFalse_discoveryOmitsUserinfoEndpoint_throwsAtBuild() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT, false)) {
      OIDCConfig.builder()
                .issuer(mock.issuer())
                .clientId("c")
                .clientSecret("s")
                .validateAccessToken(false)
                .build();
    }
  }
}
