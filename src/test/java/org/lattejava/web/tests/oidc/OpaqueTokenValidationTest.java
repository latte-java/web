/*
 * Copyright (c) 2025-2026 The Latte Project
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

/**
 * Exercises the opaque-access-token path ({@code validateAccessToken=false}), where {@link Authentication} validates
 * the token at the IdP's RFC 7662 introspection endpoint rather than decoding it locally. The introspection response is
 * the claims source and supplies the {@code aud} claim for the audience check against the configured client id.
 */
public class OpaqueTokenValidationTest extends BaseOIDCTest {
  private static final int MOCK_PORT = 9099;

  private static OIDC<JWT> opaqueOIDC;
  private static Middleware opaqueSessionEndpoints;

  @BeforeClass
  public static void setupOIDC() {
    // FusionAuth's discovery document does not advertise introspection_endpoint, so it is set explicitly here.
    var config = OIDCConfig.builder()
                           .issuer(STANDARD_ISSUER)
                           .clientId(STANDARD_APP_ID)
                           .clientSecret(STANDARD_APP_SECRET)
                           .introspectionEndpoint(URI.create(FA_BASE_URL + "/oauth2/introspect"))
                           .validateAccessToken(false)
                           .build();
    opaqueOIDC = OIDC.ssr(config);
    opaqueSessionEndpoints = OIDC.sessionEndpoints(config);
  }

  @Test
  public void opaqueMode_audienceMatch_passesThroughToHandler() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      var mockConfig = OIDCConfig.builder()
                                 .issuer(mock.issuer())
                                 .clientId("c")
                                 .clientSecret("s")
                                 .validateAccessToken(false)
                                 .build();
      OIDC<JWT> mockOIDC = OIDC.ssr(mockConfig);
      mock.onIntrospectEndpoint(200, "{\"active\":true,\"aud\":\"c\"}");

      try (var web = new Web()) {
        web.install(OIDC.sessionEndpoints(mockConfig));
        web.prefix("/protected", p -> {
          p.install(mockOIDC.authenticated());
          p.get("/page", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpResponse<String> res = get("/protected/page", "access_token=any-opaque-token");
        assertEquals(res.statusCode(), 200);
      }
    }
  }

  @Test
  public void opaqueMode_audienceMismatch_redirectsToLogin() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      var mockConfig = OIDCConfig.builder()
                                 .issuer(mock.issuer())
                                 .clientId("c")
                                 .clientSecret("s")
                                 .validateAccessToken(false)
                                 .build();
      OIDC<JWT> mockOIDC = OIDC.ssr(mockConfig);
      mock.onIntrospectEndpoint(200, "{\"active\":true,\"aud\":\"some-other-audience\"}");

      try (var web = new Web()) {
        web.install(OIDC.sessionEndpoints(mockConfig));
        web.prefix("/protected", p -> {
          p.install(mockOIDC.authenticated());
          p.get("/page", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        // The guard param skips the SameSite interstitial so the absence of a refresh token is treated as final.
        HttpResponse<String> res = get("/protected/page?" + RedirectChallenge.CSR_REDIRECT_PARAM + "=1", "access_token=any-opaque-token");
        assertEquals(res.statusCode(), 302);
        assertEquals(res.headers().firstValue("Location").orElse(null), "/login");
      }
    }
  }

  @Test
  public void opaqueMode_inactiveToken_redirectsToLogin() throws Exception {
    try (var web = new Web()) {
      web.install(opaqueSessionEndpoints);
      web.prefix("/protected", p -> {
        p.install(opaqueOIDC.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      // With no refresh token cookie the first hop is the SameSite cross-site interstitial; the guard param represents
      // the browser's same-site follow-up, where the absence of a refresh token is final.
      HttpResponse<String> res = get("/protected/page?" + RedirectChallenge.CSR_REDIRECT_PARAM + "=1", "access_token=tampered");
      assertEquals(res.statusCode(), 302);
      assertEquals(res.headers().firstValue("Location").orElse(null), "/login");
    }
  }

  @Test
  public void opaqueMode_introspectReturns5xx_returns503() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      var mockConfig = OIDCConfig.builder()
                                 .issuer(mock.issuer())
                                 .clientId("c")
                                 .clientSecret("s")
                                 .validateAccessToken(false)
                                 .build();
      OIDC<JWT> mockOIDC = OIDC.ssr(mockConfig);
      mock.onIntrospectEndpoint(500, "{\"error\":\"server_error\"}");

      try (var web = new Web()) {
        web.install(OIDC.sessionEndpoints(mockConfig));
        web.prefix("/protected", p -> {
          p.install(mockOIDC.authenticated());
          p.get("/page", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpResponse<String> res = get("/protected/page", "access_token=any-opaque-token");
        assertEquals(res.statusCode(), 503);
      }
    }
  }

  @Test
  public void opaqueMode_validToken_passesThroughToHandler() throws Exception {
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();

    try (var web = new Web()) {
      web.install(opaqueSessionEndpoints);
      web.prefix("/protected", p -> {
        p.install(opaqueOIDC.authenticated());
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/protected/page", "access_token=" + accessToken);
      assertEquals(res.statusCode(), 200);
    }
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void validateAccessTokenFalse_withoutIntrospectionEndpoint_throwsAtBuild() {
    OIDCConfig.builder()
              .authorizeEndpoint(URI.create("https://idp.example.com/authorize"))
              .tokenEndpoint(URI.create("https://idp.example.com/token"))
              .userinfoEndpoint(URI.create("https://idp.example.com/userinfo"))
              .jwksEndpoint(URI.create("https://idp.example.com/jwks"))
              .clientId("c")
              .clientSecret("s")
              .validateAccessToken(false)
              .build();
  }
}
