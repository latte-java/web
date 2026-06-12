/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

public class APIAuthorizedTest extends BaseOIDCTest {
  private static String accessToken;

  @BeforeClass
  public static void login() throws Exception {
    accessToken = FIXTURE.login(USER_EMAIL, DEFAULT_PASSWORD).accessToken();
  }

  @Test
  public void authorizerAllows_callsHandler() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(api.authenticated());
        p.install(api.authorized((_, _) -> true));
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(sendAuthenticated("/api/me").statusCode(), 200);
    }
  }

  @Test
  public void authorizerDenies_returns403() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(api.authenticated());
        p.install(api.authorized((_, _) -> false));
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(sendAuthenticated("/api/me").statusCode(), 403);
    }
  }

  @Test
  public void authorizerReceivesRequestAndJWT() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(api.authenticated());
        p.install(api.authorized((req, jwt) -> jwt != null && req.getPath().equals("/api/me")));
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(sendAuthenticated("/api/me").statusCode(), 200);
    }
  }

  @Test
  public void layered_baselinePassesSubDenies_returns403() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", apiPfx -> {
        apiPfx.install(api.authenticated());
        apiPfx.install(api.authorized((_, _) -> true));
        apiPfx.prefix("/users", u -> {
          u.install(api.authorized((_, _) -> false));
          u.get("/list", (_, res) -> res.setStatus(200));
        });
      });
      web.start(PORT);

      assertEquals(sendAuthenticated("/api/users/list").statusCode(), 403);
    }
  }

  @Test
  public void layered_bothPass_callsHandler() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", apiPfx -> {
        apiPfx.install(api.authenticated());
        apiPfx.install(api.authorized((_, _) -> true));
        apiPfx.prefix("/users", u -> {
          u.install(api.authorized((_, _) -> true));
          u.get("/list", (_, res) -> res.setStatus(200));
        });
      });
      web.start(PORT);

      assertEquals(sendAuthenticated("/api/users/list").statusCode(), 200);
    }
  }

  @Test
  public void noBoundJWT_returns401() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(api.authorized((_, _) -> true));
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/api/me", null).statusCode(), 401);
    }
  }

  private HttpResponse<String> sendAuthenticated(String path) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                                 .header("Authorization", "Bearer " + accessToken)
                                 .GET()
                                 .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
  }
}
