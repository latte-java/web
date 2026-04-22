/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.oidc;

import module java.base;

import org.lattejava.jwt.domain.JWT;

/**
 * Configuration for an {@link OpenIDConnect} instance. Use {@link #builder()} to construct.
 * <p>
 * Either {@code issuer} (for OIDC Discovery) or all four endpoint URLs (authorize, token, userinfo, jwks) must be
 * provided. Mixing is allowed; explicit endpoints override discovered ones.
 *
 * @author Brian Pontarelli
 */
public record OIDCConfig(
    String issuer,
    URI authorizeEndpoint,
    URI tokenEndpoint,
    URI userinfoEndpoint,
    URI jwksEndpoint,
    URI logoutEndpoint,
    String clientId,
    String clientSecret,
    URI redirectURI,
    List<String> scopes,
    Function<JWT, Iterable<String>> roleExtractor,
    boolean validateAccessToken,
    String postLoginLanding,
    String postLogoutLanding,
    String callbackPath,
    String logoutPath,
    String logoutReturnPath,
    String stateCookieName,
    String accessTokenCookieName,
    String refreshTokenCookieName,
    String idTokenCookieName,
    String returnToCookieName,
    Duration refreshTokenMaxAge
) {
  /**
   * @return A new Builder pre-populated with sensible defaults for all optional fields.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link OIDCConfig}. All optional fields are pre-populated with sensible defaults; required fields
   * (issuer or all four endpoints, clientId, clientSecret) must be set explicitly. Call {@link #build()} to produce an
   * immutable config — validation runs at build time and throws {@link IllegalArgumentException} on violation.
   */
  public static class Builder {
    private String accessTokenCookieName = "access_token";
    private URI authorizeEndpoint;
    private String callbackPath = "/oidc/return";
    private String clientId;
    private String clientSecret;
    private String idTokenCookieName = "id_token";
    private String issuer;
    private URI jwksEndpoint;
    private URI logoutEndpoint;
    private String logoutPath = "/oidc/logout";
    private String logoutReturnPath = "/oidc/logout-return";
    private String postLoginLanding = "/";
    private String postLogoutLanding = "/";
    private URI redirectURI;
    private String refreshTokenCookieName = "refresh_token";
    private Duration refreshTokenMaxAge = Duration.ofDays(30);
    private String returnToCookieName = "oidc_return_to";
    private Function<JWT, Iterable<String>> roleExtractor = jwt -> {
      Object raw = jwt.getObject("roles");
      return toIterable(raw);
    };
    private List<String> scopes = List.of("openid", "profile", "email", "offline_access");
    private String stateCookieName = "oidc_state";
    private URI tokenEndpoint;
    private URI userinfoEndpoint;
    private boolean validateAccessToken = true;

    /**
     * Validates the builder state and returns a new immutable {@link OIDCConfig}.
     *
     * @return The immutable config.
     * @throws IllegalArgumentException If any required field is missing or any constraint is violated.
     */
    public OIDCConfig build() {
      if (clientId == null || clientId.isBlank()) {
        throw new IllegalArgumentException("clientId must not be null or blank");
      }
      if (clientSecret == null || clientSecret.isBlank()) {
        throw new IllegalArgumentException("clientSecret must not be null or blank");
      }

      boolean hasIssuer = issuer != null && !issuer.isBlank();
      boolean hasAllEndpoints = authorizeEndpoint != null && tokenEndpoint != null
          && userinfoEndpoint != null && jwksEndpoint != null;
      if (!hasIssuer && !hasAllEndpoints) {
        throw new IllegalArgumentException(
            "Either [issuer] must be set or all four endpoints (authorize, token, userinfo, jwks) must be set");
      }

      if (scopes == null || !scopes.contains("openid")) {
        throw new IllegalArgumentException("scopes must contain [openid]");
      }
      if (roleExtractor == null) {
        throw new IllegalArgumentException("roleExtractor must not be null");
      }

      Set<String> cookieNames = new HashSet<>();
      for (String name : List.of(stateCookieName, accessTokenCookieName, refreshTokenCookieName,
          idTokenCookieName, returnToCookieName)) {
        if (name == null || name.isBlank()) {
          throw new IllegalArgumentException("cookie names must not be null or blank");
        }
        if (!cookieNames.add(name)) {
          throw new IllegalArgumentException("duplicate cookie name: [" + name + "]");
        }
      }

      Set<String> paths = new HashSet<>();
      for (String path : List.of(callbackPath, logoutPath, logoutReturnPath)) {
        if (path == null || !path.startsWith("/")) {
          throw new IllegalArgumentException("path must start with [/]: [" + path + "]");
        }
        if (!paths.add(path)) {
          throw new IllegalArgumentException("duplicate path: [" + path + "]");
        }
      }

      requireSecureURI("issuer", issuer == null ? null : URI.create(issuer));
      requireSecureURI("redirectURI", redirectURI);
      requireSecureURI("logoutEndpoint", logoutEndpoint);

      return new OIDCConfig(issuer, authorizeEndpoint, tokenEndpoint, userinfoEndpoint, jwksEndpoint,
          logoutEndpoint, clientId, clientSecret, redirectURI, scopes, roleExtractor,
          validateAccessToken, postLoginLanding, postLogoutLanding, callbackPath, logoutPath,
          logoutReturnPath, stateCookieName, accessTokenCookieName, refreshTokenCookieName,
          idTokenCookieName, returnToCookieName, refreshTokenMaxAge);
    }

    public Builder accessTokenCookieName(String value) {
      this.accessTokenCookieName = value;
      return this;
    }

    public Builder authorizeEndpoint(URI value) {
      this.authorizeEndpoint = value;
      return this;
    }

    public Builder callbackPath(String value) {
      this.callbackPath = value;
      return this;
    }

    public Builder clientId(String value) {
      this.clientId = value;
      return this;
    }

    public Builder clientSecret(String value) {
      this.clientSecret = value;
      return this;
    }

    public Builder idTokenCookieName(String value) {
      this.idTokenCookieName = value;
      return this;
    }

    public Builder issuer(String value) {
      this.issuer = value;
      return this;
    }

    public Builder jwksEndpoint(URI value) {
      this.jwksEndpoint = value;
      return this;
    }

    public Builder logoutEndpoint(URI value) {
      this.logoutEndpoint = value;
      return this;
    }

    public Builder logoutPath(String value) {
      this.logoutPath = value;
      return this;
    }

    public Builder logoutReturnPath(String value) {
      this.logoutReturnPath = value;
      return this;
    }

    public Builder postLoginLanding(String value) {
      this.postLoginLanding = value;
      return this;
    }

    public Builder postLogoutLanding(String value) {
      this.postLogoutLanding = value;
      return this;
    }

    public Builder redirectURI(URI value) {
      this.redirectURI = value;
      return this;
    }

    public Builder refreshTokenCookieName(String value) {
      this.refreshTokenCookieName = value;
      return this;
    }

    public Builder refreshTokenMaxAge(Duration value) {
      this.refreshTokenMaxAge = value;
      return this;
    }

    public Builder returnToCookieName(String value) {
      this.returnToCookieName = value;
      return this;
    }

    public Builder roleExtractor(Function<JWT, Iterable<String>> value) {
      this.roleExtractor = value;
      return this;
    }

    public Builder scopes(List<String> value) {
      this.scopes = value;
      return this;
    }

    public Builder stateCookieName(String value) {
      this.stateCookieName = value;
      return this;
    }

    public Builder tokenEndpoint(URI value) {
      this.tokenEndpoint = value;
      return this;
    }

    public Builder userinfoEndpoint(URI value) {
      this.userinfoEndpoint = value;
      return this;
    }

    public Builder validateAccessToken(boolean value) {
      this.validateAccessToken = value;
      return this;
    }

    /**
     * Enforces that the URI uses HTTPS, except when the host is a loopback address. This makes local development with
     * {@code http://localhost:9011} (etc.) workable without undermining production security posture.
     */
    private static void requireSecureURI(String field, URI uri) {
      if (uri == null) {
        return;
      }
      String scheme = uri.getScheme();
      if ("https".equalsIgnoreCase(scheme)) {
        return;
      }
      String host = uri.getHost();
      boolean loopback = host != null
          && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host));
      if ("http".equalsIgnoreCase(scheme) && loopback) {
        return;
      }
      throw new IllegalArgumentException(
          field + " must use https (http:// is permitted only for localhost/127.0.0.1/::1): [" + uri + "]");
    }

    /**
     * Coerces the raw value of a JWT claim into an {@link Iterable} of role names. Handles null, single strings,
     * Iterables, arrays, and falls back to {@code String.valueOf} for anything else.
     */
    private static Iterable<String> toIterable(Object raw) {
      if (raw == null) {
        return List.of();
      }
      if (raw instanceof Iterable<?> it) {
        List<String> out = new ArrayList<>();
        for (Object o : it) {
          if (o != null) {
            out.add(String.valueOf(o));
          }
        }
        return out;
      }
      if (raw.getClass().isArray()) {
        Object[] arr = (Object[]) raw;
        List<String> out = new ArrayList<>(arr.length);
        for (Object o : arr) {
          if (o != null) {
            out.add(String.valueOf(o));
          }
        }
        return out;
      }
      return List.of(String.valueOf(raw));
    }
  }
}
