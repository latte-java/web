/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module org.lattejava.web;

import org.lattejava.web.tests.*;

/**
 * FusionAuth-specific extension of {@link OIDCTestFixture} for tests that drive the kickstart-provisioned FusionAuth
 * instance at {@code localhost:9011}. Carries the constants for that instance (app IDs, secrets, the canned
 * admin/user credentials) and overrides {@link #clientSecretFor(String)} so a single fixture can log in to any of
 * the four kickstart-provisioned applications by ID.
 * <p>
 * Mirrors {@code src/test/docker/kickstart/kickstart.json}.
 */
public class FusionAuthFixture extends OIDCTestFixture {
  public static final String ADMIN_EMAIL = "admin@example.com";
  public static final String API_KEY = "bf69486b-4733-4470-a592-f1bfce7af580";
  public static final String DEFAULT_PASSWORD = "password";
  public static final String FAST_APP_ID = "20000000-0000-0000-0000-000000000002";
  public static final String FAST_APP_SECRET = "fast-app-secret-1234567890abcdef01234";
  public static final String FA_BASE_URL = "http://localhost:9011";
  public static final String KEYCLOAK_APP_ID = "10000000-0000-0000-0000-000000000004";
  public static final String KEYCLOAK_APP_SECRET = "keycloak-app-secret-1234567890abcdef0";
  public static final String ROTATING_APP_ID = "10000000-0000-0000-0000-000000000003";
  public static final String ROTATING_APP_SECRET = "rotating-refresh-app-secret-12345678";
  public static final String STANDARD_ADMIN_ID = "10000000-0000-0000-0000-000000000011";
  public static final String STANDARD_APP_ID = "10000000-0000-0000-0000-000000000002";
  public static final String STANDARD_APP_SECRET = "standard-app-secret-1234567890abcdef";
  public static final String STANDARD_TENANT_ID = "10000000-0000-0000-0000-000000000001";
  public static final String STANDARD_ISSUER = FA_BASE_URL + "/" + STANDARD_TENANT_ID;
  public static final String STANDARD_USER_ID = "10000000-0000-0000-0000-000000000010";
  public static final String USER_EMAIL = "user@example.com";
  private static final Map<String, String> APP_SECRETS = Map.of(
      FAST_APP_ID, FAST_APP_SECRET,
      KEYCLOAK_APP_ID, KEYCLOAK_APP_SECRET,
      ROTATING_APP_ID, ROTATING_APP_SECRET,
      STANDARD_APP_ID, STANDARD_APP_SECRET
  );

  /**
   * Builds a fixture with a default {@link WebTest} on {@link BaseWebTest#PORT} and a default {@link OIDCConfig}
   * pointing at the kickstart-provisioned standard tenant. Suitable for tests that only need {@link #login} or
   * {@link #fetchAuthorizationCode} without managing the {@link WebTest} themselves.
   */
  public FusionAuthFixture() {
    this(new WebTest(BaseWebTest.PORT), defaultConfig());
  }

  /**
   * Builds a fixture bound to the given test client and OIDC configuration.
   *
   * @param webTest The test client whose cookie jar will be populated by {@link #login}.
   * @param config  The OIDC configuration matching the application under test.
   */
  public FusionAuthFixture(WebTest webTest, OIDCConfig config) {
    super(webTest, config);
  }

  private static OIDCConfig defaultConfig() {
    return OIDCConfig.builder()
                     .issuer(STANDARD_ISSUER)
                     .clientId(STANDARD_APP_ID)
                     .clientSecret(STANDARD_APP_SECRET)
                     .build();
  }

  /**
   * Resolves the client secret for any kickstart-provisioned application by ID, allowing one fixture to log in to
   * any of {@link #FAST_APP_ID}, {@link #KEYCLOAK_APP_ID}, {@link #ROTATING_APP_ID}, or {@link #STANDARD_APP_ID}.
   *
   * @param applicationId The application UUID.
   * @return The corresponding client secret.
   * @throws IllegalArgumentException If {@code applicationId} is not a known kickstart-provisioned app.
   */
  @Override
  protected String clientSecretFor(String applicationId) {
    String secret = APP_SECRETS.get(applicationId);
    if (secret == null) {
      throw new IllegalArgumentException("Unknown applicationId [" + applicationId + "] — add to FusionAuthFixture.APP_SECRETS");
    }
    return secret;
  }

  /**
   * Widens the inherited {@link OIDCTestFixture#fetchAuthorizationCode} to {@code public} so tests in other packages
   * (e.g. {@code CallbackTest}) can drive the authorize flow directly.
   */
  @Override
  public AuthorizationCode fetchAuthorizationCode(String email, String password, String applicationId, String redirectURI) throws Exception {
    return super.fetchAuthorizationCode(email, password, applicationId, redirectURI);
  }
}
