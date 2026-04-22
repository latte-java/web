/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.lattejava.web.oidc.OIDCConfig;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class OIDCConfigTest {
  @Test
  public void builder_defaults_setSensibleValues() {
    var config = OIDCConfig.builder()
        .issuer("https://idp.example.com")
        .clientId("my-client")
        .clientSecret("secret")
        .build();

    assertEquals(config.scopes(), List.of("openid", "profile", "email", "offline_access"));
    assertTrue(config.validateAccessToken());
    assertEquals(config.postLoginLanding(), "/");
    assertEquals(config.postLogoutLanding(), "/");
    assertEquals(config.callbackPath(), "/oidc/return");
    assertEquals(config.logoutPath(), "/oidc/logout");
    assertEquals(config.logoutReturnPath(), "/oidc/logout-return");
    assertEquals(config.stateCookieName(), "oidc_state");
    assertEquals(config.accessTokenCookieName(), "access_token");
    assertEquals(config.refreshTokenCookieName(), "refresh_token");
    assertEquals(config.idTokenCookieName(), "id_token");
    assertEquals(config.returnToCookieName(), "oidc_return_to");
    assertEquals(config.refreshTokenMaxAge(), Duration.ofDays(30));
    assertNotNull(config.roleExtractor());
  }
}
