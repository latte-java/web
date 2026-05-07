/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

/**
 * Thrown when code attempts to access the request-scoped authenticated identity but no identity is bound — typically
 * because the route is not protected by an OIDC middleware or the middleware hasn't run yet. Default-mapped to 401 by
 * {@link org.lattejava.web.middleware.ExceptionHandler}.
 *
 * @author Brian Pontarelli
 */
public class UnauthenticatedException extends RuntimeException {
  public UnauthenticatedException() {
    super("No authenticated identity is bound to the current request");
  }

  public UnauthenticatedException(String message) {
    super(message);
  }
}
