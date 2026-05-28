/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module org.lattejava.jwt;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

public class APIAuthorizedTest extends BaseOIDCTest {
  @Test
  public void authorizerAllows_callsHandler() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(bind(JWT.builder().build()));
        p.install(api.authorized((_, _) -> true));
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/api/me", null).statusCode(), 200);
    }
  }

  @Test
  public void authorizerDenies_returns403() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(bind(JWT.builder().build()));
        p.install(api.authorized((_, _) -> false));
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/api/me", null).statusCode(), 403);
    }
  }

  @Test
  public void authorizerReceivesRequestAndJWT() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(bind(JWT.builder().build()));
        p.install(api.authorized((req, jwt) -> jwt != null && req.getPath().equals("/api/me")));
        p.get("/me", (_, res) -> res.setStatus(200));
      });
      web.start(PORT);

      assertEquals(get("/api/me", null).statusCode(), 200);
    }
  }

  @Test
  public void layered_baselinePassesSubDenies_returns403() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", apiPfx -> {
        apiPfx.install(bind(JWT.builder().build()));
        apiPfx.install(api.authorized((_, _) -> true));
        apiPfx.prefix("/users", u -> {
          u.install(api.authorized((_, _) -> false));
          u.get("/list", (_, res) -> res.setStatus(200));
        });
      });
      web.start(PORT);

      assertEquals(get("/api/users/list", null).statusCode(), 403);
    }
  }

  @Test
  public void layered_bothPass_callsHandler() throws Exception {
    try (var web = new Web()) {
      web.prefix("/api", apiPfx -> {
        apiPfx.install(bind(JWT.builder().build()));
        apiPfx.install(api.authorized((_, _) -> true));
        apiPfx.prefix("/users", u -> {
          u.install(api.authorized((_, _) -> true));
          u.get("/list", (_, res) -> res.setStatus(200));
        });
      });
      web.start(PORT);

      assertEquals(get("/api/users/list", null).statusCode(), 200);
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

  private Middleware bind(JWT jwt) {
    return (req, res, chain) -> ScopedValue.where(Tools.CURRENT_JWT, jwt).call(() -> {
      chain.next(req, res);
      return null;
    });
  }
}
