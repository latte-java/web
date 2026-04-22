/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.http;

import org.lattejava.jwt.domain.JWT;
import org.lattejava.web.*;

/**
 * Middleware that requires a valid session AND every named role. Unauthenticated requests trigger the same
 * login-redirect flow as {@link Authenticated}; authenticated requests missing any required role receive a 403.
 *
 * @author Brian Pontarelli
 */
public class HasAllRoles implements Middleware {
  private final Authenticated authenticated;
  private final Set<String> required;

  public HasAllRoles(OpenIDConnect<?> oidc, String... roles) {
    if (roles == null || roles.length == 0) {
      throw new IllegalArgumentException("At least one role must be provided");
    }
    this.authenticated = new Authenticated(oidc);
    this.required = Set.of(roles);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    authenticated.handle(req, res, (req2, res2) -> {
      JWT jwt = OpenIDConnect.jwt();
      Set<String> have = HasAnyRole.rolesOf(authenticated.oidc, jwt);
      if (have.containsAll(required)) {
        chain.next(req2, res2);
        return;
      }
      res2.setStatus(403);
    });
  }
}
