/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.SecureRandom;
import org.lattejava.jwt.domain.JWT;
import org.lattejava.web.*;

/**
 * Middleware that requires a valid authenticated session. Unauthenticated requests trigger an OIDC authorize-redirect;
 * authenticated requests bind the current JWT to the {@link OpenIDConnect} ScopedValue and invoke the downstream chain.
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
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final SecureRandom RANDOM = new SecureRandom();

  final OpenIDConnect<?> oidc;

  public Authenticated(OpenIDConnect<?> oidc) {
    this.oidc = Objects.requireNonNull(oidc, "oidc must not be null");
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    String accessToken = OpenIDConnect.readCookie(req, oidc.config().accessTokenCookieName());
    if (accessToken == null) {
      redirectToLogin(req, res);
      return;
    }

    ValidationOutcome outcome = validate(accessToken);

    if (outcome.status() == ValidationStatus.INVALID) {
      JWT refreshed = attemptRefresh(req, res);
      if (refreshed == null) {
        clearAuthCookies(res);
        redirectToLogin(req, res);
        return;
      }
      outcome = ValidationOutcome.valid(refreshed);
    } else if (outcome.status() == ValidationStatus.NETWORK_ERROR) {
      res.setStatus(503);
      return;
    }

    JWT bound = outcome.jwt();
    ScopedValue.where(OpenIDConnect.CURRENT_JWT, bound).call(() -> {
      chain.next(req, res);
      return null;
    });
  }

  /**
   * Starts a login flow: generates a random state (reused as the PKCE verifier), sets the state + return-to cookies,
   * and redirects to the IdP's authorize endpoint.
   */
  void redirectToLogin(HTTPRequest req, HTTPResponse res) throws Exception {
    byte[] stateBytes = new byte[22]; // 22 bytes -> 44 hex chars; 128 bits of entropy; satisfies PKCE's >=43-char rule
    RANDOM.nextBytes(stateBytes);
    String state = HexFormat.of().formatHex(stateBytes);
    String codeChallenge = computeCodeChallenge(state);

    OIDCConfig config = oidc.config();
    URI redirectURI = oidc.redirectURIFor(req);

    OIDCCookies.setTransient(res, config.stateCookieName(), state, true);
    OIDCCookies.setTransient(res, config.returnToCookieName(), req.getBaseURL() + req.getPath(), true);

    StringBuilder url = new StringBuilder(oidc.authorizeEndpoint().toString());
    url.append(url.indexOf("?") < 0 ? '?' : '&');
    url.append("response_type=code");
    url.append("&client_id=").append(URLEncoder.encode(config.clientId(), StandardCharsets.UTF_8));
    url.append("&redirect_uri=").append(URLEncoder.encode(redirectURI.toString(), StandardCharsets.UTF_8));
    url.append("&scope=").append(URLEncoder.encode(String.join(" ", config.scopes()), StandardCharsets.UTF_8));
    url.append("&state=").append(state);
    url.append("&code_challenge=").append(codeChallenge);
    url.append("&code_challenge_method=S256");

    res.sendRedirect(url.toString());
  }

  private static String computeCodeChallenge(String verifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private ValidationOutcome validate(String accessToken) {
    if (oidc.config().validateAccessToken()) {
      try {
        JWT jwt = JWT.getDecoder().decode(accessToken, oidc.verifierFunction());
        return ValidationOutcome.valid(jwt);
      } catch (Exception e) {
        return ValidationOutcome.invalid();
      }
    }

    OpenIDConnect.UserinfoResult ui = oidc.callUserinfo(accessToken);
    return switch (ui.status()) {
      case VALID -> ValidationOutcome.valid(ui.claims());
      case INVALID -> ValidationOutcome.invalid();
      case NETWORK_ERROR -> ValidationOutcome.networkError();
    };
  }

  private JWT attemptRefresh(HTTPRequest req, HTTPResponse res) {
    String refreshToken = OpenIDConnect.readCookie(req, oidc.config().refreshTokenCookieName());
    if (refreshToken == null) {
      return null;
    }

    OpenIDConnect.TokenEndpointResponse tok;
    try {
      tok = oidc.refresh(refreshToken);
    } catch (Exception e) {
      return null;
    }
    if (!tok.success()) {
      return null;
    }

    JsonNode body;
    try {
      body = MAPPER.readTree(tok.body());
    } catch (Exception e) {
      return null;
    }

    String newAccessToken = textOrNull(body, "access_token");
    String newRefreshToken = textOrNull(body, "refresh_token");
    String newIdToken = textOrNull(body, "id_token");
    long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600L;
    if (newAccessToken == null) {
      return null;
    }

    ValidationOutcome revalidated = validate(newAccessToken);
    if (revalidated.status() != ValidationStatus.VALID) {
      return null;
    }

    OIDCCookies.setAuthCookie(res, oidc.config().accessTokenCookieName(), newAccessToken, expiresIn, true);
    if (newRefreshToken != null) {
      OIDCCookies.setAuthCookie(res, oidc.config().refreshTokenCookieName(), newRefreshToken,
          oidc.config().refreshTokenMaxAge().toSeconds(), true);
    }
    if (newIdToken != null) {
      OIDCCookies.setAuthCookie(res, oidc.config().idTokenCookieName(), newIdToken, expiresIn, false);
    }
    return revalidated.jwt();
  }

  private void clearAuthCookies(HTTPResponse res) {
    OIDCCookies.clear(res, oidc.config().accessTokenCookieName());
    OIDCCookies.clear(res, oidc.config().idTokenCookieName());
    OIDCCookies.clear(res, oidc.config().refreshTokenCookieName());
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }

  enum ValidationStatus { VALID, INVALID, NETWORK_ERROR }

  record ValidationOutcome(ValidationStatus status, JWT jwt) {
    static ValidationOutcome valid(JWT jwt) { return new ValidationOutcome(ValidationStatus.VALID, jwt); }
    static ValidationOutcome invalid() { return new ValidationOutcome(ValidationStatus.INVALID, null); }
    static ValidationOutcome networkError() { return new ValidationOutcome(ValidationStatus.NETWORK_ERROR, null); }
  }
}
