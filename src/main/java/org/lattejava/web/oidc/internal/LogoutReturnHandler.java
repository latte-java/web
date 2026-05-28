/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Handles the return from the OIDC logout endpoint.
 *
 * @author Brian Pontarelli
 */
public class LogoutReturnHandler implements Handler {
  private final BrowserSettings browser;

  public LogoutReturnHandler(BrowserSettings browser) {
    this.browser = browser;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) throws Exception {
    browser.tokenWriter().clear(req, res);
    Tools.clearCookie(req, res, browser.stateCookieName());
    Tools.clearCookie(req, res, browser.returnToCookieName());
    res.sendRedirect(browser.postLogoutPage());
  }
}
