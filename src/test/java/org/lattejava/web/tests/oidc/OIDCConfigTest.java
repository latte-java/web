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

  @Test
  public void builder_localhostHTTPIssuer_permitted() {
    var config = OIDCConfig.builder()
        .issuer("http://localhost:9011")
        .clientId("c").clientSecret("s")
        .build();
    assertEquals(config.issuer(), "http://localhost:9011");
  }

  @Test
  public void builder_allExplicitEndpoints_validatesWithoutIssuer() {
    var config = OIDCConfig.builder()
        .authorizeEndpoint(URI.create("https://idp/authorize"))
        .tokenEndpoint(URI.create("https://idp/token"))
        .userinfoEndpoint(URI.create("https://idp/userinfo"))
        .jwksEndpoint(URI.create("https://idp/jwks"))
        .clientId("c").clientSecret("s")
        .build();
    assertNull(config.issuer());
    assertNotNull(config.authorizeEndpoint());
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_missingClientId_throws() {
    OIDCConfig.builder().issuer("https://x").clientSecret("s").build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_missingClientSecret_throws() {
    OIDCConfig.builder().issuer("https://x").clientId("c").build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_neitherIssuerNorAllEndpoints_throws() {
    OIDCConfig.builder().clientId("c").clientSecret("s").build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_partialEndpointsNoIssuer_throws() {
    OIDCConfig.builder()
        .authorizeEndpoint(URI.create("https://idp/authorize"))
        .tokenEndpoint(URI.create("https://idp/token"))
        .clientId("c").clientSecret("s")
        .build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_scopesMissingOpenid_throws() {
    OIDCConfig.builder()
        .issuer("https://idp").clientId("c").clientSecret("s")
        .scopes(List.of("profile", "email"))
        .build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_duplicateCookieNames_throws() {
    OIDCConfig.builder()
        .issuer("https://idp").clientId("c").clientSecret("s")
        .stateCookieName("x").accessTokenCookieName("x")
        .build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_callbackPathWithoutSlash_throws() {
    OIDCConfig.builder()
        .issuer("https://idp").clientId("c").clientSecret("s")
        .callbackPath("oidc/return")
        .build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_duplicatePaths_throws() {
    OIDCConfig.builder()
        .issuer("https://idp").clientId("c").clientSecret("s")
        .callbackPath("/same").logoutPath("/same")
        .build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_nonHTTPSIssuer_throws() {
    OIDCConfig.builder().issuer("http://idp.example.com").clientId("c").clientSecret("s").build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_nonHTTPSRedirectURI_throws() {
    OIDCConfig.builder()
        .issuer("https://idp").clientId("c").clientSecret("s")
        .redirectURI(URI.create("http://myapp.example.com/oidc/return"))
        .build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_nonHTTPSLogoutEndpoint_throws() {
    OIDCConfig.builder()
        .issuer("https://idp").clientId("c").clientSecret("s")
        .logoutEndpoint(URI.create("http://idp.example.com/logout"))
        .build();
  }
}
