/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.jwt;
import module org.lattejava.web;
import module org.testng;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class APIAuthenticatedTest extends BaseOIDCTest {
  private static final int MOCK_PORT = 9099;

  @Test
  public void activeToken_undecodable_returns401() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(200, "{\"active\":true}");
      OIDC<?> api = OIDC.create(OIDCConfig.builder().issuer(mock.issuer()).clientId("c").clientSecret("s").build());

      try (var web = new Web()) {
        web.prefix("/api", p -> {
          p.install(api.apiAuthenticated());
          p.get("/me", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        // The mock JWKS is empty, so the (active) access token cannot be decoded as a JWT.
        HttpResponse<String> res = send("/api/me", Map.of("Authorization", "Bearer not.a.jwt"));
        assertEquals(res.statusCode(), 401);
      }
    }
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void apiAuthenticated_withoutIntrospectionEndpoint_throws() {
    oidc.apiAuthenticated();
  }

  @Test
  public void customExtractor_honored() throws Exception {
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    OIDC<String> api = OIDC.create(
        configBuilder().apiTokenExtractor(req -> new Tokens(req.getHeader("X-My-Access"), null, null, null)).build(),
        JWT::subject);

    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(api.apiAuthenticated());
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = send("/api/me", Map.of("X-My-Access", accessToken));
      assertEquals(res.statusCode(), 200);
    }
  }

  @Test
  public void inactiveToken_noRefreshToken_returns401() throws Exception {
    OIDC<String> api = apiOIDC();
    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(api.apiAuthenticated());
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = send("/api/me", Map.of("Authorization", "Bearer garbage.token.value"));
      assertEquals(res.statusCode(), 401);
    }
  }

  @Test
  public void inactiveToken_refreshFails_returns401() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(200, "{\"active\":false}");
      mock.onTokenEndpoint(400, "{\"error\":\"invalid_grant\"}");
      OIDC<?> api = OIDC.create(OIDCConfig.builder().issuer(mock.issuer()).clientId("c").clientSecret("s").build());

      try (var web = new Web()) {
        web.prefix("/api", p -> {
          p.install(api.apiAuthenticated());
          p.get("/me", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpResponse<String> res = send("/api/me",
            Map.of("Authorization", "Bearer tok", "X-Refresh-Token", "rt"));
        assertEquals(res.statusCode(), 401);
      }
    }
  }

  @Test
  public void inactiveToken_validRefresh_refreshesAndWritesHeader() throws Exception {
    Tokens tokens = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID);
    assertNotNull(tokens.refreshToken());
    OIDC<String> api = apiOIDC();

    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(api.apiAuthenticated());
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = send("/api/me",
          Map.of("Authorization", "Bearer garbage.token.value", "X-Refresh-Token", tokens.refreshToken()));
      assertEquals(res.statusCode(), 200);
      String written = res.headers().firstValue("X-Access-Token").orElse(null);
      assertNotNull(written, "Expected refreshed access token in X-Access-Token header");
      assertNotEquals(written, "garbage.token.value");
    }
  }

  @Test
  public void introspectionNetworkError_returns503() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(500, "{\"error\":\"server_error\"}");
      OIDC<?> api = OIDC.create(OIDCConfig.builder().issuer(mock.issuer()).clientId("c").clientSecret("s").build());

      try (var web = new Web()) {
        web.prefix("/api", p -> {
          p.install(api.apiAuthenticated());
          p.get("/me", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpResponse<String> res = send("/api/me", Map.of("Authorization", "Bearer tok"));
        assertEquals(res.statusCode(), 503);
      }
    }
  }

  @Test
  public void missingAccessToken_returns401() throws Exception {
    OIDC<String> api = apiOIDC();
    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(api.apiAuthenticated());
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = send("/api/me", Map.of());
      assertEquals(res.statusCode(), 401);
    }
  }

  @Test
  public void refreshedToken_undecodable_returns401() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(200, "{\"active\":false}");
      mock.onTokenEndpoint(200, "{\"access_token\":\"refreshed.but.undecodable\",\"expires_in\":3600}");
      OIDC<?> api = OIDC.create(OIDCConfig.builder().issuer(mock.issuer()).clientId("c").clientSecret("s").build());

      try (var web = new Web()) {
        web.prefix("/api", p -> {
          p.install(api.apiAuthenticated());
          p.get("/me", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        // Refresh succeeds but the new token cannot be decoded against the empty mock JWKS.
        HttpResponse<String> res = send("/api/me",
            Map.of("Authorization", "Bearer tok", "X-Refresh-Token", "rt"));
        assertEquals(res.statusCode(), 401);
      }
    }
  }

  @Test
  public void validAccessToken_bindsJWT_callsHandler() throws Exception {
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    OIDC<String> api = apiOIDC();

    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(api.apiAuthenticated());
        p.get("/me", (_, res) -> {
          res.setStatus(200);
          res.getWriter().write(api.user());
        });
      });
      web.start(PORT);

      HttpResponse<String> res = send("/api/me", Map.of("Authorization", "Bearer " + accessToken));
      assertEquals(res.statusCode(), 200);
      assertEquals(res.body(), STANDARD_USER_ID);
    }
  }

  private OIDC<String> apiOIDC() {
    return OIDC.create(configBuilder().build(), JWT::subject);
  }

  private OIDCConfig.Builder configBuilder() {
    return OIDCConfig.builder()
                     .issuer(STANDARD_ISSUER)
                     .clientId(STANDARD_APP_ID)
                     .clientSecret(STANDARD_APP_SECRET)
                     .introspectionEndpoint(URI.create(FA_BASE_URL + "/oauth2/introspect"));
  }

  private HttpResponse<String> send(String path, Map<String, String> headers) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + path)).GET();
    headers.forEach(b::header);
    try (var client = HttpClient.newHttpClient()) {
      return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
  }
}
