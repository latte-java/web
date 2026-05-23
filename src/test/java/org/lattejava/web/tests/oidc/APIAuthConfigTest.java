/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.testng.Assert.*;

public class APIAuthConfigTest {
  @Test
  public void apiExtractorAndWriter_defaultsNonNull() {
    var config = OIDCConfig.builder()
                           .authorizeEndpoint(URI.create("https://idp/authorize"))
                           .tokenEndpoint(URI.create("https://idp/token"))
                           .userinfoEndpoint(URI.create("https://idp/userinfo"))
                           .jwksEndpoint(URI.create("https://idp/jwks"))
                           .clientId("c").clientSecret("s")
                           .build();
    assertNotNull(config.apiTokenExtractor());
    assertNotNull(config.apiTokenWriter());
  }

  @Test
  public void introspectionEndpoint_discoveredWhenNull() {
    try (MockIdP mock = new MockIdP(BaseWebTest.PORT + 7)) {
      var config = OIDCConfig.builder()
                             .issuer(mock.issuer())
                             .clientId("c").clientSecret("s")
                             .build();
      assertEquals(config.introspectionEndpoint(), URI.create(mock.issuer() + "/oauth2/introspect"));
    }
  }

  @Test
  public void introspectionEndpoint_explicitPreserved() {
    var config = OIDCConfig.builder()
                           .authorizeEndpoint(URI.create("https://idp/authorize"))
                           .tokenEndpoint(URI.create("https://idp/token"))
                           .userinfoEndpoint(URI.create("https://idp/userinfo"))
                           .jwksEndpoint(URI.create("https://idp/jwks"))
                           .introspectionEndpoint(URI.create("https://idp/introspect"))
                           .clientId("c").clientSecret("s")
                           .build();
    assertEquals(config.introspectionEndpoint(), URI.create("https://idp/introspect"));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void introspectionEndpoint_nonLoopbackHTTP_throws() {
    OIDCConfig.builder()
              .issuer("https://idp").clientId("c").clientSecret("s")
              .introspectionEndpoint(URI.create("http://idp.example.com/introspect"))
              .build();
  }
}
