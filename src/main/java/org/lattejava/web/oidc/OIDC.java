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

import org.lattejava.web.oidc.internal.CallbackHandler;

/**
 * The OpenID Connect entry point for Latte Web.
 * <p>
 * Install the instance at the root with {@code web.install(oidc)} — it handles the callback, logout, and logout-return
 * paths by virtue of being a {@link Middleware} itself. Attach protection where needed via {@link #authenticated()},
 * {@link #hasAnyRole(String...)}, or {@link #hasAllRoles(String...)}, which are thin factories around the public
 * {@link Authenticated}, {@link HasAnyRole}, and {@link HasAllRoles} middlewares.
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
    this.callbackHandler = new CallbackHandler(config);
    this.loginHandler = new LoginHandler(config);
    this.logoutHandler = new LogoutHandler(config);
    this.logoutReturnHandler = new LogoutReturnHandler(config);
    this.jwks = JWKS.create(config.jwksEndpoint());
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
