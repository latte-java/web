/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
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
 * request. When {@code false}, the cookie value is treated as an opaque access token and validated by calling the
 * userinfo endpoint; the userinfo response is wrapped as a {@link JWT} for ScopedValue binding.
 * <p>
 * On expired access token, the middleware attempts a refresh using the {@code refresh_token} cookie before falling back
 * to a login redirect.
 *
 * @author Brian Pontarelli
 */
public class Authenticated implements Middleware {
  private final OIDCConfig config;
  private final JWKS jwks;
  private final TokenValidator tokenValidator;

  Authenticated(OIDCConfig config, JWKS jwks) {
    this.config = config;
    this.jwks = jwks;
    this.tokenValidator = new TokenValidator(config, jwks);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    // No token means the user isn't logged in
    String accessToken = Tools.readCookie(req, config.accessTokenCookieName());
    if (accessToken == null) {
      // This assumes the id and refresh tokens are toast, but the returnTo cookie might still be used for the final
      // redirect, so it is set here because this is the requested URL we should return to
      Tools.clearAllAuthCookies(res, config);
      Tools.addTransientCookie(req, res, config.returnToCookieName(), req.getBaseURL() + req.getPath());
      res.sendRedirect(config.loginPath());
      return;
    }

    // Try to re-validate the access token
    TokenValidator.Result result = tokenValidator.validate(accessToken, true);
    TokenValidator.Result.Valid valid;
    if (result instanceof TokenValidator.Result.Invalid) {
      JWT refreshed = attemptRefresh(req, res);
      if (refreshed == null) {
        // This assumes the access, id, and refresh tokens are toast, but the returnTo cookie might still be used for
        // the final redirect, so it is set here because this is the requested URL we should return to
        Tools.clearAllAuthCookies(res, config);
        Tools.addTransientCookie(req, res, config.returnToCookieName(), req.getBaseURL() + req.getPath());
        res.sendRedirect(config.loginPath());
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

  private JWT attemptRefresh(HTTPRequest req, HTTPResponse res) {
    String refreshToken = Tools.readCookie(req, config.refreshTokenCookieName());
    if (refreshToken == null) {
      return null;
    }

    Tools.TokenEndpointResponse tokenResponse;
    try {
      tokenResponse = refresh(refreshToken);
    } catch (Exception e) {
      return null;
    }

    if (tokenResponse.failed()) {
      return null;
    }

    JsonNode body;
    try {
      body = Tools.MAPPER.readTree(tokenResponse.body());
    } catch (Exception e) {
      return null;
    }

    String newAccessToken = Tools.textOrNull(body, "access_token");
    String newRefreshToken = Tools.textOrNull(body, "refresh_token");
    String newIdToken = Tools.textOrNull(body, "id_token");
    long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600L;
    if (newAccessToken == null) {
      return null;
    }

    TokenValidator.Result revalidated = tokenValidator.validate(newAccessToken, true);
    if (revalidated instanceof TokenValidator.Result.Valid(JWT jwt)) {
      Tools.addAuthCookies(req, res, config, newIdToken, newAccessToken, newRefreshToken, expiresIn);
      return jwt;
    }

    return null;
  }

  private Tools.TokenEndpointResponse refresh(String refreshToken) throws IOException, InterruptedException {
    return Tools.postToken(config,
        Map.of(
            "grant_type", "refresh_token",
            "refresh_token", refreshToken
        )
    );
  }
}
