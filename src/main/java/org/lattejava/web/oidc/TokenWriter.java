/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module org.lattejava.http;

/**
 * Writes refreshed tokens back to the response and clears them on failure. Symmetric with {@link TokenReader}; the
 * {@code clear} operation is what differs between cookie transports (delete the cookies) and header transports (a
 * no-op), which is why it lives on the writer rather than the challenge.
 *
 * @author Brian Pontarelli
 */
public interface TokenWriter {
  /**
   * Removes any persisted tokens (cookie transports delete their cookies; header transports do nothing).
   *
   * @param req The current request.
   * @param res The response.
   */
  void clear(HTTPRequest req, HTTPResponse res);

  /**
   * Writes the newly issued tokens after a successful refresh.
   *
   * @param req    The current request.
   * @param res    The response.
   * @param tokens The refreshed tokens; the refresh/id tokens may be {@code null} if not rotated.
   */
  void write(HTTPRequest req, HTTPResponse res, Tokens tokens);
}
