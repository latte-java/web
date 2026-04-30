/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.test;

import module java.base;
import module java.net.http;
import module org.lattejava.http;

/**
 * Stores cookies between requests in a {@link WebTest}.
 * <p>
 * Java's {@link java.net.HttpCookie} (and the {@link java.net.CookieManager} backing {@link java.net.http.HttpClient})
 * does not model the {@code SameSite} attribute, so plugging an HttpClient cookie handler into this tester would
 * silently drop that information. Instead, this jar uses {@link org.lattejava.http.Cookie} — which preserves all RFC
 * 6265bis attributes including {@code SameSite} — as its backing record. The jar is consulted before each request (to
 * build a {@code Cookie} header) and updated from each response (by parsing every {@code Set-Cookie} header).
 *
 * @author Brian Pontarelli
 */
public final class CookieJar {
  public final Map<String, Cookie> cookies = new LinkedHashMap<>();

  public CookieJar() {
  }

  /**
   * Replaces or inserts a cookie keyed by name.
   *
   * @param cookie The cookie to add.
   */
  public void add(Cookie cookie) {
    if (cookie == null || cookie.name == null) {
      return;
    }
    cookies.put(cookie.name, cookie);
  }

  /**
   * Removes all cookies from the jar.
   */
  public void clear() {
    cookies.clear();
  }

  /**
   * Returns the cookie with the given name, or {@code null} if absent.
   *
   * @param name The cookie name.
   * @return The cookie, or {@code null}.
   */
  public Cookie get(String name) {
    return cookies.get(name);
  }

  /**
   * Builds a {@code Cookie} request header value from the current jar contents, omitting expired cookies. Returns
   * {@code null} if there are no cookies to send.
   *
   * @return The request header value, or {@code null} if the jar is empty.
   */
  public String toRequestHeader() {
    if (cookies.isEmpty()) {
      return null;
    }
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    StringJoiner joiner = new StringJoiner("; ");
    for (Cookie cookie : cookies.values()) {
      if (cookie.expires != null && cookie.expires.isBefore(now)) {
        continue;
      }
      joiner.add(cookie.toRequestHeader());
    }
    String header = joiner.toString();
    return header.isEmpty() ? null : header;
  }

  /**
   * Parses every {@code Set-Cookie} header in the response and updates the jar.
   *
   * @param response The response whose {@code Set-Cookie} headers should be applied.
   */
  public void update(HttpResponse<?> response) {
    for (String header : response.headers().allValues("Set-Cookie")) {
      Cookie cookie = Cookie.fromResponseHeader(header);
      if (cookie == null || cookie.name == null) {
        continue;
      }
      // Servers signal a cookie deletion by setting Max-Age=0 or an Expires in the past.
      if (cookie.maxAge != null && cookie.maxAge <= 0) {
        cookies.remove(cookie.name);
        continue;
      }
      if (cookie.expires != null && cookie.expires.isBefore(ZonedDateTime.now(ZoneOffset.UTC))) {
        cookies.remove(cookie.name);
        continue;
      }
      cookies.put(cookie.name, cookie);
    }
  }
}
