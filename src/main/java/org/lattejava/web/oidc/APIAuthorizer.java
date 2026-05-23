/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module org.lattejava.http;
import module org.lattejava.jwt;

/**
 * Application-supplied decision for whether a validated access token may call a given API. Invoked by the
 * {@link APIAuthorized} middleware after {@link APIAuthenticated} has validated the token and bound the decoded
 * {@link JWT}.
 * <p>
 * Receives the request so the decision can be per-endpoint (path, method, scope), which is why authorization is
 * delegated here rather than baked into fixed role checks. The {@link APIAuthorized} middleware that invokes it plays
 * the same role as the role-based middlewares — it does not authenticate; it assumes a bound JWT is present.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface APIAuthorizer {
  /**
   * Decides whether the request is allowed.
   *
   * @param req The current request.
   * @param jwt The validated, decoded access-token JWT.
   * @return {@code true} to allow the request through; {@code false} to deny it with a 403.
   */
  boolean authorize(HTTPRequest req, JWT jwt);
}
