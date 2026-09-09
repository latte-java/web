/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

public interface UserService {
  String find(String id);

  void save(String id, String name);
}
