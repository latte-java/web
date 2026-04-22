/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.oidc;

import module java.base;

/**
 * Configuration for an {@link OpenIDConnect} instance. Use {@link #builder()} to construct.
 * <p>
 * Either {@code issuer} (for OIDC Discovery) or all four endpoint URLs (authorize, token,
 * userinfo, jwks) must be provided. Mixing is allowed; explicit endpoints override discovered ones.
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
    Function<Object, Iterable<String>> roleExtractor,
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
   * @return A new {@link Builder} pre-populated with default values.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link OIDCConfig}. All optional fields are pre-populated with sensible defaults; required fields
   * (issuer or endpoints, clientId, clientSecret, redirectURI) must be set explicitly.
   */
  public static class Builder {
    private URI authorizeEndpoint;
    private String accessTokenCookieName = "access_token";
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
    private Duration refreshTokenMaxAge = Duration.ofDays(30);
    private String refreshTokenCookieName = "refresh_token";
    private String returnToCookieName = "oidc_return_to";
    private Function<Object, Iterable<String>> roleExtractor = jwt -> {
      // Replaced in Task 5 once the JWT library API is verified; placeholder default.
      return List.of();
    };
    private List<String> scopes = List.of("openid", "profile", "email", "offline_access");
    private String stateCookieName = "oidc_state";
    private URI tokenEndpoint;
    private URI userinfoEndpoint;
    private boolean validateAccessToken = true;

    /**
     * @return A new {@link OIDCConfig} with the current builder values.
     */
    public OIDCConfig build() {
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

    public Builder roleExtractor(Function<Object, Iterable<String>> value) {
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
  }
}
