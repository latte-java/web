/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.oidc;

import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

/**
 * Variant of {@link Authenticated} for API endpoints that share the OIDC session cookies with the browser but expect a
 * status code on failure rather than a 302 to the login path. Token validation, refresh handling, and the JWT
 * ScopedValue binding all behave identically to {@link Authenticated}; only the unauthenticated response differs.
 * <p>
 * On a missing or invalid access token whose refresh also fails, this middleware clears the auth cookies and returns
 * 401. The return-to cookie is not set, since the client decides how to recover from a 401.
 *
 * @author Brian Pontarelli
 */
public class JWTAuthenticated extends Authenticated {
  JWTAuthenticated(OIDCConfig config, JWKS jwks) {
    super(config, jwks);
  }

  @Override
  protected void unauthorized(HTTPRequest req, HTTPResponse res) {
    Tools.clearAllAuthCookies(res, config);
    res.setStatus(401);
  }
}
