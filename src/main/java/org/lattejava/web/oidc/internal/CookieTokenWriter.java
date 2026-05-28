/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Writes OIDC tokens to HTTP cookies and clears them on authentication failure. Cookie policy: id and access tokens use
 * SameSite=Lax; refresh token uses SameSite=Strict (the default) with the configured max-age.
 *
 * @author Brian Pontarelli
 */
public class CookieTokenWriter implements TokenWriter {
  public static final String ACCESS_TOKEN_COOKIE = "access_token";
  public static final String ID_TOKEN_COOKIE = "id_token";
  public static final String REFRESH_TOKEN_COOKIE = "refresh_token";
  public static final Duration REFRESH_TOKEN_MAX_AGE = Duration.ofDays(30);

  private final String accessTokenCookieName;
  private final String idTokenCookieName;
  private final String refreshTokenCookieName;
  private final Duration refreshTokenMaxAge;

  public CookieTokenWriter() {
    this(ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE, ID_TOKEN_COOKIE, REFRESH_TOKEN_MAX_AGE);
  }

  public CookieTokenWriter(String accessTokenCookieName, String refreshTokenCookieName, String idTokenCookieName,
                           Duration refreshTokenMaxAge) {
    this.accessTokenCookieName = accessTokenCookieName;
    this.idTokenCookieName = idTokenCookieName;
    this.refreshTokenCookieName = refreshTokenCookieName;
    this.refreshTokenMaxAge = refreshTokenMaxAge;
  }

  @Override
  public void clear(HTTPRequest req, HTTPResponse res) {
    Tools.clearCookie(req, res, accessTokenCookieName);
    Tools.clearCookie(req, res, idTokenCookieName);
    Tools.clearCookie(req, res, refreshTokenCookieName);
  }

  @Override
  public void write(HTTPRequest req, HTTPResponse res, Tokens tokens) {
    long expiry = tokens.expiresIn() != null ? tokens.expiresIn() : 3600L;
    Tools.addAuthCookies(req, res, accessTokenCookieName, refreshTokenCookieName, idTokenCookieName,
        refreshTokenMaxAge, tokens.idToken(), tokens.accessToken(), tokens.refreshToken(), expiry);
  }
}
