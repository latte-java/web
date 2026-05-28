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

/**
 * Verifies multi-client isolation: a token minted for client A must be rejected by a profile configured for client B,
 * and that two profiles built from the same issuer each validate their own application's tokens correctly.
 *
 * @author Brian Pontarelli
 */
public class MultiClientTest extends BaseOIDCTest {
  @Test
  public void tokenForStandardApp_rejectedByFastApp() throws Exception {
    // Login against the standard app — the resulting access token is audience-constrained to STANDARD_APP_ID.
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();

    // Build an API profile for the FAST app — different clientId, same issuer.
    OIDC<String> fastApi = OIDC.api(
        OIDCConfig.builder()
                  .issuer(STANDARD_ISSUER)
                  .clientId(FAST_APP_ID)
                  .clientSecret(FAST_APP_SECRET)
                  .introspectionEndpoint(URI.create(FA_BASE_URL + "/oauth2/introspect"))
                  .build(),
        JWT::subject);

    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(fastApi.authenticated());
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/me"))
                                   .header("Authorization", "Bearer " + accessToken)
                                   .GET()
                                   .build();
      HttpResponse<String> res;
      try (var client = HttpClient.newHttpClient()) {
        res = client.send(req, HttpResponse.BodyHandlers.ofString());
      }
      // A token minted for STANDARD_APP_ID fails introspection (or JWT audience check) against FAST_APP_ID config.
      assertEquals(res.statusCode(), 401);
    }
  }

  @Test
  public void twoProfilesSameIssuer_eachValidatesItsOwnToken() throws Exception {
    // Mints tokens for two different apps under the same issuer/tenant.
    String standardToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    String fastToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, FAST_APP_ID).accessToken();

    // Both profiles are configured against the same issuer; each validates its own app's token correctly.
    OIDC<String> standardSsr = OIDC.ssr(
        OIDCConfig.builder()
                  .issuer(STANDARD_ISSUER)
                  .clientId(STANDARD_APP_ID)
                  .clientSecret(STANDARD_APP_SECRET)
                  .build(),
        JWT::subject);
    OIDC<String> fastSsr = OIDC.ssr(
        OIDCConfig.builder()
                  .issuer(STANDARD_ISSUER)
                  .clientId(FAST_APP_ID)
                  .clientSecret(FAST_APP_SECRET)
                  .build(),
        JWT::subject);

    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.prefix("/standard", p -> {
        p.install(standardSsr.authenticated());
        p.get("/me", (_, res) -> {
          res.setStatus(200);
          res.getWriter().write(standardSsr.user());
        });
      });
      web.prefix("/fast", p -> {
        p.install(fastSsr.authenticated());
        p.get("/me", (_, res) -> {
          res.setStatus(200);
          res.getWriter().write(fastSsr.user());
        });
      });
      web.start(PORT);

      HttpRequest standardReq = HttpRequest.newBuilder(URI.create(BASE_URL + "/standard/me"))
                                           .header("Cookie", "access_token=" + standardToken)
                                           .GET()
                                           .build();
      HttpRequest fastReq = HttpRequest.newBuilder(URI.create(BASE_URL + "/fast/me"))
                                       .header("Cookie", "access_token=" + fastToken)
                                       .GET()
                                       .build();
      try (var client = HttpClient.newHttpClient()) {
        HttpResponse<String> standardRes = client.send(standardReq, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> fastRes = client.send(fastReq, HttpResponse.BodyHandlers.ofString());

        assertEquals(standardRes.statusCode(), 200);
        assertEquals(standardRes.body(), STANDARD_USER_ID);
        assertEquals(fastRes.statusCode(), 200);
        assertEquals(fastRes.body(), STANDARD_USER_ID);
      }
    }
  }
}
