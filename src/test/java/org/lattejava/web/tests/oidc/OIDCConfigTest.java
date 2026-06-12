/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
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

  @Test
  public void builder_defaults_setSensibleValues() {
    var config = OIDCConfig.builder()
                           .issuer("http://localhost:9012/10000000-0000-0000-0000-000000000001")
                           .clientId("my-client")
                           .clientSecret("secret")
                           .build();

    assertEquals(config.authorizeEndpoint(), URI.create("http://localhost:9012/oauth2/authorize"));
    assertEquals(config.jwksEndpoint(), URI.create("http://localhost:9012/.well-known/jwks.json"));
    assertEquals(config.logoutEndpoint(), URI.create("http://localhost:9012/oauth2/logout"));
    assertEquals(config.tokenEndpoint(), URI.create("http://localhost:9012/oauth2/token"));

    assertEquals(config.scopes(), List.of("openid", "profile", "email", "offline_access"));
    assertTrue(config.validateAccessToken());
    assertNotNull(config.roleExtractor());
  }

  @Test
  public void builder_localhostHTTPIssuer_permitted() {
    var config = OIDCConfig.builder()
                           .issuer("http://localhost:9012/10000000-0000-0000-0000-000000000001")
                           .clientId("c").clientSecret("s")
                           .build();
    assertEquals(config.issuer(), "http://localhost:9012/10000000-0000-0000-0000-000000000001");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_missingClientId_throws() {
    OIDCConfig.builder().issuer("https://x").clientSecret("s").build();
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_missingClientSecret_throws() {
    OIDCConfig.builder().issuer("https://x").clientId("c").build();
  }

  @Test
  public void builder_publicClient_omitsSecret() {
    var config = OIDCConfig.builder()
                           .authorizeEndpoint(URI.create("https://idp/authorize"))
                           .tokenEndpoint(URI.create("https://idp/token"))
                           .userinfoEndpoint(URI.create("https://idp/userinfo"))
                           .jwksEndpoint(URI.create("https://idp/jwks"))
                           .clientId("public-cli")
                           .publicClient(true)
                           .build();
    assertTrue(config.publicClient());
    assertNull(config.clientSecret());
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void builder_publicClient_withIntrospection_throws() {
    OIDCConfig.builder()
              .authorizeEndpoint(URI.create("https://idp/authorize"))
              .tokenEndpoint(URI.create("https://idp/token"))
              .userinfoEndpoint(URI.create("https://idp/userinfo"))
              .jwksEndpoint(URI.create("https://idp/jwks"))
              .introspectionEndpoint(URI.create("https://idp/introspect"))
              .clientId("public-cli")
              .publicClient(true)
              .validateAccessToken(false)
              .build();
  }

  @Test
  public void builder_defaults_confidentialClient() {
    var config = OIDCConfig.builder()
                           .issuer("http://localhost:9012/10000000-0000-0000-0000-000000000001")
                           .clientId("c").clientSecret("s")
                           .build();
    assertFalse(config.publicClient());
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

  @Test
  public void requiredFields() {
    assertThrows(IllegalArgumentException.class, () -> OIDCConfig.builder().build());
    assertThrows(IllegalArgumentException.class,
        () -> OIDCConfig.builder().clientId("c").clientSecret("s").build()); // no issuer/endpoints
  }

  @Test
  public void validateAccessTokenFalseRequiresIntrospection() {
    assertThrows(IllegalStateException.class, () -> OIDCConfig.builder()
                                                              .authorizeEndpoint(URI.create("https://idp/auth"))
                                                              .tokenEndpoint(URI.create("https://idp/token"))
                                                              .userinfoEndpoint(URI.create("https://idp/userinfo"))
                                                              .jwksEndpoint(URI.create("https://idp/jwks"))
                                                              .clientId("c").clientSecret("s")
                                                              .validateAccessToken(false)
                                                              .build());
  }
}
