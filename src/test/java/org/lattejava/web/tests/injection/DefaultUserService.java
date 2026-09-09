/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module java.base;

import jakarta.inject.Singleton;

@Singleton
public class DefaultUserService implements UserService {
  private final Map<String, String> users = new ConcurrentHashMap<>(Map.of("1", "Brian"));

  @Override
  public String find(String id) {
    return users.get(id);
  }

  @Override
  public void save(String id, String name) {
    users.put(id, name);
  }
}
