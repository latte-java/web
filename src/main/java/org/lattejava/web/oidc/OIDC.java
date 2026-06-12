/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.jwt;
import module org.lattejava.web;

import org.lattejava.web.oidc.internal.*;

/**
 * The OpenID Connect entry point for Latte Web.
 * <p>
 * Use the mode-first static factories to create a profile instance: {@link #ssr}, {@link #spa}, or {@link #api} for
 * standard modes; {@link #custom} for fully manual wiring. Register session endpoints (login, callback, logout,
 * logout-return) with {@link #sessionEndpoints(OIDCConfig)} or {@link #sessionEndpoints(OIDCConfig, BrowserSettings)}.
 * Protect routes via {@link #authenticated()}, {@link #hasAnyRole(String...)}, {@link #hasAllRoles(String...)}, or
 * {@link #authorized(Authorizer)}.
 * <p>
 * Request-scoped access to the authenticated JWT is available via the static {@link #jwt()} / {@link #optionalJWT()}
 * and the instance-level {@link #user()} / {@link #optionalUser()}, which apply the configured translator.
 *
 * @param <U> The translated-user type. Use the identity factory overloads for an untyped {@code OIDC<JWT>}.
 * @author Brian Pontarelli
 */
public class OIDC<U> {
  private static final Map<String, JWKS> JWKS_CACHE = new ConcurrentHashMap<>();

  private final AuthChallenge challenge;
  private final OIDCConfig config;
  private final TokenReader reader;
  private final Function<JWT, U> translator;
  private final TokenValidator validator;
  private final TokenWriter writer;

  private OIDC(OIDCConfig config, TokenReader reader, TokenWriter writer, AuthChallenge challenge,
               Function<JWT, U> translator) {
    this.config = config;
    this.reader = reader;
    this.writer = writer;
    this.challenge = challenge;
    this.translator = translator;

    JWKS jwks = jwks(config);
    this.validator = new TokenValidator(config, jwks);
  }

  /**
   * Creates an API-mode profile with default {@link APISettings} and {@code JWT} as the user type.
   *
   * @param config The IdP configuration.
   * @return The profile instance.
   */
  public static OIDC<JWT> api(OIDCConfig config) {
    return api(config, APISettings.builder().build(), Function.identity());
  }

  /**
   * Creates an API-mode profile with default {@link APISettings} and a custom translator.
   *
   * @param config     The IdP configuration.
   * @param translator Maps the bound JWT to a domain object.
   * @param <U>        The user type.
   * @return The profile instance.
   */
  public static <U> OIDC<U> api(OIDCConfig config, Function<JWT, U> translator) {
    return api(config, APISettings.builder().build(), translator);
  }

  /**
   * Creates an API-mode profile with explicit {@link APISettings} and a custom translator.
   *
   * @param config     The IdP configuration.
   * @param api        The API transport settings.
   * @param translator Maps the bound JWT to a domain object.
   * @param <U>        The user type.
   * @return The profile instance.
   */
  public static <U> OIDC<U> api(OIDCConfig config, APISettings api, Function<JWT, U> translator) {
    return new OIDC<>(config, api.tokenReader(), api.tokenWriter(), new StatusChallenge(), translator);
  }

