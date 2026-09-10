/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module java.base;

/**
 * Thrown by {@link Messages#get(String, Object...)} when no file in the lookup chain defines the key. This is a plain
 * {@link RuntimeException} rather than an {@link HTTPException} so that the key and path are not written to the client
 * by the default error renderer.
 *
 * @author Brian Pontarelli
 */
public class MissingMessageException extends RuntimeException {
  private final String key;

  public MissingMessageException(String key, String path, Locale locale) {
    super("No message for key [" + key + "] under path [" + path + "] for locale [" + locale + "]");
    this.key = key;
  }

  /**
   * @return The key that was not found.
   */
  public String key() {
    return key;
  }
}
