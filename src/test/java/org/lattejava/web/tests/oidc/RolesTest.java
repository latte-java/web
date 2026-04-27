/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class RolesTest extends BaseWebTest {
  private static OIDC<?> oidc;

  @BeforeClass
  public static void setupOIDC() {
    var config = OIDCConfig.builder()
                           .issuer(STANDARD_ISSUER)
                           .clientId(STANDARD_APP_ID)
                           .clientSecret(STANDARD_APP_SECRET)
                           .build();
    oidc = OIDC.create(config);
  }

  private static HttpResponse<String> get(String path, String accessToken) throws Exception {
    try (HttpClient client = HttpClient.newBuilder()
                                       .followRedirects(HttpClient.Redirect.NEVER)
                                       .build()) {
      HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(BASE_URL + path)).GET();
      if (accessToken != null) {
        req.header("Cookie", "access_token=" + accessToken);
      }
      return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

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
    OIDC<?> keycloakOIDC = OIDC.create(config);
    String token = login(ADMIN_EMAIL, DEFAULT_PASSWORD, KEYCLOAK_APP_ID).accessToken();

    try (var web = new Web()) {
      web.install(keycloakOIDC);
      web.prefix("/admin", p -> {
        p.install(keycloakOIDC.authenticated());
        p.install(keycloakOIDC.hasAnyRole("admin"));
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/admin/page", token).statusCode(), 200);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void hasAllRoles_emptyVarargs_throws() {
    oidc.hasAllRoles();
  }

  @Test
  public void hasAllRoles_userAndModerator_passesForUser() throws Exception {
    String token = login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/protected", p -> {
        p.install(oidc.authenticated());
        p.install(oidc.hasAllRoles("user", "moderator"));
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/protected/page", token).statusCode(), 200);
    }
  }

  @Test
  public void hasAllRoles_userAndModerator_returns403ForAdmin() throws Exception {
    String token = login(ADMIN_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/protected", p -> {
        p.install(oidc.authenticated());
        p.install(oidc.hasAllRoles("user", "moderator"));
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/protected/page", token).statusCode(), 403);
    }
  }

  @Test
  public void hasAllRoles_withoutAuthenticatedUpstream_returns401() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/protected", p -> {
        p.install(oidc.hasAllRoles("user"));
        p.get("/page", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/protected/page", null).statusCode(), 401);
    }
  }

  @Test
  public void hasAnyRole_admin_passesForAdmin() throws Exception {
    String token = login(ADMIN_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/admin", p -> {
        p.install(oidc.authenticated());
        p.install(oidc.hasAnyRole("admin"));
        p.get("/dashboard", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/admin/dashboard", token).statusCode(), 200);
    }
  }

  @Test
  public void hasAnyRole_admin_returns403ForUser() throws Exception {
    String token = login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/admin", p -> {
        p.install(oidc.authenticated());
        p.install(oidc.hasAnyRole("admin"));
        p.get("/dashboard", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/admin/dashboard", token).statusCode(), 403);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void hasAnyRole_emptyVarargs_throws() {
    oidc.hasAnyRole();
  }

  @Test
  public void hasAnyRole_withoutAuthenticatedUpstream_returns401() throws Exception {
    try (var web = new Web()) {
      web.install(oidc);
      web.prefix("/admin", p -> {
        p.install(oidc.hasAnyRole("admin"));
        p.get("/dashboard", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/admin/dashboard", null).statusCode(), 401);
    }
  }
}
