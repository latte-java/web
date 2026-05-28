/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import module org.lattejava.web;

/**
 * HTML/redirect challenge for the SSR profile. On an authentication failure it either writes a one-shot meta-refresh
 * interstitial (when {@code retryable} and the retry marker is absent — the SameSite cross-site case) or clears
 * credentials, records the return-to URL, and redirects to the login path. Authorization failure and IdP-unavailable
 * delegate to the configured {@link BrowserSettings#forbiddenHandler()} /
 * {@link BrowserSettings#unavailableHandler()}.
 *
 * @author Brian Pontarelli
 */
public class RedirectChallenge implements AuthChallenge {
  public static final String CSR_REDIRECT_PARAM = "csroidcredirect";
  private final BrowserSettings settings;

  public RedirectChallenge(BrowserSettings settings) {
    this.settings = settings;
  }

  @Override
  public void forbidden(HTTPRequest req, HTTPResponse res) throws Exception {
    settings.forbiddenHandler().handle(req, res);
  }

  @Override
  public void unauthenticated(HTTPRequest req, HTTPResponse res, TokenWriter writer, boolean retryable)
      throws Exception {
    if (retryable && req.getURLParameter(CSR_REDIRECT_PARAM) == null) {
      String url = req.getReconstructedURL();
      url += (url.contains("?") ? "&" : "?") + CSR_REDIRECT_PARAM + "=1";
      Tools.writeMetaRefresh(res, url);
      return;
    }

    writer.clear(req, res);
    Tools.addTransientCookie(req, res, settings.returnToCookieName(), req.getBaseURL() + req.getPath());
    res.sendRedirect(settings.loginPath());
  }

  @Override
  public void unavailable(HTTPRequest req, HTTPResponse res) throws Exception {
    settings.unavailableHandler().handle(req, res);
  }
}
