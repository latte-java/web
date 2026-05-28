/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class LogoutTest extends BaseOIDCTest {
  @Test
  public void logoutPath_withLogoutEndpoint_butNoIdTokenCookie_omitsIdTokenHint() throws Exception {
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.start(PORT);

      HttpResponse<String> res = get("/logout", null);
      assertEquals(res.statusCode(), 302);

      URI uri = URI.create(res.headers().firstValue("Location").orElseThrow());
      Map<String, String> params = parseQuery(uri.getRawQuery());
      assertEquals(params.get("post_logout_redirect_uri"), BASE_URL + "/oidc/logout-return");
      assertEquals(params.get("client_id"), STANDARD_APP_ID);
      assertNull(params.get("id_token_hint"), "Expected no id_token_hint when cookie absent");
    }
  }

  @Test
  public void logoutPath_withLogoutEndpoint_redirectsToIdP_withAllParams() throws Exception {
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.start(PORT);

      HttpResponse<String> res = get("/logout", "id_token=opaque-id-token");
      assertEquals(res.statusCode(), 302);

      String location = res.headers().firstValue("Location").orElseThrow();
      URI uri = URI.create(location);
      String base = uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
      assertEquals(base, "http://localhost:9012/oauth2/logout");

      Map<String, String> params = parseQuery(uri.getRawQuery());
      assertEquals(params.get("post_logout_redirect_uri"), BASE_URL + "/oidc/logout-return");
      assertEquals(params.get("client_id"), STANDARD_APP_ID);
      assertEquals(params.get("id_token_hint"), "opaque-id-token");
    }
  }

  @Test
  public void logoutPath_withoutLogoutEndpoint_clearedCookies_omitSecureFlag_overHttp() throws Exception {
    // When original cookies are set without Secure (plain http://localhost), the cleared
    // Set-Cookie must also omit Secure. Otherwise Safari drops the clear directive on plain
    // HTTP and the cookies persist (logout silently fails). Regression guard against the
    // hardcoded Secure=true that previously lived in Tools.clearCookie.
    var noLogoutConfig = OIDCConfig.builder()
                                   .authorizeEndpoint(URI.create("http://localhost:9012/oauth2/authorize"))
                                   .tokenEndpoint(URI.create("http://localhost:9012/oauth2/token"))
                                   .userinfoEndpoint(URI.create("http://localhost:9012/oauth2/userinfo"))
                                   .jwksEndpoint(URI.create("http://localhost:9012/.well-known/jwks.json"))
                                   .clientId(STANDARD_APP_ID)
                                   .clientSecret(STANDARD_APP_SECRET)
                                   .build();
    Middleware noLogoutSessionEndpoints = OIDC.sessionEndpoints(noLogoutConfig);

    try (var web = new Web()) {
      web.install(noLogoutSessionEndpoints);
      web.start(PORT);

      HttpResponse<String> res = get("/logout", "access_token=a; id_token=i; refresh_token=r");
      for (String name : List.of("access_token", "id_token", "refresh_token", "oidc_state", "oidc_return_to")) {
        Cookie c = getCookie(res, name);
        assertNotNull(c, "Expected cleared [" + name + "] cookie");
        assertFalse(c.isSecure(), "Cleared [" + name + "] cookie must NOT have Secure on plain HTTP; Safari drops Set-Cookie with Secure over http and the cookie persists.");
      }
    }
  }

  @Test
  public void logoutPath_withoutLogoutEndpoint_clearsCookies_andRedirectsToPostLogoutPage() throws Exception {
    var noLogoutConfig = OIDCConfig.builder()
                                   .authorizeEndpoint(URI.create("http://localhost:9012/oauth2/authorize"))
                                   .tokenEndpoint(URI.create("http://localhost:9012/oauth2/token"))
                                   .userinfoEndpoint(URI.create("http://localhost:9012/oauth2/userinfo"))
                                   .jwksEndpoint(URI.create("http://localhost:9012/.well-known/jwks.json"))
                                   .clientId(STANDARD_APP_ID)
                                   .clientSecret(STANDARD_APP_SECRET)
                                   .build();
    Middleware noLogoutSessionEndpoints = OIDC.sessionEndpoints(noLogoutConfig);

    try (var web = new Web()) {
      web.install(noLogoutSessionEndpoints);
      web.start(PORT);

      HttpResponse<String> res = get("/logout", "access_token=a; id_token=i; refresh_token=r");
      assertEquals(res.statusCode(), 302);
      assertEquals(res.headers().firstValue("Location").orElse(null), "/");

      for (String name : List.of("access_token", "id_token", "refresh_token", "oidc_state", "oidc_return_to")) {
        Cookie c = getCookie(res, name);
        assertNotNull(c, "Expected cleared [" + name + "] cookie");
        assertEquals(c.getMaxAge(), Long.valueOf(0L));
      }
    }
  }

  @Test
  public void logoutReturnPath_clearedCookies_omitSecureFlag_overHttp() throws Exception {
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.start(PORT);

      HttpResponse<String> res = get("/oidc/logout-return", "access_token=a; id_token=i; refresh_token=r; oidc_state=s; oidc_return_to=here");
      for (String name : List.of("access_token", "id_token", "refresh_token", "oidc_state", "oidc_return_to")) {
        Cookie c = getCookie(res, name);
        assertNotNull(c, "Expected cleared [" + name + "] cookie");
        assertFalse(c.isSecure(), "Cleared [" + name + "] cookie must NOT have Secure on plain HTTP; Safari drops Set-Cookie with Secure over http and the cookie persists.");
      }
    }
  }

  @Test
  public void logoutReturnPath_clearedCookies_setSecureFlag_whenForwardedHttps() throws Exception {
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.start(PORT);

      try (HttpClient client = HttpClient.newBuilder()
                                         .followRedirects(HttpClient.Redirect.NEVER)
                                         .build()) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/oidc/logout-return"))
                                     .header("X-Forwarded-Proto", "https")
                                     .header("Cookie", "access_token=a; id_token=i; refresh_token=r; oidc_state=s; oidc_return_to=here")
                                     .GET()
                                     .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

        for (String name : List.of("access_token", "id_token", "refresh_token", "oidc_state", "oidc_return_to")) {
          Cookie c = getCookie(res, name);
          assertNotNull(c, "Expected cleared [" + name + "] cookie");
          assertTrue(c.isSecure(), "Cleared [" + name + "] cookie SHOULD have Secure when X-Forwarded-Proto indicates https; otherwise it won't match the original cookie set with Secure and the clear may be ignored by strict browsers.");
        }
      }
    }
  }

  @Test
  public void logoutReturnPath_clearsAllCookies_andRedirectsToPostLogoutPage() throws Exception {
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.start(PORT);

      HttpResponse<String> res = get("/oidc/logout-return", "access_token=a; id_token=i; refresh_token=r; oidc_state=s; oidc_return_to=here");
      assertEquals(res.statusCode(), 302);
      assertEquals(res.headers().firstValue("Location").orElse(null), "/");

      for (String name : List.of("access_token", "id_token", "refresh_token", "oidc_state", "oidc_return_to")) {
        Cookie c = getCookie(res, name);
        assertNotNull(c, "Expected cleared [" + name + "] cookie");
        assertEquals(c.getMaxAge(), Long.valueOf(0L));
      }
    }
  }

  @Test
  public void logoutReturnPath_hitDirectlyWithoutCookies_succeeds() throws Exception {
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.start(PORT);

      HttpResponse<String> res = get("/oidc/logout-return", null);
      assertEquals(res.statusCode(), 302);
      assertEquals(res.headers().firstValue("Location").orElse(null), "/");
    }
  }
}
