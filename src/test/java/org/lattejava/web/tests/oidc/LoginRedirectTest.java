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

public class LoginRedirectTest extends BaseOIDCTest {
  @Test
  public void authorizeURL_codeChallenge_matchesSHA256OfState() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = get("/login", null);
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
  public void loginPath_appendsIdpHintToAuthorizeURL_whenProvided() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = get("/login?idp_hint=11111111-2222-3333-4444-200000000001", null);
      assertEquals(res.statusCode(), 302);

      String location = res.headers().firstValue("Location").orElseThrow();
      Map<String, String> params = parseQuery(URI.create(location).getRawQuery());
      assertEquals(params.get("idp_hint"), "11111111-2222-3333-4444-200000000001");
    }
  }

  @Test
  public void loginPath_doesNotSetReturnToCookie_forAbsoluteURL() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      String evil = URLEncoder.encode("https://evil.com/", StandardCharsets.UTF_8);
      HttpResponse<String> res = get("/login?return_to=" + evil, null);
      Cookie returnTo = getCookie(res, "oidc_return_to");
      assertNull(returnTo, "Expected no oidc_return_to cookie for absolute URL");
    }
  }

  @Test
  public void loginPath_doesNotSetReturnToCookie_forProtocolRelativeURL() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      String evil = URLEncoder.encode("//evil.com/path", StandardCharsets.UTF_8);
      HttpResponse<String> res = get("/login?return_to=" + evil, null);
      Cookie returnTo = getCookie(res, "oidc_return_to");
      assertNull(returnTo, "Expected no oidc_return_to cookie for protocol-relative URL");
    }
  }

  @Test
  public void loginPath_redirectsToAuthorizeURL_withRequiredParams() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = get("/login", null);
      assertEquals(res.statusCode(), 302);

      String location = res.headers().firstValue("Location").orElseThrow();
      URI uri = URI.create(location);
      String base = uri.getScheme() + "://" + uri.getAuthority() + uri.getPath();
      assertEquals(base, "http://localhost:9010/oauth2/authorize");

      Map<String, String> params = parseQuery(uri.getRawQuery());
      assertEquals(params.get("response_type"), "code");
      assertEquals(params.get("client_id"), STANDARD_APP_ID);
      assertEquals(params.get("redirect_uri"), BASE_URL + "/oidc/return");
      assertEquals(params.get("scope"), "openid profile email offline_access");
      assertEquals(params.get("code_challenge_method"), "S256");
      assertNotNull(params.get("state"));
      assertNotNull(params.get("code_challenge"));
    }
  }

  @Test
  public void loginPath_setsReturnToCookie_forSafeRelativePath() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      String safe = URLEncoder.encode("/app/groups/foo/verify", StandardCharsets.UTF_8);
      HttpResponse<String> res = get("/login?return_to=" + safe, null);
      Cookie returnTo = getCookie(res, "oidc_return_to");
      assertNotNull(returnTo, "Expected oidc_return_to cookie");
      assertEquals(returnTo.getValue(), "/app/groups/foo/verify");
    }
  }

  @Test
  public void loginPath_setsStateCookie_with44HexCharsAndTransientAttributes() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.start(PORT);

      HttpResponse<String> res = get("/login", null);
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

      HttpResponse<String> res = get("/protected/foo", null);
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
