/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

/**
 * Exercises how introspection responses are handled, observed through an API profile with
 * {@code validateAccessToken=false} so every bearer token is introspected: an inactive result (an explicit
 * {@code active:false} or any 4xx response) challenges with 401, a 5xx response is a network error and challenges with
 * 503, and an active result proceeds to validation (200 end-to-end with a real FusionAuth token).
 */
public class IntrospectTest extends BaseOIDCTest {
  private static final int MOCK_PORT = 9099;

  @Test
  public void clientError_returns401() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(400, "{\"error\":\"invalid_request\"}");
      assertEquals(introspectStatus(mock), 401);
    }
  }

  @Test
  public void garbageFAToken_returns401() throws Exception {
    assertEquals(faIntrospectStatus("garbage.token.value"), 401);
  }

  @Test
  public void inactiveResponse_returns401() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(200, "{\"active\":false}");
      assertEquals(introspectStatus(mock), 401);
    }
  }

  @Test
  public void serverError_returns503() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(500, "{\"error\":\"server_error\"}");
      assertEquals(introspectStatus(mock), 503);
    }
  }

  @Test
  public void validFAToken_returns200() throws Exception {
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD).accessToken();
    assertEquals(faIntrospectStatus(accessToken), 200);
  }

  private int faIntrospectStatus(String accessToken) throws Exception {
    var config = OIDCConfig.builder()
                           .issuer(STANDARD_ISSUER)
                           .clientId(STANDARD_APP_ID)
                           .clientSecret(STANDARD_APP_SECRET)
                           .introspectionEndpoint(URI.create(FA_BASE_URL + "/oauth2/introspect"))
                           .validateAccessToken(false)
                           .build();
    return statusFor(OIDC.api(config), accessToken);
  }

  private int introspectStatus(MockIdP mock) throws Exception {
    var config = OIDCConfig.builder()
                           .issuer(mock.issuer())
                           .clientId("c")
                           .clientSecret("s")
                           .validateAccessToken(false)
                           .build();
    return statusFor(OIDC.api(config), "tok");
  }

  private int statusFor(OIDC<?> api, String accessToken) throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(api.authenticated());
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/me"))
                                   .header("Authorization", "Bearer " + accessToken)
                                   .GET()
                                   .build();
      try (var client = HttpClient.newHttpClient()) {
        return client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
      }
    }
  }
}
