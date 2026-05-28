/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module org.lattejava.jwt;
import module org.testng;

import org.lattejava.web.oidc.*;

import static org.testng.Assert.*;

/**
 * Unit tests for {@link Authorizer} built-in factories.
 *
 * @author Brian Pontarelli
 */
public class AuthorizerTest {
  private static final Function<JWT, Set<String>> ROLES =
      jwt -> new HashSet<>(jwt.getList("roles", String.class));

  @Test
  public void emptyRolesRejected() {
    assertThrows(IllegalArgumentException.class, () -> Authorizer.hasAnyRole(ROLES));
    assertThrows(IllegalArgumentException.class, () -> Authorizer.hasAllRoles(ROLES));
  }

  @Test
  public void hasAllRoles_allPresent_passes() {
    JWT jwt = JWT.builder().claim("roles", List.of("user", "moderator")).build();
    assertTrue(Authorizer.hasAllRoles(ROLES, "user", "moderator").authorize(null, jwt));
  }

  @Test
  public void hasAllRoles_missingOne_fails() {
    JWT jwt = JWT.builder().claim("roles", List.of("user", "moderator")).build();
    assertFalse(Authorizer.hasAllRoles(ROLES, "user", "admin").authorize(null, jwt));
  }

  @Test
  public void hasAnyRole_atLeastOnePresent_passes() {
    JWT jwt = JWT.builder().claim("roles", List.of("user", "moderator")).build();
    assertTrue(Authorizer.hasAnyRole(ROLES, "admin", "moderator").authorize(null, jwt));
  }

  @Test
  public void hasAnyRole_nonePresent_fails() {
    JWT jwt = JWT.builder().claim("roles", List.of("user", "moderator")).build();
    assertFalse(Authorizer.hasAnyRole(ROLES, "admin").authorize(null, jwt));
  }
}
