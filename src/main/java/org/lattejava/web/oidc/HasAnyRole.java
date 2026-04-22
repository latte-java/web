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
 * Middleware that requires a valid session AND at least one of the named roles. Unauthenticated requests trigger the
 * same login-redirect flow as {@link Authenticated}; authenticated requests missing all required roles receive a 403.
 *
 * @author Brian Pontarelli
 */
public class HasAnyRole implements Middleware {
  private final Authenticated authenticated;
  private final Set<String> required;

  public HasAnyRole(OpenIDConnect<?> oidc, String... roles) {
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
      Set<String> have = rolesOf(authenticated.oidc, jwt);
      for (String r : required) {
        if (have.contains(r)) {
          chain.next(req2, res2);
          return;
        }
      }
      res2.setStatus(403);
    });
  }

  /**
   * Applies the OIDC config's {@code roleExtractor} and coerces the result to a {@link Set}.
   */
  static Set<String> rolesOf(OpenIDConnect<?> oidc, JWT jwt) {
    Iterable<String> raw = oidc.config().roleExtractor().apply(jwt);
    if (raw == null) {
      return Set.of();
    }
    Set<String> out = new HashSet<>();
    for (String r : raw) {
      if (r != null) {
        out.add(r);
      }
    }
    return out;
  }
}
