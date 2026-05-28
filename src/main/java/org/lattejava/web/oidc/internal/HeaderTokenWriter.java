/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Writes refreshed OIDC tokens to HTTP response headers and does nothing on clear (header transports have no persistent
 * client state to remove).
 *
 * @author Brian Pontarelli
 */
public class HeaderTokenWriter implements TokenWriter {
  private final String accessTokenHeader;
  private final String refreshTokenHeader;

  public HeaderTokenWriter(String accessTokenHeader, String refreshTokenHeader) {
    this.accessTokenHeader = accessTokenHeader;
    this.refreshTokenHeader = refreshTokenHeader;
  }

  @Override
  public void clear(HTTPRequest req, HTTPResponse res) {
    // Nothing persisted on the client for header transports.
  }

  @Override
  public void write(HTTPRequest req, HTTPResponse res, Tokens tokens) {
    if (tokens.accessToken() != null) {
      res.setHeader(accessTokenHeader, tokens.accessToken());
    }
    if (tokens.refreshToken() != null) {
      res.setHeader(refreshTokenHeader, tokens.refreshToken());
    }
  }
}
