/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

/**
 * An {@link HTTPException} that maps to {@code 401 Unauthorized}. Thrown when a request lacks valid authentication, for
 * example when {@link org.lattejava.web.oidc.OIDC#jwt()} is called and no JWT is bound to the current request.
 *
 * @author Brian Pontarelli
 */
public class UnauthenticatedException extends HTTPException {
  public UnauthenticatedException() {
    super(401, "No authenticated identity is bound to the current request");
  }

  public UnauthenticatedException(String message) {
    super(401, message);
  }

  public UnauthenticatedException(String message, Throwable cause) {
    super(401, message, cause);
  }
}
