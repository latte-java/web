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
      Tools.clearAllCookies(res, config);

      String desc = req.getURLParameter("error_description");
      desc = desc != null ? desc : error;

      String target = config.postLoginLanding()
          + (config.postLoginLanding().contains("?") ? "&" : "?")
          + "oidc_error=" + URLEncoder.encode(desc, StandardCharsets.UTF_8);
      res.sendRedirect(target);
      return;
    }

    String queryState = req.getURLParameter("state");
    String cookieState = Tools.readCookie(req, config.stateCookieName());
    if (queryState == null || !queryState.equals(cookieState)) {
      res.setStatus(400);
      return;
    }

    String code = req.getURLParameter("code");
    if (code == null || code.isBlank()) {
      res.setStatus(400);
      return;
    }

    URI redirectURI = config.fullRedirectURI(req);
    Tools.TokenEndpointResponse tok;
    try {
      tok = exchangeCode(code, redirectURI, cookieState);
    } catch (Exception e) {
      res.setStatus(500);
      return;
    }

    if (tok.failed()) {
      res.setStatus(400);
      return;
    }

    JsonNode body = Tools.MAPPER.readTree(tok.body());
    String accessToken = Tools.textOrNull(body, "access_token");
    String idToken = Tools.textOrNull(body, "id_token");
    String refreshToken = Tools.textOrNull(body, "refresh_token");
    long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600L;

    if (accessToken == null || idToken == null) {
      res.setStatus(400);
      return;
    }

    // Always verify the id_token signature (OIDC spec requires it).
    try {
      new JWTDecoder().decode(idToken, jwks);
    } catch (Exception e) {
      res.setStatus(400);
      return;
    }

    // When validateAccessToken=true, verify the access-token JWT too.
    if (config.validateAccessToken()) {
      try {
        new JWTDecoder().decode(idToken, jwks);
      } catch (Exception e) {
        res.setStatus(400);
        return;
      }
    }

    String returnTo = Tools.readCookie(req, config.returnToCookieName());
    Tools.addAuthCookies(req, res, config, idToken, accessToken, refreshToken, expiresIn);
    Tools.clearCookie(res, config.stateCookieName());
    Tools.clearCookie(res, config.returnToCookieName());

    res.sendRedirect(returnTo != null && !returnTo.isBlank() ? returnTo : config.postLoginLanding());
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
}
