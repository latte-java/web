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

public class LogoutTest extends BaseWebTest {
  private static OIDC<?> standardOIDC;

  @BeforeClass
  public static void setupOIDC() {
    standardOIDC = OIDC.create(OIDCConfig.builder()
                                         .issuer(STANDARD_ISSUER)
                                         .clientId(STANDARD_APP_ID)
                                         .clientSecret(STANDARD_APP_SECRET)
                                         .build());
  }

  @Test
  public void logoutPath_withLogoutEndpoint_butNoIdTokenCookie_omitsIdTokenHint() throws Exception {
    try (var web = new Web()) {
      web.install(standardOIDC);
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
      web.install(standardOIDC);
      web.start(PORT);

      HttpResponse<String> res = get("/logout", "id_token=opaque-id-token");
      assertEquals(res.statusCode(), 302);

      String location = res.headers().firstValue("Location").orElseThrow();
      URI uri = URI.create(location);
      String base = uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
      assertEquals(base, "http://localhost:9011/oauth2/logout");

      Map<String, String> params = parseQuery(uri.getRawQuery());
      assertEquals(params.get("post_logout_redirect_uri"), BASE_URL + "/oidc/logout-return");
      assertEquals(params.get("client_id"), STANDARD_APP_ID);
      assertEquals(params.get("id_token_hint"), "opaque-id-token");
    }
  }

  @Test
  public void logoutPath_withoutLogoutEndpoint_clearsCookies_andRedirectsToPostLogoutPage() throws Exception {
    OIDC<?> noLogoutOIDC = OIDC.create(OIDCConfig.builder()
                                                 .authorizeEndpoint(URI.create("http://localhost:9011/oauth2/authorize"))
                                                 .tokenEndpoint(URI.create("http://localhost:9011/oauth2/token"))
                                                 .userinfoEndpoint(URI.create("http://localhost:9011/oauth2/userinfo"))
                                                 .jwksEndpoint(URI.create("http://localhost:9011/.well-known/jwks.json"))
                                                 .clientId(STANDARD_APP_ID)
                                                 .clientSecret(STANDARD_APP_SECRET)
                                                 .build());

    try (var web = new Web()) {
      web.install(noLogoutOIDC);
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
  public void logoutReturnPath_clearsAllCookies_andRedirectsToPostLogoutPage() throws Exception {
    try (var web = new Web()) {
      web.install(standardOIDC);
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
      web.install(standardOIDC);
      web.start(PORT);

      HttpResponse<String> res = get("/oidc/logout-return", null);
      assertEquals(res.statusCode(), 302);
      assertEquals(res.headers().firstValue("Location").orElse(null), "/");
    }
  }
}
