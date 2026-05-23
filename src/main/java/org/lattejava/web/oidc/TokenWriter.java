/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module org.lattejava.http;

/**
 * Writes refreshed tokens back to the API response after a successful reactive refresh. Pluggable via
 * {@link OIDCConfig.Builder#apiTokenWriter(TokenWriter)}; the default writes the new access token to the
 * {@code X-Access-Token} response header and, when present, the rotated refresh token to {@code X-Refresh-Token}.
 * <p>
 * Symmetric with {@link TokenExtractor}: the extractor reads a {@link Tokens} from the request, the writer puts the
 * refreshed tokens onto the response.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface TokenWriter {
  /**
   * Writes the refreshed tokens to the response.
   *
   * @param req    The current request.
   * @param res    The response to write to.
   * @param tokens The newly issued tokens; the refresh token may be {@code null} if the IdP did not rotate it.
   */
  void write(HTTPRequest req, HTTPResponse res, Tokens tokens);

  /**
   * The default {@link TokenWriter}: writes the new access token to the {@code X-Access-Token} response header and,
   * when present, the rotated refresh token to {@code X-Refresh-Token}. The id token and expiry are not written — API
   * clients don't use them.
   */
  class Default implements TokenWriter {
    @Override
    public void write(HTTPRequest req, HTTPResponse res, Tokens tokens) {
      if (tokens.accessToken() != null) {
        res.setHeader("X-Access-Token", tokens.accessToken());
      }
      if (tokens.refreshToken() != null) {
        res.setHeader("X-Refresh-Token", tokens.refreshToken());
      }
    }
  }
}
