/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

import org.lattejava.web.oidc.internal.CallbackHandler;

/**
 * The OpenID Connect entry point for Latte Web.
 * <p>
 * Install the instance at the root with {@code web.install(oidc)} — it handles the callback, logout, and logout-return
 * paths by virtue of being a {@link Middleware} itself. Attach protection where needed via {@link #authenticated()},
 * {@link #jwtAuthenticated()}, {@link #hasAnyRole(String...)}, or {@link #hasAllRoles(String...)}, which are thin
 * factories around the public {@link Authenticated}, {@link JWTAuthenticated}, {@link HasAnyRole}, and
 * {@link HasAllRoles} middlewares.
 * <p>
 * Request-scoped access to the authenticated JWT is available via the static {@link #jwt()} / {@link #optionalJWT()}
 * and the instance-level {@link #user()} / {@link #optionalUser()}, which apply the configured translator.
 *
 * @param <U> The translated-user type. Use {@link #create(OIDCConfig)} for the untyped {@code OpenIDConnect<JWT>} or
 *            {@link #create(OIDCConfig, Function)} with a custom translator.
 * @author Brian Pontarelli
 */
public class OIDC<U> implements Middleware {
  private final CallbackHandler callbackHandler;
  private final OIDCConfig config;
  private final JWKS jwks;
  private final LoginHandler loginHandler;
  private final LogoutHandler logoutHandler;
  private final LogoutReturnHandler logoutReturnHandler;
  private final Function<JWT, U> translator;

  private OIDC(OIDCConfig config, Function<JWT, U> translator) {
    this.config = config;
    this.translator = translator;
    this.jwks = JWKS.fromJWKS(config.jwksEndpoint().toString()).build();

    this.callbackHandler = new CallbackHandler(config, jwks);
    this.loginHandler = new LoginHandler(config);
    this.logoutHandler = new LogoutHandler(config);
    this.logoutReturnHandler = new LogoutReturnHandler(config);
  }

  /**
   * Constructs an untyped {@code OpenIDConnect<JWT>} — {@link #user()} returns the bound JWT directly.
   *
   * @param config The configuration.
   * @return The constructed OpenIDConnect instance.
   * @throws IllegalStateException If discovery fails or a required endpoint can't be resolved.
   */
  public static OIDC<JWT> create(OIDCConfig config) {
    return new OIDC<>(config, Function.identity());
  }

  /**
   * Constructs a typed OpenIDConnect, applying the translator on every call to {@link #user()}.
   *
   * @param config     The configuration.
   * @param translator Maps a JWT to the user's domain object.
   * @return The constructed OpenIDConnect instance.
   * @throws IllegalStateException If discovery fails or a required endpoint can't be resolved.
   */
  public static <U> OIDC<U> create(OIDCConfig config, Function<JWT, U> translator) {
    return new OIDC<>(config, translator);
  }

  /**
   * Returns the raw JWT bound to the current request. Throws if called outside a protected route.
   *
   * @return The bound JWT.
   * @throws UnauthenticatedException If no JWT is currently bound.
   */
  public static JWT jwt() {
    if (!Tools.CURRENT_JWT.isBound()) {
      throw new UnauthenticatedException();
    }
    return Tools.CURRENT_JWT.get();
  }

  /**
   * Returns the raw JWT bound to the current request, or empty if none is bound.
   *
   * @return An Optional carrying the bound JWT or empty.
   */
  public static Optional<JWT> optionalJWT() {
    return Tools.CURRENT_JWT.isBound() ? Optional.of(Tools.CURRENT_JWT.get()) : Optional.empty();
  }

