/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class AuthenticatedTest extends BaseOIDCTest {
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
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();

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
      try (var client = HttpClient.newHttpClient()) {
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(res.statusCode(), 200);
        assertEquals(res.body(), STANDARD_USER_ID);
      }
    }
  }
}
