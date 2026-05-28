/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

/**
 * System middleware that handles the browser login flow paths — login, callback, logout, and logout-return — derived
 * from {@link BrowserSettings}. Any other path passes through to the chain. Install once per browser client; API-only
 * clients never install it.
 *
 * @author Brian Pontarelli
 */
public class SessionEndpoints implements Middleware {
  private final BrowserSettings browser;
  private final CallbackHandler callbackHandler;
  private final LoginHandler loginHandler;
  private final LogoutHandler logoutHandler;
  private final LogoutReturnHandler logoutReturnHandler;

  public SessionEndpoints(OIDCConfig config, BrowserSettings browser, JWKS jwks) {
    this.browser = browser;
    this.callbackHandler = new CallbackHandler(config, browser, jwks);
    this.loginHandler = new LoginHandler(config, browser);
    this.logoutHandler = new LogoutHandler(config, browser);
    this.logoutReturnHandler = new LogoutReturnHandler(browser);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    String path = req.getPath();
    if (path.equals(browser.callbackPath())) {
      callbackHandler.handle(req, res);
      return;
    }

    if (path.equals(browser.loginPath())) {
      loginHandler.handle(req, res);
      return;
    }

    if (path.equals(browser.logoutPath())) {
      logoutHandler.handle(req, res);
      return;
    }

    if (path.equals(browser.logoutReturnPath())) {
      logoutReturnHandler.handle(req, res);
      return;
    }

    chain.next(req, res);
  }
}