  /**
   * Creates a fully custom profile with explicit transport and challenge.
   *
   * @param config     The IdP configuration.
   * @param reader     The token reader.
   * @param writer     The token writer.
   * @param challenge  The auth challenge.
   * @param translator Maps the bound JWT to a domain object.
   * @param <U>        The user type.
   * @return The profile instance.
   */
  public static <U> OIDC<U> custom(OIDCConfig config, TokenReader reader, TokenWriter writer, AuthChallenge challenge,
                                   Function<JWT, U> translator) {
    return new OIDC<>(config, reader, writer, challenge, translator);
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
   * Returns a {@link Middleware} that dispatches the four browser session endpoints (login, callback, logout,
   * logout-return) using default {@link BrowserSettings}.
   *
   * @param config The IdP configuration.
   * @return The session endpoints middleware.
   */
  public static Middleware sessionEndpoints(OIDCConfig config) {
    return sessionEndpoints(config, BrowserSettings.builder().build());
  }

  /**
   * Returns a {@link Middleware} that dispatches the four browser session endpoints using the given
   * {@link BrowserSettings}.
   *
   * @param config  The IdP configuration.
   * @param browser The browser settings (paths, cookie names, redirect targets).
   * @return The session endpoints middleware.
   */
  public static Middleware sessionEndpoints(OIDCConfig config, BrowserSettings browser) {
    return new SessionEndpoints(config, browser, jwks(config));
  }

  /**
   * Creates an SPA-mode profile with default {@link BrowserSettings} and {@code JWT} as the user type.
   *
   * @param config The IdP configuration.
   * @return The profile instance.
   */
  public static OIDC<JWT> spa(OIDCConfig config) {
    return spa(config, BrowserSettings.builder().build(), Function.identity());
  }

  /**
   * Creates an SPA-mode profile with default {@link BrowserSettings} and a custom translator.
   *
   * @param config     The IdP configuration.
   * @param translator Maps the bound JWT to a domain object.
   * @param <U>        The user type.
   * @return The profile instance.
   */
  public static <U> OIDC<U> spa(OIDCConfig config, Function<JWT, U> translator) {
    return spa(config, BrowserSettings.builder().build(), translator);
  }

  /**
   * Creates an SPA-mode profile with explicit {@link BrowserSettings} and a custom translator.
   *
   * @param config     The IdP configuration.
   * @param browser    The browser settings (cookie names for reading tokens).
   * @param translator Maps the bound JWT to a domain object.
   * @param <U>        The user type.
   * @return The profile instance.
   */
  public static <U> OIDC<U> spa(OIDCConfig config, BrowserSettings browser, Function<JWT, U> translator) {
    return new OIDC<>(config, browser.tokenReader(), browser.tokenWriter(), new StatusChallenge(), translator);
  }

  /**
   * Creates an SSR-mode profile with default {@link BrowserSettings} and {@code JWT} as the user type.
   *
   * @param config The IdP configuration.
   * @return The profile instance.
   */
  public static OIDC<JWT> ssr(OIDCConfig config) {
    return ssr(config, BrowserSettings.builder().build(), Function.identity());
  }

  /**
   * Creates an SSR-mode profile with default {@link BrowserSettings} and a custom translator.
   *
   * @param config     The IdP configuration.
   * @param translator Maps the bound JWT to a domain object.
   * @param <U>        The user type.
   * @return The profile instance.
   */
  public static <U> OIDC<U> ssr(OIDCConfig config, Function<JWT, U> translator) {
    return ssr(config, BrowserSettings.builder().build(), translator);
  }

  /**
   * Creates an SSR-mode profile with explicit {@link BrowserSettings} and a custom translator.
   *
   * @param config     The IdP configuration.
   * @param browser    The browser settings (cookie names, redirect targets, challenge pages).
   * @param translator Maps the bound JWT to a domain object.
   * @param <U>        The user type.
   * @return The profile instance.
   */
  public static <U> OIDC<U> ssr(OIDCConfig config, BrowserSettings browser, Function<JWT, U> translator) {
    return new OIDC<>(config, browser.tokenReader(), browser.tokenWriter(), new RedirectChallenge(browser), translator);
  }

  private static JWKS jwks(OIDCConfig config) {
    return JWKS_CACHE.computeIfAbsent(config.jwksEndpoint().toString(), uri -> JWKS.fromJWKS(uri).build());
  }

  /**
   * Returns a {@link Middleware} that authenticates the request using this profile's transport and challenge.
   *
   * @return The authentication middleware.
   */
  public Middleware authenticated() {
    return new Authentication(config, reader, writer, challenge, validator);
  }

  /**
   * Returns a {@link Middleware} that authorizes the authenticated request using the given {@link Authorizer}. Must be
   * installed downstream of {@link #authenticated()}.
   *
   * @param authorizer The access decision.
   * @return The authorization middleware.
   */
  public Middleware authorized(Authorizer authorizer) {
    return new Authorization(authorizer, challenge, writer);
  }

  /**
   * Returns a {@link Middleware} that requires all the named roles. Must be installed downstream of
   * {@link #authenticated()}.
   *
   * @param roles One or more roles; the authenticated user must possess every one.
   * @return The authorization middleware.
   */
  public Middleware hasAllRoles(String... roles) {
    return new Authorization(Authorizer.hasAllRoles(config.roleExtractor(), roles), challenge, writer);
  }

  /**
   * Returns a {@link Middleware} that requires at least one of the named roles. Must be installed downstream of
   * {@link #authenticated()}.
   *
   * @param roles One or more roles; the authenticated user must possess at least one.
   * @return The authorization middleware.
   */
  public Middleware hasAnyRole(String... roles) {
    return new Authorization(Authorizer.hasAnyRole(config.roleExtractor(), roles), challenge, writer);
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
