/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Handles the logout path for the app. If there is an OIDC logout endpoint, this redirects to it. Otherwise, it clears
 * the cookies and redirects to the configured post-logout landing page.
 *
 * @author Brian Pontarelli
 */
public class LogoutHandler implements Handler {
  private final BrowserSettings browser;
  private final OIDCConfig config;

  public LogoutHandler(OIDCConfig config, BrowserSettings browser) {
    this.config = config;
    this.browser = browser;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) throws Exception {
    URI logoutEndpoint = config.logoutEndpoint();
    if (logoutEndpoint == null) {
      browser.tokenWriter().clear(req, res);
      Tools.clearCookie(req, res, browser.stateCookieName());
      Tools.clearCookie(req, res, browser.returnToCookieName());
      res.sendRedirect(browser.postLogoutPage());
      return;
    }

    String idToken = browser.tokenReader().read(req).idToken();
    URI returnURI = URI.create(req.getBaseURL() + browser.logoutReturnPath());

    StringBuilder url = new StringBuilder(logoutEndpoint.toString());
    url.append(url.indexOf("?") < 0 ? '?' : '&')
       .append("post_logout_redirect_uri=")
       .append(URLEncoder.encode(returnURI.toString(), StandardCharsets.UTF_8))
       .append("&client_id=")
       .append(URLEncoder.encode(config.clientId(), StandardCharsets.UTF_8));
    if (idToken != null) {
      url.append("&id_token_hint=")
         .append(URLEncoder.encode(idToken, StandardCharsets.UTF_8));
    }

    if (req.getMethod().is(HTTPMethod.POST)) {
      // POSTs might not work if the CSP is strict. This uses a meta-refresh to break a CSP redirect after a form post
      Tools.writeMetaRefresh(res, url.toString());
    } else {
      res.sendRedirect(url.toString());
    }
  }
}
