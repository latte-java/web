/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Middleware that delegates the access decision for an API request to an {@link APIAuthorizer}. Install via
 * {@link OIDC#apiAuthorized(APIAuthorizer)} downstream of {@link APIAuthenticated} — typically once per sub-API, and
 * optionally as a baseline at the API prefix.
 * <p>
 * Authorization composes additively along the prefix chain: every {@code APIAuthorized} on a request's path runs, and
 * all must pass. A baseline at {@code /api} therefore always applies, and sub-APIs add finer checks on top of it.
 * <p>
 * It does not authenticate. A request that reaches it without a bound JWT is a configuration error
 * ({@link APIAuthenticated} was not installed upstream) and gets {@code 401}; a bound request that the authorizer
 * rejects gets {@code 403}.
 *
 * @author Brian Pontarelli
 */
public class APIAuthorized implements Middleware {
  private final APIAuthorizer authorizer;

  APIAuthorized(APIAuthorizer authorizer) {
    if (authorizer == null) {
      throw new IllegalArgumentException("An APIAuthorizer must be provided");
    }
    this.authorizer = authorizer;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    // No bound JWT means APIAuthenticated never ran upstream — fail closed.
    if (!Tools.CURRENT_JWT.isBound()) {
      res.setStatus(401);
      return;
    }

    if (authorizer.authorize(req, Tools.CURRENT_JWT.get())) {
      chain.next(req, res);
      return;
    }

    res.setStatus(403);
  }
}
