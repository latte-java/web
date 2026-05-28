/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Reads OIDC tokens from HTTP request headers. When the access-token header is {@code Authorization}, the value is
 * expected in {@code Bearer <token>} form; any other header name is read verbatim.
 *
 * @author Brian Pontarelli
 */
public class HeaderTokenReader implements TokenReader {
  private final String accessTokenHeader;
  private final String refreshTokenHeader;

  public HeaderTokenReader(String accessTokenHeader, String refreshTokenHeader) {
    this.accessTokenHeader = accessTokenHeader;
    this.refreshTokenHeader = refreshTokenHeader;
  }

  @Override
  public Tokens read(HTTPRequest req) {
    String access = null;
    if ("Authorization".equalsIgnoreCase(accessTokenHeader)) {
      String authorization = req.getHeader(accessTokenHeader);
      if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
        access = authorization.substring(7).trim();
      }
    } else {
      access = req.getHeader(accessTokenHeader);
    }
    return new Tokens(access, req.getHeader(refreshTokenHeader), null, null);
  }
}
