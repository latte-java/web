/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import module com.fasterxml.jackson.databind;
import module java.base;
import module java.net.http;

/**
 * Constants and helpers for tests that drive the kickstart-provisioned FusionAuth instance at {@code localhost:9011}.
 * <p>
 * Mirrors {@code src/test/docker/kickstart/kickstart.json}.
 */
public final class FusionAuthFixture {
  public static final String ADMIN_EMAIL = "admin@example.com";
  public static final String API_KEY = "bf69486b-4733-4470-a592-f1bfce7af580";
  public static final String DEFAULT_PASSWORD = "password";
  public static final String FA_BASE_URL = "http://localhost:9011";
  public static final String STANDARD_APP_ID = "10000000-0000-0000-0000-000000000002";
  public static final String STANDARD_APP_SECRET = "standard-app-secret-1234567890abcdef";
  public static final String STANDARD_TENANT_ID = "10000000-0000-0000-0000-000000000001";
  public static final String STANDARD_USER_ID = "10000000-0000-0000-0000-000000000010";
  public static final String STANDARD_ADMIN_ID = "10000000-0000-0000-0000-000000000011";
  public static final String USER_EMAIL = "user@example.com";
  public static final String STANDARD_ISSUER = FA_BASE_URL + "/" + STANDARD_TENANT_ID;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private FusionAuthFixture() {
  }

  /**
   * Authenticates a user via FusionAuth's {@code /api/login} endpoint and returns the issued access token (JWT).
   *
   * @param email         The user's email.
   * @param password      The user's password.
   * @param applicationId The application UUID to authenticate against.
   * @return The access token JWT.
   */
  public static String login(String email, String password, String applicationId) throws Exception {
    String body = MAPPER.writeValueAsString(Map.of(
        "loginId", email,
        "password", password,
        "applicationId", applicationId
    ));
    HttpRequest req = HttpRequest.newBuilder(URI.create(FA_BASE_URL + "/api/login"))
                                 .header("Authorization", API_KEY)
                                 .header("Content-Type", "application/json")
                                 .header("X-FusionAuth-TenantId", STANDARD_TENANT_ID)
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .build();
    HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() != 200) {
      throw new IllegalStateException("FusionAuth login failed [" + res.statusCode() + "]: [" + res.body() + "]");
    }

    JsonNode json = MAPPER.readTree(res.body());
    JsonNode token = json.get("token");
    if (token == null || token.isNull()) {
      throw new IllegalStateException("FusionAuth login response missing [token]: [" + res.body() + "]");
    }
    return token.asText();
  }
}
