/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module org.lattejava.http;
import module org.lattejava.web;
import module org.lattejava.web.tests;
import module org.testng;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

/**
 * Drives the OIDC authorization-code + PKCE flow against the kickstart-provisioned public client (no
 * {@code clientSecret}, RFC 8252 loopback redirect URI). Verifies that the four-arg {@link OIDCTestFixture#login}
 * overload exchanges the code without HTTP Basic auth — sending {@code client_id} in the form body — and returns
 * usable tokens.
 *
 * @author Brian Pontarelli
 */
public class PublicClientLoginTest extends BaseWebTest {
  private static OIDCTestFixture fixture;
  private static WebTest webTest;

  @BeforeClass
  public static void setupPublicClient() {
    try {
      var config = OIDCConfig.builder()
                             .issuer(STANDARD_ISSUER)
                             .clientId(PUBLIC_CLI_APP_ID)
                             .publicClient(true)
                             .build();
      webTest = new WebTest(PORT);
      fixture = new OIDCTestFixture(webTest, config);
    } catch (Exception e) {
      fail("Unable to construct the public-client OIDC configuration. FusionAuth is likely not running.", e);
    }
  }

  @Test
  public void login_publicClient_returnsTokensAndPopulatesCookies() throws Exception {
    Tokens tokens = fixture.login(USER_EMAIL, DEFAULT_PASSWORD, PUBLIC_CLI_REDIRECT_URI);

    assertNotNull(tokens.accessToken(), "Expected access_token from public-client token exchange");
    assertFalse(tokens.accessToken().isBlank());
    assertNotNull(tokens.idToken(), "Expected id_token from public-client token exchange");
    assertNotNull(tokens.refreshToken(), "Expected refresh_token from public-client token exchange");

    Cookie accessCookie = webTest.cookies.get("access_token");
    assertNotNull(accessCookie, "Expected access_token cookie in jar");
    assertEquals(accessCookie.value, tokens.accessToken());
  }
}
