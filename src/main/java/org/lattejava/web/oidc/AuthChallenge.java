/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module org.lattejava.http;

/**
 * Communicates authentication/authorization outcomes to the client in the profile's dialect — HTML/redirects for SSR,
 * status codes for SPA and API. Receives the {@link TokenWriter} so it can clear credentials when appropriate, and a
 * {@code retryable} flag used only by the SSR meta-refresh interstitial.
 *
 * @author Brian Pontarelli
 */
public interface AuthChallenge {
  /**
   * Responds to an authorization failure (the authenticated user lacks a required role or permission).
   *
   * @param req The current request.
   * @param res The response.
   * @throws Exception If the response cannot be written.
   */
  void forbidden(HTTPRequest req, HTTPResponse res) throws Exception;

  /**
   * Responds to an authentication failure (no valid token is present or the token cannot be refreshed).
   *
   * @param req      The current request.
   * @param res      The response.
   * @param writer   The token writer, so the challenge can clear credentials when appropriate.
   * @param retryable {@code true} when the failure may be transient (e.g., an expired JWT on a cross-site navigation
   *                 that hasn't yet received a refreshed cookie); triggers the SSR meta-refresh interstitial.
   * @throws Exception If the response cannot be written.
   */
  void unauthenticated(HTTPRequest req, HTTPResponse res, TokenWriter writer, boolean retryable) throws Exception;

  /**
   * Responds when the IdP is unreachable and token validation cannot complete.
   *
   * @param req The current request.
   * @param res The response.
   * @throws Exception If the response cannot be written.
   */
  void unavailable(HTTPRequest req, HTTPResponse res) throws Exception;
}
