/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import jakarta.inject.Singleton;

@Singleton
public class DefaultGreeter implements Greeter {
  @Override
  public String greet(String name) {
    return "Hello, " + name;
  }
}
