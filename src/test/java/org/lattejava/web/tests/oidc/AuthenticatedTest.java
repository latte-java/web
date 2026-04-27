/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.jwt;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class AuthenticatedTest extends BaseWebTest {
  private static OIDC<String> oidc;

  @BeforeClass
  public static void setupOIDC() {
    var config = OIDCConfig.builder()
                           .issuer(STANDARD_ISSUER)
                           .clientId(STANDARD_APP_ID)
                           .clientSecret(STANDARD_APP_SECRET)
                           .build();
    oidc = OIDC.create(config, JWT::subject);
  }

  @Test
  public void jwt_outsideProtectedRoute_throws() {
    expectThrows(UnauthenticatedException.class, OIDC::jwt);
  }

  @Test
  public void optionalJWT_outsideProtectedRoute_isEmpty() {
    assertTrue(OIDC.optionalJWT().isEmpty());
  }

  @Test
  public void optionalUser_outsideProtectedRoute_isEmpty() {
    assertTrue(oidc.optionalUser().isEmpty());
  }

  @Test
  public void user_outsideProtectedRoute_throws() {
    expectThrows(UnauthenticatedException.class, oidc::user);
  }

  @Test
  public void validAccessToken_callsHandler_withTranslatedUser() throws Exception {
    String accessToken = login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID);

    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/protected", p -> {
        p.install(oidc.authenticated());
        p.get("/me", (_, res) -> {
          String subject = oidc.user();
          res.setStatus(200);
          res.getWriter().write(subject);
        });
      });
      web.start(PORT);

      HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/protected/me"))
                                   .header("Cookie", "access_token=" + accessToken)
                                   .GET()
                                   .build();
      HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());

      assertEquals(res.statusCode(), 200);
      assertEquals(res.body(), STANDARD_USER_ID);
    }
  }
}
