package org.lattejava.web.oidc.internal;

import module com.fasterxml.jackson.databind;
import module java.net.http;
import module jwt;
import module org.lattejava.web;

public class TokenValidator {
  private final OIDCConfig config;
  private final JWKS jwks;

  public TokenValidator(OIDCConfig config, JWKS jwks) {
    this.config = config;
    this.jwks = jwks;
  }

  public Result validate(String token, boolean accessToken) {
    // If we are validating the access-token, verify it as a JWT
    if (!accessToken || config.validateAccessToken()) {
      try {
        var jwt = JWT.parse(token, jwks, this::validateJWT);
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
        if (!validateJWT(jwt)) {
          return new Result.Invalid();
        }

        return new Result.Valid(jwt);
      }

      if (status == 401) {
        return new Result.Invalid();
      }

      return new Result.NetworkError();
    } catch (Exception e) {
      return new Result.NetworkError();
    }
  }

  private boolean validateJWT(JWT jwt) {
    return jwt.audience().contains(config.clientId());
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
