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

public class TokenValidator {
  private final OIDCConfig config;
  private final JWKS jwks;

  public TokenValidator(OIDCConfig config, JWKS jwks) {
    this.config = config;
    this.jwks = jwks;
  }

  /**
   * Validates a token against the IdP's RFC 7662 introspection endpoint. Used purely as a validity/revocation gate for
   * API authentication — the response claims are ignored; callers source claims by decoding the access token as a JWT.
   *
   * @param token The token to introspect.
   * @return {@link IntrospectionResult.Active} when the IdP reports {@code active=true}; {@link
   *     IntrospectionResult.Inactive} when it reports inactive or returns a non-5xx error; {@link
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
        return new IntrospectionResult.Active();
      }

      return new IntrospectionResult.Inactive();
    } catch (Exception e) {
      return new IntrospectionResult.NetworkError();
    }
  }

  public Result validate(String token, boolean accessToken) {
    // If we are validating the access-token, verify it as a JWT
    if (!accessToken || config.validateAccessToken()) {
      try {
        var jwt = JWT.decode(token, jwks, this::validateJWT);
        return new Result.Valid(jwt);
      } catch (Exception e) {
        return new Result.Invalid();
      }
    }

    // This branch is `accessToken=true AND validateAccessToken=false`, so we can use userinfo
    try {
      HttpRequest req = HttpRequest.newBuilder(config.userinfoEndpoint())
                                   .header("Authorization", "Bearer " + token)
                                   .GET()
                                   .build();
      HttpResponse<String> res = Tools.HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      int status = res.statusCode();
      if (status == 200) {
        JsonNode json = Tools.MAPPER.readTree(res.body());
        var jwt = Tools.userinfoToJWT(json);
        return new Result.Valid(jwt);
      }

      if (status >= 500 && status <= 599) {
        return new Result.NetworkError();
      }

      return new Result.Invalid();
    } catch (Exception e) {
      return new Result.NetworkError();
    }
  }

  private void validateJWT(JWT jwt) {
    if (!jwt.audience().contains(config.clientId())) {
      throw new InvalidJWTException("The aud claim " + jwt.audience() + " does not contain the client id [" + config.clientId() + "]");
    }
  }

  /**
   * Outcome of a call to the introspection endpoint. Carries no claims — introspection is a pure validity gate.
   */
  public sealed interface IntrospectionResult
      permits IntrospectionResult.Active, IntrospectionResult.Inactive, IntrospectionResult.NetworkError {
    record Active() implements IntrospectionResult {
    }

    record Inactive() implements IntrospectionResult {
    }

    record NetworkError() implements IntrospectionResult {
    }
  }

  /**
   * Outcome of a call to the userinfo endpoint.
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
