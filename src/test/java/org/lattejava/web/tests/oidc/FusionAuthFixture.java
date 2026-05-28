/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module org.lattejava.web;

import org.lattejava.web.tests.*;

import static org.testng.Assert.*;

/**
 * FusionAuth-specific extension of {@link OIDCTestFixture} for tests that drive the kickstart-provisioned FusionAuth
 * instance at {@code localhost:9012}. Carries the constants for that instance (app IDs, secrets, the canned admin/user
 * credentials) and exposes {@link #configFor(String)} so tests that need to drive logins against a non-standard
 * kickstart app can build the right {@link OIDCConfig} without inlining the per-app secret.
 * <p>
 * One fixture represents one OAuth client. Tests that exercise multiple kickstart apps construct one fixture per app —
 * see {@link FusionAuthFixture#FusionAuthFixture(WebTest, OIDCConfig)} and {@link #configFor(String)}.
 * <p>
 * Mirrors {@code src/test/fusionauth/kickstart/kickstart.json}.
 */
public class FusionAuthFixture extends OIDCTestFixture {
  public static final String ADMIN_EMAIL = "admin@example.com";
  public static final String DEFAULT_PASSWORD = "password";
  public static final String FAST_APP_ID = "20000000-0000-0000-0000-000000000002";
  public static final String FAST_APP_SECRET = "fast-app-secret-1234567890abcdef01234";
  public static final String FA_BASE_URL = "http://localhost:9012";
  public static final String KEYCLOAK_APP_ID = "10000000-0000-0000-0000-000000000004";
  public static final String KEYCLOAK_APP_SECRET = "keycloak-app-secret-1234567890abcdef0";
  public static final String PUBLIC_CLI_APP_ID = "30000000-0000-0000-0000-000000000001";
  public static final String PUBLIC_CLI_REDIRECT_URI = "http://127.0.0.1:8765/callback";
  public static final String ROTATING_APP_ID = "10000000-0000-0000-0000-000000000003";
  public static final String ROTATING_APP_SECRET = "rotating-refresh-app-secret-12345678";
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
   * pointing at the kickstart-provisioned standard app. Suitable for tests that only need {@link #login} or
   * {@link #fetchAuthorizationCode} against the standard app without managing the {@link WebTest} themselves.
   */
  public FusionAuthFixture() {
    this(new WebTest(BaseWebTest.PORT), configFor(STANDARD_APP_ID));
  }

  /**
   * Builds a fixture bound to the given test client and OIDC configuration.
   *
   * @param webTest The test client whose cookie jar will be populated by {@link #login}.
   * @param config  The OIDC configuration for the client under test.
   */
  public FusionAuthFixture(WebTest webTest, OIDCConfig config) {
    super(webTest, config);
  }

  /**
   * Builds a confidential-client {@link OIDCConfig} for the given kickstart-provisioned application ID. The matching
   * secret is looked up from the in-process constant table — callers don't have to inline {@code *_APP_SECRET}
   * alongside {@code *_APP_ID} at every test site.
   *
   * @param clientId One of {@link #FAST_APP_ID}, {@link #KEYCLOAK_APP_ID}, {@link #ROTATING_APP_ID}, or
   *                 {@link #STANDARD_APP_ID}.
   * @return The corresponding {@link OIDCConfig} pointing at the kickstart standard tenant.
   * @throws IllegalArgumentException If {@code clientId} is not a known kickstart-provisioned confidential app.
   */
  public static OIDCConfig configFor(String clientId) {
    String secret = APP_SECRETS.get(clientId);
    if (secret == null) {
      throw new IllegalArgumentException("Unknown kickstart clientId [" + clientId + "] — add it to FusionAuthFixture.APP_SECRETS");
    }
    try {
      return OIDCConfig.builder()
                       .issuer(STANDARD_ISSUER)
                       .clientId(clientId)
                       .clientSecret(secret)
                       .build();
    } catch (Exception e) {
      System.out.println("Unable to construct the OIDC configuration. This is likely due to FusionAuth not running.");
      fail("Unable to construct the OIDC configuration. This is likely due to FusionAuth not running.", e);
      return null;
    }
  }

  /**
   * Widens the inherited {@link OIDCTestFixture#fetchAuthorizationCode} to {@code public} so tests in other packages
   * (e.g. {@code CallbackTest}) can drive the authorize flow directly.
   */
  @Override
  public AuthorizationCode fetchAuthorizationCode(String email, String password, String redirectURI) throws Exception {
    return super.fetchAuthorizationCode(email, password, redirectURI);
  }
}
