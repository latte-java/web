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

import static org.testng.Assert.*;

public class LoginRedirectTest extends BaseWebTest {
  private static final String CLIENT_ID = "10000000-0000-0000-0000-000000000002";
  private static final String CLIENT_SECRET = "standard-app-secret-1234567890abcdef";
  private static final String ISSUER = "http://localhost:9011/10000000-0000-0000-0000-000000000001";

  private static OIDC<?> oidc;

  @BeforeClass
  public static void setupOIDC() {
    var config = OIDCConfig.builder()
                           .issuer(ISSUER)
                           .clientId(CLIENT_ID)
                           .clientSecret(CLIENT_SECRET)
                           .build();
    oidc = OIDC.create(config);
  }

  private static HttpResponse<String> noFollow(String url) throws Exception {
    HttpClient client = HttpClient.newBuilder()
                                  .followRedirects(HttpClient.Redirect.NEVER)
                                  .build();
    HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
    return client.send(req, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void authorizeURL_codeChallenge_matchesSHA256OfState() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = noFollow(BASE_URL + "/login");

      Cookie state = getCookie(res, "oidc_state");
      assertNotNull(state, "Expected oidc_state cookie");

      String location = res.headers().firstValue("Location").orElseThrow();
      Map<String, String> params = parseQuery(URI.create(location).getRawQuery());
      String challenge = params.get("code_challenge");
      assertNotNull(challenge);

      byte[] digest = MessageDigest.getInstance("SHA-256").digest(state.getValue().getBytes(StandardCharsets.UTF_8));
      String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
      assertEquals(challenge, expected);
    }
  }

  @Test
  public void loginPath_redirectsToAuthorizeURL_withRequiredParams() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = noFollow(BASE_URL + "/login");

      assertEquals(res.statusCode(), 302);
      String location = res.headers().firstValue("Location").orElseThrow();
      URI uri = URI.create(location);
      String base = uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
      assertEquals(base, "http://localhost:9011/oauth2/authorize");

      Map<String, String> params = parseQuery(uri.getRawQuery());
      assertEquals(params.get("response_type"), "code");
      assertEquals(params.get("client_id"), CLIENT_ID);
      assertEquals(params.get("redirect_uri"), BASE_URL + "/oidc/return");
      assertEquals(params.get("scope"), "openid profile email offline_access");
      assertEquals(params.get("code_challenge_method"), "S256");
      assertNotNull(params.get("state"));
      assertNotNull(params.get("code_challenge"));
    }
  }

  @Test
  public void loginPath_setsStateCookie_with44HexCharsAndTransientAttributes() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = noFollow(BASE_URL + "/login");

      Cookie state = getCookie(res, "oidc_state");
      assertNotNull(state, "Expected oidc_state cookie");

      String value = state.getValue();
      assertEquals(value.length(), 44, "Expected 44 hex chars, got [" + value + "]");
      assertTrue(value.matches("[0-9a-f]+"), "State value not hex: [" + value + "]");

      assertTrue(state.isHttpOnly(), "Expected HttpOnly");
      assertEquals(state.getSameSite(), Cookie.SameSite.Strict);
      assertNull(state.getMaxAge(), "state cookie should be transient");
    }
  }

  @Test
  public void unauthenticatedRequest_redirectsToLoginPath_withReturnToCookie() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/protected", p -> {
        p.install(oidc.authenticated());
        p.get("/foo", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = noFollow(BASE_URL + "/protected/foo");

      assertEquals(res.statusCode(), 302);
      assertEquals(res.headers().firstValue("Location").orElse(null), "/login");

      Cookie returnTo = getCookie(res, "oidc_return_to");
      assertNotNull(returnTo, "Expected oidc_return_to cookie");
      assertEquals(returnTo.getValue(), BASE_URL + "/protected/foo");
      assertTrue(returnTo.isHttpOnly(), "Expected HttpOnly");
      assertEquals(returnTo.getSameSite(), Cookie.SameSite.Strict);
      assertNull(returnTo.getMaxAge(), "return-to cookie should be transient");
    }
  }
}
