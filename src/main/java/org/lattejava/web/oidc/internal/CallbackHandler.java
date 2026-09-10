/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

/**
 * Handles the IdP's redirect back to the app after the authorization code grant. Checks the state, exchanges the code
 * using the state as the PKCE verifier, verifies the returned tokens, writes them through the token writer, and
 * redirects to the return-to URL or the post-login page. Any failure clears credentials and redirects to the error page
 * with {@code oidc_error} and {@code oidc_error_description} query parameters.
 *
 * @author Brian Pontarelli
 */
public class CallbackHandler implements Handler {
  private final BrowserSettings browser;
  private final OIDCConfig config;
  private final JWKS jwks;

  public CallbackHandler(OIDCConfig config, BrowserSettings browser, JWKS jwks) {
    this.config = config;
    this.browser = browser;
    this.jwks = jwks;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) throws Exception {
    String error = req.getURLParameter("error");
    if (error != null) {
      String desc = req.getURLParameter("error_description");
      redirectError(req, res, error, desc != null ? desc : error);
      return;
    }

    String queryState = req.getURLParameter("state");
    String cookieState = Tools.readCookie(req, browser.stateCookieName());
    if (queryState == null || !queryState.equals(cookieState)) {
      redirectError(req, res, "invalid_state", "Invalid state");
      return;
    }

    String code = req.getURLParameter("code");
    if (code == null || code.isBlank()) {
      redirectError(req, res, "missing_code", "Missing authorization code");
      return;
    }

    URI redirectURI = URI.create(req.getBaseURL() + browser.callbackPath());
    Tools.TokenEndpointResponse tok;
    try {
      tok = exchangeCode(code, redirectURI, cookieState);
    } catch (Exception e) {
      redirectError(req, res, "token_exchange_failed", "Token exchange failed");
      return;
    }

    if (tok.failed()) {
      redirectError(req, res, "token_exchange_failed", "Token exchange failed");
      return;
    }

    Tokens tokens = TokensJSON.fromJSON(tok.body());
    if (tokens.accessToken() == null || tokens.idToken() == null) {
      redirectError(req, res, "token_exchange_failed", "Token exchange failed");
      return;
    }

    // Always verify the id_token signature (OIDC spec requires it).
    try {
      JWT.decode(tokens.idToken(), jwks);
    } catch (Exception e) {
      redirectError(req, res, "invalid_id_token", "Invalid ID token");
      return;
    }

    // When validateAccessToken=true, verify the access-token JWT too.
    if (config.validateAccessToken()) {
      try {
        JWT.decode(tokens.accessToken(), jwks);
      } catch (Exception e) {
        redirectError(req, res, "invalid_access_token", "Invalid access token");
        return;
      }
    }

    String returnTo = Tools.readCookie(req, browser.returnToCookieName());
    browser.tokenWriter().write(req, res, tokens);
    Tools.clearCookie(req, res, browser.stateCookieName());
    Tools.clearCookie(req, res, browser.returnToCookieName());

    res.sendRedirect(returnTo != null && !returnTo.isBlank() ? returnTo : browser.postLoginPage());
  }

  private Tools.TokenEndpointResponse exchangeCode(String code, URI redirectURI, String codeVerifier)
      throws IOException, InterruptedException {
    return Tools.postToken(config,
        Map.of(
            "grant_type", "authorization_code",
            "code", code,
            "redirect_uri", redirectURI.toString(),
            "code_verifier", codeVerifier
        )
    );
  }

  private void redirectError(HTTPRequest req, HTTPResponse res, String code, String description) {
    browser.tokenWriter().clear(req, res);
    Tools.clearCookie(req, res, browser.stateCookieName());
    Tools.clearCookie(req, res, browser.returnToCookieName());
    String target = browser.errorPage()
        + (browser.errorPage().contains("?") ? "&" : "?")
        + "oidc_error=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
        + "&oidc_error_description=" + URLEncoder.encode(description, StandardCharsets.UTF_8);
    res.sendRedirect(target);
  }
}
