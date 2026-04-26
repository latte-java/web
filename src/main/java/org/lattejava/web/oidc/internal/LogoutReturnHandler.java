package org.lattejava.web.oidc.internal;

import module org.lattejava.web;

import org.lattejava.http.server.*;

/**
 * Handles the return from the OIDC logout endpoint.
 *
 * @author Brian Pontarelli
 */
public class LogoutReturnHandler implements Handler {
  private final OIDCConfig config;

  public LogoutReturnHandler(OIDCConfig config) {
    this.config = config;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) throws Exception {
    Tools.clearAllCookies(res, config);
    res.sendRedirect(config.postLogoutLanding());
  }
}
