/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
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
  private final OIDCConfig config;

  public LogoutHandler(OIDCConfig config) {
    this.config = config;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) throws Exception {
    URI logoutEndpoint = config.logoutEndpoint();
    if (logoutEndpoint == null) {
      Tools.clearAllCookies(res, config);
      res.sendRedirect(config.postLogout());
      return;
    }

    String idToken = Tools.readCookie(req, config.idTokenCookieName());
    URI returnURI = URI.create(req.getBaseURL() + config.logoutReturnPath());

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
    res.sendRedirect(url.toString());
  }
}
