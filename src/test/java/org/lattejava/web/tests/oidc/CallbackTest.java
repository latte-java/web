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

public class CallbackTest extends BaseWebTest {
  private static OIDC<?> oidc;

  @BeforeClass
  public static void setupOIDC() {
    var config = OIDCConfig.builder()
                           .issuer(STANDARD_ISSUER)
                           .clientId(STANDARD_APP_ID)
                           .clientSecret(STANDARD_APP_SECRET)
                           .build();
    oidc = OIDC.create(config);
  }

  private static void assertExpiresIn(Long actual) {
    assertNotNull(actual);
    assertTrue(actual >= 3600L - 5 && actual <= 3600L,
        "Expected Max-Age within 5s of [" + 3600L + "], got [" + actual + "]");
  }

  private static void assertOIDCErrorRedirect(HttpResponse<String> res, String expectedCode, String expectedDescription) {
    assertEquals(res.statusCode(), 302);
    String location = res.headers().firstValue("Location").orElseThrow();
    assertTrue(location.startsWith("/?oidc_error="), "Expected /?oidc_error=... got [" + location + "]");
    Map<String, String> params = parseQuery(URI.create(location).getRawQuery());
    assertEquals(params.get("oidc_error"), expectedCode);
    assertEquals(params.get("oidc_error_description"), expectedDescription);
  }

  private static HttpResponse<String> sendCallback(String queryString, String cookieHeader) throws Exception {
    try (HttpClient client = HttpClient.newBuilder()
                                       .followRedirects(HttpClient.Redirect.NEVER)
                                       .build()) {
      HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + "/oidc/return" + queryString)).GET();
      if (cookieHeader != null) {
        builder.header("Cookie", cookieHeader);
      }
      return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  @Test
  public void blankCode_redirectsToLandingWithOIDCError() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = sendCallback("?state=abc&code=", "oidc_state=abc");
      assertOIDCErrorRedirect(res, "missing_code", "Missing authorization code");
    }
  }

  @Test
  public void errorQueryParam_clearsCookies_redirectsToLanding_withOIDCErrorParam() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = sendCallback(
          "?error=access_denied&error_description=user+cancelled",
          "oidc_state=abc; oidc_return_to=" + BASE_URL + "/somewhere"
      );

      assertOIDCErrorRedirect(res, "access_denied", "user cancelled");

      for (String name : List.of("access_token", "id_token", "refresh_token", "oidc_state", "oidc_return_to")) {
        Cookie c = getCookie(res, name);
        assertNotNull(c, "Expected clear-cookie for [" + name + "]");
        assertEquals(c.getMaxAge(), Long.valueOf(0L), "Expected Max-Age=0 for [" + name + "]");
      }
    }
  }

  @Test
  public void missingCodeQueryParam_redirectsToLandingWithOIDCError() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = sendCallback("?state=abc", "oidc_state=abc");
      assertOIDCErrorRedirect(res, "missing_code", "Missing authorization code");
    }
  }

  @Test
  public void missingStateCookie_redirectsToLandingWithOIDCError() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = sendCallback("?state=abc&code=xyz", null);
      assertOIDCErrorRedirect(res, "invalid_state", "Invalid state");
    }
  }

  @Test
  public void stateCookieMismatch_redirectsToLandingWithOIDCError() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = sendCallback("?state=abc&code=xyz", "oidc_state=different");
      assertOIDCErrorRedirect(res, "invalid_state", "Invalid state");
    }
  }

  @Test
  public void successfulCodeExchange_redirectsToReturnToCookie_whenSet() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      var auth = fetchAuthorizationCode(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID, BASE_URL + "/oidc/return");

      HttpResponse<String> res = sendCallback(
          "?code=" + auth.code() + "&state=" + auth.state(),
          "oidc_state=" + auth.state() + "; oidc_return_to=" + BASE_URL + "/dashboard"
      );

      assertEquals(res.statusCode(), 302);
      assertEquals(res.headers().firstValue("Location").orElse(null), BASE_URL + "/dashboard");
    }
  }

  @Test
  public void successfulCodeExchange_setsAuthCookies_andRedirectsToPostLoginLanding() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      var auth = fetchAuthorizationCode(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID, BASE_URL + "/oidc/return");

      HttpResponse<String> res = sendCallback(
          "?code=" + auth.code() + "&state=" + auth.state(),
          "oidc_state=" + auth.state()
      );

      assertEquals(res.statusCode(), 302);
      assertEquals(res.headers().firstValue("Location").orElse(null), "/");

      Cookie access = getCookie(res, "access_token");
      assertNotNull(access, "Expected access_token cookie");
      assertTrue(access.isHttpOnly(), "access_token should be HttpOnly");
      assertExpiresIn(access.getMaxAge());

      Cookie id = getCookie(res, "id_token");
      assertNotNull(id, "Expected id_token cookie");
      assertFalse(id.isHttpOnly(), "id_token should not be HttpOnly (SPA reads claims)");
      assertExpiresIn(id.getMaxAge());

      Cookie refresh = getCookie(res, "refresh_token");
      assertNotNull(refresh, "Expected refresh_token cookie");
      assertTrue(refresh.isHttpOnly(), "refresh_token should be HttpOnly");
      assertEquals(refresh.getMaxAge(), Long.valueOf(Duration.ofDays(30).toSeconds()));
    }
  }
}