  /**
   * Creates the API authentication middleware. Install once at the common API prefix (e.g. {@code /api}); it validates
   * the request's access token via introspection, refreshes it reactively, and binds the decoded JWT. Authorization is
   * applied separately via {@link #apiAuthorized(APIAuthorizer)}.
   *
   * @return A new {@link APIAuthenticated} middleware bound to this OpenIDConnect instance.
   * @throws IllegalStateException If no introspection endpoint is configured (set it explicitly or provide an issuer
   *                               whose discovery document advertises {@code introspection_endpoint}).
   */
  public APIAuthenticated apiAuthenticated() {
    if (config.introspectionEndpoint() == null) {
      throw new IllegalStateException("apiAuthenticated() requires an introspection endpoint — set introspectionEndpoint explicitly or provide an issuer whose discovery advertises [introspection_endpoint]");
    }
    return new APIAuthenticated(config, jwks);
  }

  /**
   * Creates an API authorization middleware that delegates the access decision to the given authorizer. Install
   * downstream of {@link #apiAuthenticated()} — per sub-API and optionally as a baseline at the API prefix. Authorizers
   * compose additively along the prefix chain (all must pass).
   *
   * @param authorizer The application-supplied access decision.
   * @return A new {@link APIAuthorized} middleware.
   */
  public APIAuthorized apiAuthorized(APIAuthorizer authorizer) {
    return new APIAuthorized(authorizer);
  }

  /**
   * @return A new {@link Authenticated} middleware bound to this OpenIDConnect instance.
   */
  public Authenticated authenticated() {
    return new Authenticated(config, jwks);
  }

  /**
   * System middleware dispatch: handles {@code callbackPath}, {@code logoutPath}, and {@code logoutReturnPath}. Any
   * other path passes through to the chain.
   */
  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    String path = req.getPath();
    if (path.equals(config.callbackPath())) {
      callbackHandler.handle(req, res);
      return;
    }

    if (path.equals(config.loginPath())) {
      loginHandler.handle(req, res);
      return;
    }

    if (path.equals(config.logoutPath())) {
      logoutHandler.handle(req, res);
      return;
    }

    if (path.equals(config.logoutReturnPath())) {
      logoutReturnHandler.handle(req, res);
      return;
    }

    chain.next(req, res);
  }

  /**
   * @param roles One or more roles; the middleware requires the authenticated user to possess every listed role.
   * @return A new {@link HasAllRoles} middleware bound to this OpenIDConnect instance.
   */
  public HasAllRoles hasAllRoles(String... roles) {
    return new HasAllRoles(config, roles);
  }

  /**
   * @param roles One or more roles; the middleware lets the request through when the authenticated user has at least
   *              one of them.
   * @return A new {@link HasAnyRole} middleware bound to this OpenIDConnect instance.
   */
  public HasAnyRole hasAnyRole(String... roles) {
    return new HasAnyRole(config, roles);
  }

  /**
   * @return A new {@link JWTAuthenticated} middleware bound to this OpenIDConnect instance. Use this for browser flows
   *     (or some API client flows) that call API endpoints using the same cookies that the browser flows use, except
   *     these flows expect a 401 on failure instead of a login redirect. Specifically, `fetch` and SPA flows will use
   *     this middleware extensively.
   */
  public JWTAuthenticated jwtAuthenticated() {
    return new JWTAuthenticated(config, jwks);
  }

  /**
   * Translates the JWT bound to the current request into the configured user type, or returns empty if no JWT is
   * bound.
   *
   * @return An Optional carrying the translated user or empty.
   */
  public Optional<U> optionalUser() {
    return Tools.CURRENT_JWT.isBound() ? Optional.of(translator.apply(Tools.CURRENT_JWT.get())) : Optional.empty();
  }

  /**
   * Translates the JWT bound to the current request into the configured user type. Throws if called outside a protected
   * route.
   *
   * @return The translated user.
   * @throws UnauthenticatedException If no JWT is currently bound.
   */
  public U user() {
    if (!Tools.CURRENT_JWT.isBound()) {
      throw new UnauthenticatedException();
    }
    return translator.apply(Tools.CURRENT_JWT.get());
  }
}
