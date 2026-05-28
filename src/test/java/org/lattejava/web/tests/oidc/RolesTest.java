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

public class RolesTest extends BaseOIDCTest {
  @Test
  public void customRoleExtractor_resolvesNestedRealmAccessRoles_forKeycloakApp() throws Exception {
    var config = OIDCConfig.builder()
                           .issuer(STANDARD_ISSUER)
                           .clientId(KEYCLOAK_APP_ID)
                           .clientSecret(KEYCLOAK_APP_SECRET)
                           .roleExtractor(jwt -> {
                             Map<String, Object> realmAccess = jwt.getMap("realm_access");
                             if (realmAccess == null) {
                               return Set.of();
                             }
                             @SuppressWarnings("unchecked")
                             List<String> roles = (List<String>) realmAccess.get("roles");
                             return roles == null ? Set.of() : new HashSet<>(roles);
                           })
                           .build();
    OIDC<String> keycloakOIDC = OIDC.ssr(config, JWT::subject);
    String token = FIXTURE.login(ADMIN_EMAIL, DEFAULT_PASSWORD, KEYCLOAK_APP_ID).accessToken();

    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.prefix("/admin", p -> {
        p.install(keycloakOIDC.authenticated());
        p.install(keycloakOIDC.hasAnyRole("admin"));
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/admin/page", "access_token=" + token).statusCode(), 200);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void hasAllRoles_emptyVarargs_throws() {
    ssr.hasAllRoles();
  }

  @Test
  public void hasAllRoles_userAndModerator_passesForUser() throws Exception {
    String token = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.prefix("/protected", p -> {
        p.install(ssr.authenticated());
        p.install(ssr.hasAllRoles("user", "moderator"));
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/protected/page", "access_token=" + token).statusCode(), 200);
    }
  }

  @Test
  public void hasAllRoles_userAndModerator_returns403ForAdmin() throws Exception {
    String token = FIXTURE.login(ADMIN_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.prefix("/protected", p -> {
        p.install(ssr.authenticated());
        p.install(ssr.hasAllRoles("user", "moderator"));
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/protected/page", "access_token=" + token);
      assertEquals(res.statusCode(), 403);
      // SSR forbidden invokes the forbiddenHandler which returns an HTML body
      assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("text/html"),
          "Expected HTML forbidden body from SSR forbiddenHandler");
    }
  }

  @Test
  public void hasAllRoles_withoutAuthenticatedUpstream_returns401() throws Exception {
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.prefix("/protected", p -> {
        p.install(spa.hasAllRoles("user"));
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/protected/page", null).statusCode(), 401);
    }
  }

  @Test
  public void hasAnyRole_admin_passesForAdmin() throws Exception {
    String token = FIXTURE.login(ADMIN_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.prefix("/admin", p -> {
        p.install(ssr.authenticated());
        p.install(ssr.hasAnyRole("admin"));
        p.get("/dashboard", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/admin/dashboard", "access_token=" + token).statusCode(), 200);
    }
  }

  @Test
  public void hasAnyRole_admin_returns403ForUser() throws Exception {
    String token = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.prefix("/admin", p -> {
        p.install(ssr.authenticated());
        p.install(ssr.hasAnyRole("admin"));
        p.get("/dashboard", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      HttpResponse<String> res = get("/admin/dashboard", "access_token=" + token);
      assertEquals(res.statusCode(), 403);
      // SSR forbidden invokes the forbiddenHandler which returns an HTML body
      assertTrue(res.headers().firstValue("Content-Type").orElse("").contains("text/html"),
          "Expected HTML forbidden body from SSR forbiddenHandler");
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void hasAnyRole_emptyVarargs_throws() {
    ssr.hasAnyRole();
  }

  @Test
  public void hasAnyRole_withoutAuthenticatedUpstream_returns401() throws Exception {
    try (var web = new Web()) {
      web.install(sessionEndpoints);
      web.prefix("/admin", p -> {
        p.install(spa.hasAnyRole("admin"));
        p.get("/dashboard", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/admin/dashboard", null).statusCode(), 401);
    }
  }
}
