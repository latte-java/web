/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.jwt;

/**
 * Application-supplied access decision over a bound {@link JWT}. Returns a boolean and never emits a status code — the
 * {@code Authorization} middleware routes the outcome through the profile's {@link AuthChallenge}. The built-in
 * {@link #hasAnyRole}/{@link #hasAllRoles} factories cover role checks; arbitrary checks (path, method, scope) are
 * supplied as lambdas.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface Authorizer {
  /**
   * Returns an {@link Authorizer} that passes when the JWT's extracted roles contain <em>all</em> of the listed roles.
   *
   * @param roleExtractor A function that extracts the set of role strings from a JWT.
   * @param roles         One or more required roles; at least one must be supplied.
   * @return The authorizer.
   * @throws IllegalArgumentException If no roles are provided.
   */
  static Authorizer hasAllRoles(Function<JWT, Set<String>> roleExtractor, String... roles) {
    if (roles == null || roles.length == 0) {
      throw new IllegalArgumentException("At least one role must be provided");
    }
    Set<String> required = Set.of(roles);
    return (_, jwt) -> roleExtractor.apply(jwt).containsAll(required);
  }

  /**
   * Returns an {@link Authorizer} that passes when the JWT's extracted roles contain <em>at least one</em> of the
   * listed roles.
   *
   * @param roleExtractor A function that extracts the set of role strings from a JWT.
   * @param roles         One or more required roles; at least one must be supplied.
   * @return The authorizer.
   * @throws IllegalArgumentException If no roles are provided.
   */
  static Authorizer hasAnyRole(Function<JWT, Set<String>> roleExtractor, String... roles) {
    if (roles == null || roles.length == 0) {
      throw new IllegalArgumentException("At least one role must be provided");
    }
    Set<String> required = Set.of(roles);
    return (_, jwt) -> roleExtractor.apply(jwt).stream().anyMatch(required::contains);
  }

  /**
   * Returns {@code true} if the request is authorized given the bound JWT.
   *
   * @param req The current request.
   * @param jwt The JWT bound to the current request.
   * @return {@code true} if the request is authorized; {@code false} otherwise.
   */
  boolean authorize(HTTPRequest req, JWT jwt);
}
