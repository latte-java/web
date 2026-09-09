/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module java.base;

import jakarta.inject.Singleton;

@Singleton
public class DefaultAuditLog implements AuditLog {
  private final List<String> paths = new CopyOnWriteArrayList<>();

  @Override
  public List<String> paths() {
    return List.copyOf(paths);
  }

  @Override
  public void record(String path) {
    paths.add(path);
  }
}
