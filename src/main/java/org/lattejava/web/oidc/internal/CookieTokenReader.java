/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Reads OIDC tokens from HTTP cookies. The three cookie names are supplied at construction time so this reader can be
 * shared across profiles with different cookie-name conventions.
 *
 * @author Brian Pontarelli
 */
public class CookieTokenReader implements TokenReader {
  public static final String ACCESS_TOKEN_COOKIE = CookieTokenWriter.ACCESS_TOKEN_COOKIE;
  public static final String ID_TOKEN_COOKIE = CookieTokenWriter.ID_TOKEN_COOKIE;
  public static final String REFRESH_TOKEN_COOKIE = CookieTokenWriter.REFRESH_TOKEN_COOKIE;

  private final String accessTokenCookieName;
  private final String idTokenCookieName;
  private final String refreshTokenCookieName;

  public CookieTokenReader() {
    this(ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE, ID_TOKEN_COOKIE);
  }

  public CookieTokenReader(String accessTokenCookieName, String refreshTokenCookieName, String idTokenCookieName) {
    this.accessTokenCookieName = accessTokenCookieName;
    this.idTokenCookieName = idTokenCookieName;
    this.refreshTokenCookieName = refreshTokenCookieName;
  }

  @Override
  public Tokens read(HTTPRequest req) {
    return new Tokens(
        Tools.readCookie(req, accessTokenCookieName),
        Tools.readCookie(req, refreshTokenCookieName),
        Tools.readCookie(req, idTokenCookieName),
        null);
  }
}
