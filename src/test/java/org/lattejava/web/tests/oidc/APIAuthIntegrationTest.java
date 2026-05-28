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
 * End-to-end API auth against FusionAuth: {@code api.authenticated()} validates and binds the JWT, then
 * {@code api.authorized(...)} makes the access decision off a real claim.
 */
public class APIAuthIntegrationTest extends BaseOIDCTest {
  @Test
  public void authenticatedAndAuthorized_reachesHandler() throws Exception {
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    OIDC<String> apiOIDC = apiOIDC();

    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(apiOIDC.authenticated());
        p.install(apiOIDC.authorized((_, jwt) -> STANDARD_USER_ID.equals(jwt.subject())));
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(getWithToken(accessToken).statusCode(), 200);
    }
  }

  @Test
  public void authenticatedButNotAuthorized_returns403() throws Exception {
    String accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    OIDC<String> apiOIDC = apiOIDC();

    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(apiOIDC.authenticated());
        p.install(apiOIDC.authorized((_, _) -> false));
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(getWithToken(accessToken).statusCode(), 403);
    }
  }

  private OIDC<String> apiOIDC() {
    return OIDC.api(OIDCConfig.builder()
                              .issuer(STANDARD_ISSUER)
                              .clientId(STANDARD_APP_ID)
                              .clientSecret(STANDARD_APP_SECRET)
                              .introspectionEndpoint(URI.create(FA_BASE_URL + "/oauth2/introspect"))
                              .build(), JWT::subject);
  }

  private HttpResponse<String> getWithToken(String accessToken) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/me"))
                                 .header("Authorization", "Bearer " + accessToken)
                                 .GET()
                                 .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
  }
}
