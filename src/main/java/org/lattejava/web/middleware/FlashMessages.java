/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.middleware;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Carries {@link Flash} messages between requests by keeping the browser's {@value Flash#COOKIE_NAME} cookie in step
 * with the request's copy of it.
 * <p>
 * {@link Flash} reads and rewrites the cookie on the request; this middleware never parses that value. It notes the
 * value the browser sent and, once the handler returns, decides what the browser should keep:
 * <ul>
 *   <li>A GET that completes with a non-redirect status <em>consumes</em> the messages: the cookie is cleared. The page
 *       rendered by that request is assumed to have displayed them. Messages added during such a GET are dropped.</li>
 *   <li>Any other request (a redirect with any method, or a non-GET with any status) carries the messages forward. If
 *       the handler changed the request's cookie through {@link Flash}, the new value is written; if it removed the
 *       cookie, the browser's cookie is cleared; otherwise no {@code Set-Cookie} header is sent.</li>
 *   <li>If the handler throws, the cookie is left exactly as the browser sent it.</li>
 * </ul>
 * Because cookie headers must be in place before the handler commits the response body, the clear for a GET is staged
 * before the handler runs and withdrawn afterward if the handler turned out to redirect. A handler that commits the
 * response by writing a body therefore ends with whatever was staged: a consuming GET has its cookie cleared, and a
 * non-GET keeps the cookie the browser sent, so changes made before rendering a body are visible to that request's
 * templates but are not carried forward. Add messages before redirecting to carry them to the next page.
 * <p>
 * The cookie is written with the {@link Cookies} defaults except {@code SameSite=Lax}, so it survives a redirect chain
 * that started at another site (such as an OIDC login). It has no {@code Max-Age} and lasts for the browser session.
 *
 * @author Brian Pontarelli
 */
public class FlashMessages implements Middleware {
  private static final Cookies COOKIES = Cookies.newInstance();

  private static String value(HTTPRequest req) {
    Cookie cookie = req.getCookie(Flash.COOKIE_NAME);
    return cookie != null ? cookie.value : null;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    String incoming = value(req);

    // A GET is expected to render the messages, and the handler will usually commit the response while doing so. Stage
    // the clear now so it goes out with the headers; it is withdrawn below if the GET turns out to be a redirect.
    boolean isGET = req.getMethod().is(HTTPMethod.GET);
    boolean clearedEarly = isGET && incoming != null;
    if (clearedEarly) {
      COOKIES.clear(Flash.COOKIE_NAME).from(req, res);
    }

    try {
      chain.next(req, res);
    } catch (Exception e) {
      if (clearedEarly && !res.isCommitted()) {
        res.removeCookie(Flash.COOKIE_NAME);
      }
      throw e;
    }

    if (res.isCommitted()) {
      return;
    }

    int status = res.getStatus();
    boolean redirect = status >= 300 && status < 400;
    if (isGET && !redirect) {
      return; // Consumed. The staged clear (if any) stands, and messages added during this GET are dropped.
    }

    String outgoing = value(req);
    if (Objects.equals(outgoing, incoming)) {
      if (clearedEarly) {
        res.removeCookie(Flash.COOKIE_NAME); // Unchanged. Withdraw the staged clear so the browser keeps its cookie.
      }
    } else if (outgoing == null) {
      COOKIES.clear(Flash.COOKIE_NAME).from(req, res); // The handler cleared the messages.
    } else {
      COOKIES.write(Flash.COOKIE_NAME, outgoing)
             .sameSite(Cookie.SameSite.Lax)
             .to(req, res);
    }
  }
}
