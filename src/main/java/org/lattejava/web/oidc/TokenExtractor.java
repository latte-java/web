/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module org.lattejava.http;

/**
 * Pulls the access and refresh tokens off an incoming API request. Pluggable via
 * {@link OIDCConfig.Builder#apiTokenExtractor(TokenExtractor)}; the default reads the access token from the
 * {@code Authorization: Bearer <token>} header and the refresh token from the {@code X-Refresh-Token} header.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface TokenExtractor {
  /**
   * Extracts the tokens from the request.
   *
   * @param req The current request.
   * @return The extracted tokens; any field of the returned {@link Tokens} may be {@code null}.
   */
  Tokens extract(HTTPRequest req);

  /**
   * The default {@link TokenExtractor}: reads the access token from the {@code Authorization: Bearer <token>} header
   * and the refresh token from the {@code X-Refresh-Token} header. A missing header yields {@code null} in the
   * corresponding {@link Tokens} field; the id token and expiry are always {@code null}.
   */
  class Default implements TokenExtractor {
    @Override
    public Tokens extract(HTTPRequest req) {
      String authorization = req.getHeader("Authorization");
      String accessToken = (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7))
          ? authorization.substring(7).trim()
          : null;
      return new Tokens(accessToken, req.getHeader("X-Refresh-Token"), null, null);
    }
  }
}
