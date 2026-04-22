/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.oidc;

import org.lattejava.http.Cookie;
import org.lattejava.http.server.HTTPResponse;

/**
 * Cookie-writing helpers shared by the OIDC middlewares. All cookies set here carry {@code Secure}, {@code HttpOnly}
 * (unless specified otherwise), {@code SameSite=Strict}, and {@code Path=/}. Transient cookies (state, return-to) are
 * session-scoped (no Max-Age). Auth cookies carry the specified Max-Age.
 *
 * @author Brian Pontarelli
 */
final class OIDCCookies {
  private OIDCCookies() {
  }

  /**
   * Sets a transient cookie (no Max-Age, so the browser discards it at session end).
   */
  static void setTransient(HTTPResponse res, String name, String value, boolean httpOnly) {
    Cookie c = new Cookie(name, value);
    c.setPath("/");
    c.setSecure(true);
    c.setSameSite(Cookie.SameSite.Strict);
    c.setHttpOnly(httpOnly);
    res.addCookie(c);
  }

  /**
   * Sets an auth cookie with the given Max-Age (seconds).
   */
  static void setAuthCookie(HTTPResponse res, String name, String value, long maxAgeSeconds, boolean httpOnly) {
    Cookie c = new Cookie(name, value);
    c.setPath("/");
    c.setSecure(true);
    c.setSameSite(Cookie.SameSite.Strict);
    c.setHttpOnly(httpOnly);
    c.setMaxAge(maxAgeSeconds);
    res.addCookie(c);
  }

  /**
   * Clears a cookie by setting its value to empty with Max-Age=0.
   */
  static void clear(HTTPResponse res, String name) {
    Cookie c = new Cookie(name, "");
    c.setPath("/");
    c.setSecure(true);
    c.setSameSite(Cookie.SameSite.Strict);
    c.setMaxAge(0L);
    res.addCookie(c);
  }
}
