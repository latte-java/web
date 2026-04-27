/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

/**
 * Configuration for an {@link OIDC} instance. Use {@link #builder()} to construct.
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
    List<String> scopes,
    Function<JWT, Set<String>> roleExtractor,
    boolean validateAccessToken,
    String errorPage,
    String postLoginPage,
    String postLogoutPage,
    String loginPath,
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
   * Builds the full redirect URI for the OIDC callback using the current URL and the path configured.
   *
   * @param req The current HTTP request.
   * @return The full redirect URI.
   */
  public URI fullRedirectURI(HTTPRequest req) {
    return URI.create(req.getBaseURL() + callbackPath());
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
    private String errorPage = "/";
    private String idTokenCookieName = "id_token";
    private String issuer;
    private URI jwksEndpoint;
    private String loginPath = "/login";
    private URI logoutEndpoint;
    private String logoutPath = "/logout";
    private String logoutReturnPath = "/oidc/logout-return";
    private String postLoginPage = "/";
    private String postLogoutPage = "/";
    private String refreshTokenCookieName = "refresh_token";
    private Duration refreshTokenMaxAge = Duration.ofDays(30);
    private String returnToCookieName = "oidc_return_to";
    private Function<JWT, Set<String>> roleExtractor = jwt -> {
      var roles = jwt.getList("roles", String.class);
      return new HashSet<>(roles);
    };
    private List<String> scopes = List.of("openid", "profile", "email", "offline_access");
    private String stateCookieName = "oidc_state";
    private URI tokenEndpoint;
    private URI userinfoEndpoint;
    private boolean validateAccessToken = true;

    public Builder accessTokenCookieName(String value) {
      this.accessTokenCookieName = value;
      return this;
    }

    public Builder authorizeEndpoint(URI value) {
      this.authorizeEndpoint = value;
      return this;
    }

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

      Tools.requireSecureURI("issuer", issuer == null ? null : URI.create(issuer));
      Tools.requireSecureURI("authorizeEndpoint", authorizeEndpoint);
      Tools.requireSecureURI("jwksEndpoint", jwksEndpoint);
      Tools.requireSecureURI("logoutEndpoint", logoutEndpoint);
      Tools.requireSecureURI("tokenEndpoint", tokenEndpoint);
      Tools.requireSecureURI("userinfoEndpoint", userinfoEndpoint);
      Tools.requireSecureURI("logoutEndpoint", logoutEndpoint);

      fillIn();

      if (authorizeEndpoint == null || tokenEndpoint == null || jwksEndpoint == null) {
        throw new IllegalStateException("Required endpoint unresolved — set issuer or provide explicit authorize/token/jwks endpoints");
      }

      if (!validateAccessToken && userinfoEndpoint == null) {
        throw new IllegalStateException("validateAccessToken=false requires userinfoEndpoint — set explicitly or provide issuer for discovery");
      }

      return new OIDCConfig(issuer, authorizeEndpoint, tokenEndpoint, userinfoEndpoint, jwksEndpoint,
          logoutEndpoint, clientId, clientSecret, scopes, roleExtractor,
          validateAccessToken, errorPage, postLoginPage, postLogoutPage, loginPath, callbackPath, logoutPath,
          logoutReturnPath, stateCookieName, accessTokenCookieName, refreshTokenCookieName,
          idTokenCookieName, returnToCookieName, refreshTokenMaxAge);
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

    public Builder errorPage(String value) {
      this.errorPage = value;
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

    public Builder loginPath(String value) {
      this.loginPath = value;
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

    public Builder postLoginPage(String value) {
      this.postLoginPage = value;
      return this;
    }

    public Builder postLogoutPage(String value) {
      this.postLogoutPage = value;
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

    public Builder roleExtractor(Function<JWT, Set<String>> value) {
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

    private void fillIn() {
      if (issuer == null) {
        return;
      }

      OpenIDConnectConfiguration config;
      try {
        config = OpenIDConnect.discover(issuer);
      } catch (Exception e) {
        throw new IllegalStateException("Failed to fetch OIDC discovery document for issuer [" + issuer + "]", e);
      }

      if (authorizeEndpoint == null && config.authorizationEndpoint() != null) {
        authorizeEndpoint = URI.create(config.authorizationEndpoint());
      }

      if (tokenEndpoint == null && config.tokenEndpoint() != null) {
        tokenEndpoint = URI.create(config.tokenEndpoint());
      }

      if (userinfoEndpoint == null && config.userinfoEndpoint() != null) {
        userinfoEndpoint = URI.create(config.userinfoEndpoint());
      }

      if (jwksEndpoint == null && config.jwksURI() != null) {
        jwksEndpoint = URI.create(config.jwksURI());
      }

      if (logoutEndpoint == null && config.endSessionEndpoint() != null) {
        logoutEndpoint = URI.create(config.endSessionEndpoint());
      }
    }
  }
}
