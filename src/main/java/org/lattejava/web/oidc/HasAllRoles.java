/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Middleware that requires a valid session AND every named role.
 * <p>
 * Unauthenticated requests return a 401 rather than a login request since they represent a misconfigured middleware
 * chain. Routes should always call OpenIDConnect.authenticated() before this middleware to ensure the user is logged
 * in.
 *
 * @author Brian Pontarelli
 */
public class HasAllRoles implements Middleware {
  private final OIDCConfig config;
  private final Set<String> required;

  HasAllRoles(OIDCConfig config, String... roles) {
    if (roles == null || roles.length == 0) {
      throw new IllegalArgumentException("At least one role must be provided");
    }

    this.config = config;
    this.required = Set.of(roles);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    if (!Tools.CURRENT_JWT.isBound()) {
      res.setStatus(401);
      return;
    }

    var jwt = Tools.CURRENT_JWT.get();
    var roles = config.roleExtractor().apply(jwt);
    boolean hasAll = roles.containsAll(required);
    if (hasAll) {
      chain.next(req, res);
      return;
    }

    res.setStatus(401);
  }
}
