/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module com.fasterxml.jackson.databind;
import module java.base;
import module java.net.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

/**
 * Validates OIDC access tokens. Performs local JWT verification against the JWKS when
 * {@link org.lattejava.web.oidc.OIDCConfig#validateAccessToken()} is {@code true}, and RFC 7662 introspection for
 * opaque tokens when it is {@code false}. Both paths require the {@code aud} claim to contain the configured client id.
 *
 * @author Brian Pontarelli
 */
public class TokenValidator {
  private final OIDCConfig config;
  private final JWKS jwks;

  public TokenValidator(OIDCConfig config, JWKS jwks) {
    this.config = config;
    this.jwks = jwks;
  }

  /**
   * Validates a token against the IdP's RFC 7662 introspection endpoint. Used uniformly when
   * {@link OIDCConfig#validateAccessToken()} is {@code false} (the token is opaque and cannot be decoded locally). The
   * {@link IntrospectionResult.Active} result carries a {@link JWT} built from the introspection response claims, which
   * is the token's only claims source in this mode.
   *
   * @param token The token to introspect.
   * @return {@link IntrospectionResult.Active} (carrying the response claims) when the IdP reports {@code active=true};
   *     {@link IntrospectionResult.Inactive} when it reports inactive or returns a non-5xx error; {@link
   *     IntrospectionResult.NetworkError} on a 5xx response or a thrown exception.
   */
  public IntrospectionResult introspect(String token) {
    try {
      String body = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&token_type_hint=access_token";
      String basic = "Basic " + Base64.getEncoder().encodeToString(
          (config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8)
      );
      HttpRequest req = HttpRequest.newBuilder(config.introspectionEndpoint())
                                   .header("Authorization", basic)
                                   .header("Content-Type", "application/x-www-form-urlencoded")
                                   .POST(HttpRequest.BodyPublishers.ofString(body))
                                   .build();
      HttpResponse<String> res = Tools.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      int status = res.statusCode();
      if (status >= 500 && status <= 599) {
        return new IntrospectionResult.NetworkError();
      }

      if (status != 200) {
        return new IntrospectionResult.Inactive();
      }

      JsonNode json = Tools.MAPPER.readTree(res.body());
      JsonNode active = json.get("active");
      if (active != null && active.asBoolean(false)) {
        return new IntrospectionResult.Active(Tools.jsonToJWT(json));
      }

      return new IntrospectionResult.Inactive();
    } catch (Exception e) {
      return new IntrospectionResult.NetworkError();
    }
  }

  /**
   * Validates an access token. When {@link OIDCConfig#validateAccessToken()} is {@code true} the token is decoded
   * locally as a JWT against the configured JWKS; when it is {@code false} the token is validated via RFC 7662
   * introspection (which also handles opaque tokens). In either case the audience claim must contain the configured
   * client ID.
   *
   * @param token The access token to validate.
   * @return The validation result: {@link Result.Valid} carrying the bound JWT, {@link Result.Invalid} for any
   *     non-network failure, or {@link Result.NetworkError} when the IdP is unreachable.
   */
  public Result validate(String token) {
    if (config.validateAccessToken()) {
      try {
        var jwt = JWT.decode(token, jwks, this::validateJWT);
        return new Result.Valid(jwt);
      } catch (Exception e) {
        return new Result.Invalid();
      }
    }

    // validateAccessToken=false: the access token may be opaque; ask the IdP via RFC 7662 introspection.
    // The introspection response is the only claims source and supplies the aud claim for the audience check.
    IntrospectionResult result = introspect(token);
    if (result instanceof IntrospectionResult.NetworkError) {
      return new Result.NetworkError();
    }

    if (result instanceof IntrospectionResult.Active(JWT jwt)) {
      try {
        validateJWT(jwt);
      } catch (Exception e) {
        return new Result.Invalid();
      }

      return new Result.Valid(jwt);
    }

    return new Result.Invalid();
  }

  private void validateJWT(JWT jwt) {
    if (!jwt.audience().contains(config.clientId())) {
      throw new InvalidJWTException("The aud claim [" + jwt.audience() + "] does not contain the client id [" + config.clientId() + "]");
    }
  }

  /**
   * Outcome of a call to the introspection endpoint. {@link Active} carries the claims parsed from the introspection
   * response, which are the token's claims source in introspection ({@code validateAccessToken=false}) mode.
   */
  public sealed interface IntrospectionResult
      permits IntrospectionResult.Active, IntrospectionResult.Inactive, IntrospectionResult.NetworkError {
    record Active(JWT jwt) implements IntrospectionResult {
    }

    record Inactive() implements IntrospectionResult {
    }

    record NetworkError() implements IntrospectionResult {
    }
  }

  /**
   * Outcome of a token validation — JWKS decode (JWT mode) or RFC 7662 introspection (opaque mode).
   */
  public sealed interface Result permits Result.Invalid, Result.NetworkError, Result.Valid {
    record Invalid() implements Result {
    }

    record NetworkError() implements Result {
    }

    record Valid(JWT jwt) implements Result {
    }
  }
}
