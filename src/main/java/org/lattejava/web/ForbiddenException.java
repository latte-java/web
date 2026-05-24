/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

/**
 * An {@link HTTPException} that maps to {@code 403 Forbidden}. Thrown when an authenticated request is denied access to
 * a resource.
 *
 * @author Brian Pontarelli
 */
public class ForbiddenException extends HTTPException {
  public ForbiddenException() {
    super(403);
  }

  public ForbiddenException(String message) {
    super(403, message);
  }

  public ForbiddenException(String message, Throwable cause) {
    super(403, message, cause);
  }
}
