/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

/**
 * Middleware that authenticates an API request carrying its own tokens. Install once at the common API prefix (e.g.
 * {@code /api}) via {@link OIDC#apiAuthenticated()}.
 * <p>
 * On each request it extracts the tokens (via the configured {@link TokenExtractor}), validates the access token at the
 * IdP's RFC 7662 introspection endpoint, and — if introspection reports the token inactive — attempts a reactive
 * refresh using the refresh token. The validated access token (original or refreshed) is then decoded as a {@link JWT}
 * against JWKS and bound to the {@link OIDC} ScopedValue before the chain continues. The decoded JWT, not the
 * introspection response, carries the claims; introspection is purely a validity/revocation gate.
 * <p>
 * Authorization (whether the token may call a given API) is delegated separately to {@link APIAuthorized}. Failures are
 * communicated as status codes (no redirects): {@code 401} for a missing/invalid token that cannot be refreshed,
 * {@code 503} when introspection cannot reach the IdP, and {@code 403} (from {@link APIAuthorized}) when the authorizer
 * denies the request.
 *
 * @author Brian Pontarelli
 */
public class APIAuthenticated implements Middleware {
  protected final OIDCConfig config;
  private final JWKS jwks;
  private final TokenValidator tokenValidator;

  APIAuthenticated(OIDCConfig config, JWKS jwks) {
    this.config = config;
    this.jwks = jwks;
    this.tokenValidator = new TokenValidator(config, jwks);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    Tokens tokens = config.apiTokenExtractor().extract(req);
    String accessToken = tokens.accessToken();
    if (accessToken == null) {
      res.setStatus(401);
      return;
    }

    TokenValidator.IntrospectionResult result = tokenValidator.introspect(accessToken);
    if (result instanceof TokenValidator.IntrospectionResult.NetworkError) {
      res.setStatus(503);
      return;
    }

    if (result instanceof TokenValidator.IntrospectionResult.Inactive) {
      String refreshToken = tokens.refreshToken();
      if (refreshToken == null) {
        res.setStatus(401);
        return;
      }

      Tokens refreshed = Tools.refresh(config, refreshToken);
      if (refreshed == null) {
        res.setStatus(401);
        return;
      }

      // The refreshed access token came directly from the IdP, so it is trusted without re-introspection.
      accessToken = refreshed.accessToken();
      config.apiTokenWriter().write(req, res, refreshed);
    }

    JWT jwt;
    try {
      jwt = JWT.decode(accessToken, jwks);
    } catch (Exception e) {
      res.setStatus(401);
      return;
    }

    ScopedValue.where(Tools.CURRENT_JWT, jwt).call(() -> {
      chain.next(req, res);
      return null;
    });
  }
}
