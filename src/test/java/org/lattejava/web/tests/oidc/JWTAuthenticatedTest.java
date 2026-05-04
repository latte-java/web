/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class JWTAuthenticatedTest extends BaseWebTest {
  private static final FusionAuthFixture FIXTURE = new FusionAuthFixture();
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
  public void invalidAccessToken_invalidRefreshToken_clearsAuthCookies_returns401() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/api", p -> {
        p.install(oidc.jwtAuthenticated());
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/api/me", "access_token=tampered; refresh_token=tampered");
      assertEquals(res.statusCode(), 401);
      assertNull(res.headers().firstValue("Location").orElse(null));
      assertNull(getCookie(res, "oidc_return_to"), "401 path should not set the return-to cookie");

      for (String name : List.of("access_token", "id_token", "refresh_token")) {
        Cookie c = getCookie(res, name);
        assertNotNull(c, "Expected cleared [" + name + "] cookie");
        assertEquals(c.getMaxAge(), Long.valueOf(0L));
      }
    }
  }

  @Test
  public void noAccessTokenCookie_returns401() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/api", p -> {
        p.install(oidc.jwtAuthenticated());
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/api/me", null);
      assertEquals(res.statusCode(), 401);
      assertNull(res.headers().firstValue("Location").orElse(null));
      assertNull(getCookie(res, "oidc_return_to"), "401 path should not set the return-to cookie");
    }
  }

  @Test
  public void validAccessToken_callsHandler_withTranslatedUser() throws Exception {
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();

    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/api", p -> {
        p.install(oidc.jwtAuthenticated());
        p.get("/me", (_, res) -> {
          String subject = oidc.user();
          res.setStatus(200);
          res.getWriter().write(subject);
        });
      });
      web.start(PORT);

      HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/me"))
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

  @Test
  public void validRefreshToken_refreshSucceeds_callsHandler() throws Exception {
    Tokens tokens = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID);
    assertNotNull(tokens.refreshToken());

    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/api", p -> {
        p.install(oidc.jwtAuthenticated());
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/api/me", "access_token=tampered; refresh_token=" + tokens.refreshToken());
      assertEquals(res.statusCode(), 200);

      Cookie newAccess = getCookie(res, "access_token");
      assertNotNull(newAccess, "Expected refreshed access_token cookie");
      assertNotEquals(newAccess.getValue(), "tampered");
    }
  }
}
