/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

/**
 * Resolves instances by type. Any dependency injection library can supply this (e.g. Avaje's
 * {@code BeanScope::get}).
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface Injector {
  <T> T get(Class<T> type);
}
