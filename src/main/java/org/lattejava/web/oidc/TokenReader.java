/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module org.lattejava.http;

/**
 * Reads the OIDC tokens off an incoming request. Pluggable per profile (cookies for browser modes, bearer headers for
 * API by default), so neither transport is hard-wired to a mode.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface TokenReader {
  /**
   * Extracts the tokens from the request. Any field of the returned {@link Tokens} may be {@code null}.
   *
   * @param req The current request.
   * @return The extracted tokens.
   */
  Tokens read(HTTPRequest req);
}
