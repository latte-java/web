package org.lattejava.web.oidc.internal;

import module com.fasterxml.jackson.databind;
import module java.base;
import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

/**
 * Handles the callback from the OIDC provider after the authorization code grant has completed and the IDP redirects
 * back to the app.
 *
 * @author Brian Pontarelli
 */
public class CallbackHandler implements Handler {
  private final OIDCConfig config;

  private final JWKS jwks;

  public CallbackHandler(OIDCConfig config, JWKS jwks) {
    this.config = config;
    this.jwks = jwks;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) throws Exception {
    String error = req.getURLParameter("error");
    if (error != null) {
      String desc = req.getURLParameter("error_description");
      redirectError(res, error, desc != null ? desc : error);
      return;
    }

    String queryState = req.getURLParameter("state");
    String cookieState = Tools.readCookie(req, config.stateCookieName());
    if (queryState == null || !queryState.equals(cookieState)) {
      redirectError(res, "invalid_state", "Invalid state");
      return;
    }

    String code = req.getURLParameter("code");
    if (code == null || code.isBlank()) {
      redirectError(res, "missing_code", "Missing authorization code");
      return;
    }

    URI redirectURI = config.fullRedirectURI(req);
    Tools.TokenEndpointResponse tok;
    try {
      tok = exchangeCode(code, redirectURI, cookieState);
    } catch (Exception e) {
      redirectError(res, "token_exchange_failed", "Token exchange failed");
      return;
    }

    if (tok.failed()) {
      redirectError(res, "token_exchange_failed", "Token exchange failed");
      return;
    }

    JsonNode body = Tools.MAPPER.readTree(tok.body());
    String accessToken = Tools.textOrNull(body, "access_token");
    String idToken = Tools.textOrNull(body, "id_token");
    String refreshToken = Tools.textOrNull(body, "refresh_token");
    long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600L;

    if (accessToken == null || idToken == null) {
      redirectError(res, "token_exchange_failed", "Token exchange failed");
      return;
    }

    // Always verify the id_token signature (OIDC spec requires it).
    try {
      JWT.decode(idToken, jwks);
    } catch (Exception e) {
      redirectError(res, "invalid_id_token", "Invalid ID token");
      return;
    }

    // When validateAccessToken=true, verify the access-token JWT too.
    if (config.validateAccessToken()) {
      try {
        JWT.decode(idToken, jwks);
      } catch (Exception e) {
        redirectError(res, "invalid_access_token", "Invalid access token");
        return;
      }
    }

    String returnTo = Tools.readCookie(req, config.returnToCookieName());
    Tools.addAuthCookies(req, res, config, idToken, accessToken, refreshToken, expiresIn);
    Tools.clearCookie(res, config.stateCookieName());
    Tools.clearCookie(res, config.returnToCookieName());

    res.sendRedirect(returnTo != null && !returnTo.isBlank() ? returnTo : config.postLoginPage());
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

  private void redirectError(HTTPResponse res, String code, String description) {
    Tools.clearAllCookies(res, config);
    String target = config.errorPage()
        + (config.errorPage().contains("?") ? "&" : "?")
        + "oidc_error=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
        + "&oidc_error_description=" + URLEncoder.encode(description, StandardCharsets.UTF_8);
    res.sendRedirect(target);
  }
}
