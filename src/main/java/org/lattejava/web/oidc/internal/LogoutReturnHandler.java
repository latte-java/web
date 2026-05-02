/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
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
  private final OIDCConfig config;

  public LogoutReturnHandler(OIDCConfig config) {
    this.config = config;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) throws Exception {
    Tools.clearAllCookies(res, config);
    res.sendRedirect(config.postLogout());
  }
}
