/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module java.base;

public interface AuditLog {
  List<String> paths();

  void record(String path);
}
