/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

public class OIDCConfigTest {
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
  public void builder_callbackPathWithoutSlash_throws() {
    OIDCConfig.builder()
              .issuer("https://idp").clientId("c").clientSecret("s")
              .callbackPath("oidc/return")
              .build();
  }

  @Test
  public void builder_defaults_setSensibleValues() {
    var config = OIDCConfig.builder()
                           .issuer("http://localhost:9011/10000000-0000-0000-0000-000000000001")
                           .clientId("my-client")
                           .clientSecret("secret")
                           .build();

    assertEquals(config.authorizeEndpoint(), URI.create("http://localhost:9011/oauth2/authorize"));
    assertEquals(config.jwksEndpoint(), URI.create("http://localhost:9011/.well-known/jwks.json"));
    assertEquals(config.logoutEndpoint(), URI.create("http://localhost:9011/oauth2/logout"));
    assertEquals(config.tokenEndpoint(), URI.create("http://localhost:9011/oauth2/token"));

    assertEquals(config.scopes(), List.of("openid", "profile", "email", "offline_access"));
    assertTrue(config.validateAccessToken());
    assertEquals(config.errorPage(), "/");
    assertEquals(config.postLoginPage(), "/");
    assertEquals(config.postLogout(), "/");
    assertEquals(config.callbackPath(), "/oidc/return");
    assertEquals(config.logoutPath(), "/logout");
    assertEquals(config.logoutReturnPath(), "/oidc/logout-return");
    assertEquals(config.stateCookieName(), "oidc_state");
    assertEquals(config.accessTokenCookieName(), "access_token");
    assertEquals(config.refreshTokenCookieName(), "refresh_token");
    assertEquals(config.idTokenCookieName(), "id_token");
    assertEquals(config.returnToCookieName(), "oidc_return_to");
    assertEquals(config.refreshTokenMaxAge(), Duration.ofDays(30));
    assertNotNull(config.roleExtractor());
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_duplicateCookieNames_throws() {
    OIDCConfig.builder()
              .issuer("https://idp").clientId("c").clientSecret("s")
              .stateCookieName("x").accessTokenCookieName("x")
              .build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_duplicatePaths_throws() {
    OIDCConfig.builder()
              .issuer("https://idp").clientId("c").clientSecret("s")
              .callbackPath("/same").logoutPath("/same")
              .build();
  }

  @Test
  public void builder_localhostHTTPIssuer_permitted() {
    var config = OIDCConfig.builder()
                           .issuer("http://localhost:9011/10000000-0000-0000-0000-000000000001")
                           .clientId("c").clientSecret("s")
                           .build();
    assertEquals(config.issuer(), "http://localhost:9011/10000000-0000-0000-0000-000000000001");
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
  public void builder_nonHTTPSIssuer_throws() {
    OIDCConfig.builder().issuer("http://idp.example.com").clientId("c").clientSecret("s").build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_nonHTTPSLogoutEndpoint_throws() {
    OIDCConfig.builder()
              .issuer("https://idp").clientId("c").clientSecret("s")
              .logoutEndpoint(URI.create("http://idp.example.com/logout"))
              .build();
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
}
