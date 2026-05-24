/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

/**
 * An {@link HTTPException} that maps to {@code 503 Service Unavailable}. Thrown when the request cannot be served
 * because a dependency the framework relies on is temporarily unreachable.
 *
 * @author Brian Pontarelli
 */
public class ServiceUnavailableException extends HTTPException {
  public ServiceUnavailableException() {
    super(503);
  }

  public ServiceUnavailableException(String message) {
    super(503, message);
  }

  public ServiceUnavailableException(String message, Throwable cause) {
    super(503, message, cause);
  }
}
