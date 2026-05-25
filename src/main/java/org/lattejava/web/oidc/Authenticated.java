/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module com.fasterxml.jackson.databind;
import module java.base;
import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

/**
 * Middleware that requires a valid authenticated session. Unauthenticated requests trigger an OIDC authorize-redirect;
 * authenticated requests bind the current JWT to the {@link OIDC} ScopedValue and invoke the downstream chain.
 * <p>
 * When {@code validateAccessToken=true}, the access-token cookie is verified as a JWT against the IdP's JWKS on every
 * request, including an audience check that the {@code aud} claim contains the configured client id. When {@code
 * false}, the cookie value is treated as an opaque access token and validated at the IdP's RFC 7662 introspection
 * endpoint; the introspection response is wrapped as a {@link JWT} for ScopedValue binding and is subject to the same
 * audience check.
 * <p>
 * On expired access token, the middleware attempts a refresh using the {@code refresh_token} cookie before falling back
 * to {@link #unauthorized(HTTPRequest, HTTPResponse)}. Subclasses may override that hook to change the unauthenticated
 * response (e.g. {@link JWTAuthenticated} returns 401 instead of redirecting).
 *
 * @author Brian Pontarelli
 */
public class Authenticated implements Middleware {
  public static final String CSR_REDIRECT_PARAM = "csroidcredirect";
  protected final OIDCConfig config;
  private final TokenValidator tokenValidator;

  Authenticated(OIDCConfig config, JWKS jwks) {
    this.config = config;
    this.tokenValidator = new TokenValidator(config, jwks);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    // No token means the user isn't logged in
    String accessToken = Tools.readCookie(req, config.accessTokenCookieName());
    if (accessToken == null) {
      unauthorized(req, res);
      return;
    }

    // Try to re-validate the access token
    TokenValidator.Result result = tokenValidator.validate(accessToken, true);
    TokenValidator.Result.Valid valid;
    if (result instanceof TokenValidator.Result.Invalid) {
      // Check if a refresh is possible
      String refreshToken = Tools.readCookie(req, config.refreshTokenCookieName());
      boolean notRedirected = req.getURLParameter(CSR_REDIRECT_PARAM) == null;
      if (refreshToken == null && notRedirected) {
        // This case happens when the browser is handling SameSite Strict and a Cross-Site Request (or the refresh
        // token was deleted by the user or some other code). Therefore, we can send the user to an interstitial page
        // that does a refresh back to the original page via a meta-refresh. And if that fails, then it is certain that
        // they need to log in again. For V1, I'm just gonna write out a meta-refresh here manually to break the
        // SameSite restriction.

        // This prevents infinite redirect loops
        String url = req.getReconstructedURL();
        if (url.contains("?")) {
          url += "&" + CSR_REDIRECT_PARAM + "=1";
        } else {
          url += "?" + CSR_REDIRECT_PARAM + "=1";
        }

        Tools.writeMetaRefresh(res, url);
        return;
      }

      JWT refreshed = attemptRefresh(req, res);
      if (refreshed == null) {
        unauthorized(req, res);
        return;
      }

      valid = new TokenValidator.Result.Valid(refreshed);
    } else if (result instanceof TokenValidator.Result.NetworkError) {
      res.setStatus(503);
      return;
    } else {
      valid = (TokenValidator.Result.Valid) result;
    }

    JWT bound = valid.jwt();
    ScopedValue.where(Tools.CURRENT_JWT, bound).call(() -> {
      chain.next(req, res);
      return null;
    });
  }

  /**
   * Handles a request that has no valid access token and could not be refreshed. The default implementation clears the
   * auth cookies, captures the requested URL in the return-to cookie, and redirects to the configured login path.
   * Subclasses may override this hook to change the response — for example, {@link JWTAuthenticated} sends a 401 for
   * API clients that expect a status code rather than a redirect.
   *
   * @param req The current request.
   * @param res The response to populate.
   * @throws Exception If the response cannot be written.
   */
  protected void unauthorized(HTTPRequest req, HTTPResponse res) throws Exception {
    // This assumes the access, id, and refresh tokens are toast, but the returnTo cookie might still be used for the
    // final redirect, so it is set here because this is the requested URL we should return to
    Tools.clearAllAuthCookies(req, res, config);
    Tools.addTransientCookie(req, res, config.returnToCookieName(), req.getBaseURL() + req.getPath());
    res.sendRedirect(config.loginPath());
  }

  private JWT attemptRefresh(HTTPRequest req, HTTPResponse res) {
    String refreshToken = Tools.readCookie(req, config.refreshTokenCookieName());
    if (refreshToken == null) {
      return null;
    }

    Tokens result = Tools.refresh(config, refreshToken);
    if (result == null) {
      return null;
    }

    TokenValidator.Result revalidated = tokenValidator.validate(result.accessToken(), true);
    if (revalidated instanceof TokenValidator.Result.Valid(JWT jwt)) {
      long expiresIn = result.expiresIn() != null ? result.expiresIn() : 3600L;
      Tools.addAuthCookies(req, res, config, result.idToken(), result.accessToken(), result.refreshToken(), expiresIn);
      return jwt;
    }

    return null;
  }
}
