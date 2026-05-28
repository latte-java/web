/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class IntrospectTest extends BaseOIDCTest {
  private static final int MOCK_PORT = 9099;

  @Test
  public void clientError_returnsInactive() {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(400, "{\"error\":\"invalid_request\"}");
      assertTrue(introspect(mock) instanceof TokenValidator.IntrospectionResult.Inactive);
    }
  }

  @Test
  public void garbageFAToken_returnsInactive() {
    var config = faConfigWithIntrospection();
    var result = new TokenValidator(config, null).introspect("garbage.token.value");
    assertTrue(result instanceof TokenValidator.IntrospectionResult.Inactive, "Expected Inactive but got " + result);
  }

  @Test
  public void inactiveResponse_returnsInactive() {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(200, "{\"active\":false}");
      assertTrue(introspect(mock) instanceof TokenValidator.IntrospectionResult.Inactive);
    }
  }

  @Test
  public void serverError_returnsNetworkError() {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(500, "{\"error\":\"server_error\"}");
      assertTrue(introspect(mock) instanceof TokenValidator.IntrospectionResult.NetworkError);
    }
  }

  @Test
  public void validActiveResponse_returnsActive() {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(200, "{\"active\":true,\"sub\":\"abc\",\"scope\":\"api\"}");
      assertTrue(introspect(mock) instanceof TokenValidator.IntrospectionResult.Active);
    }
  }

  @Test
  public void validFAToken_returnsActive() throws Exception {
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD).accessToken();
    var config = faConfigWithIntrospection();
    var result = new TokenValidator(config, null).introspect(accessToken);
    assertTrue(result instanceof TokenValidator.IntrospectionResult.Active, "Expected Active but got " + result);
  }

  private OIDCConfig faConfigWithIntrospection() {
    return OIDCConfig.builder()
                     .issuer(STANDARD_ISSUER)
                     .clientId(STANDARD_APP_ID)
                     .clientSecret(STANDARD_APP_SECRET)
                     .introspectionEndpoint(URI.create(FA_BASE_URL + "/oauth2/introspect"))
                     .build();
  }

  private TokenValidator.IntrospectionResult introspect(MockIdP mock) {
    var config = OIDCConfig.builder()
                           .issuer(mock.issuer())
                           .clientId("c")
                           .clientSecret("s")
                           .build();
    return new TokenValidator(config, null).introspect("tok");
  }
}
