/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module java.base;

import jakarta.inject.Singleton;

@Singleton
public class InstanceCounter {
  private final AtomicInteger count = new AtomicInteger();

  public int count() {
    return count.get();
  }

  public void increment() {
    count.incrementAndGet();
  }
}
