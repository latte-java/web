/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module org.lattejava.jwt;
import module org.lattejava.web;
import module org.lattejava.web.tests;
import module org.testng;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class BaseOIDCTest extends BaseWebTest {
  public static final FusionAuthFixture FIXTURE = new FusionAuthFixture();
  public static OIDC<String> api;
  public static Middleware sessionEndpoints;
  public static OIDC<String> spa;
  public static OIDC<String> ssr;

  @BeforeSuite
  public static void setupOIDC() {
    try {
      var config = OIDCConfig.builder()
                             .issuer(STANDARD_ISSUER)
                             .clientId(STANDARD_APP_ID)
                             .clientSecret(STANDARD_APP_SECRET)
                             .build();
      ssr = OIDC.ssr(config, JWT::subject);
      spa = OIDC.spa(config, JWT::subject);
      api = OIDC.api(config, JWT::subject);
      sessionEndpoints = OIDC.sessionEndpoints(config);
    } catch (Exception e) {
      System.out.println("Unable to construct the OIDC configuration. FusionAuth is likely not running.");
      fail("Unable to construct the OIDC configuration. FusionAuth is likely not running.", e);
    }
  }
}
