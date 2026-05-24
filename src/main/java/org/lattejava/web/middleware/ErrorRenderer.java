/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.middleware;

import module org.lattejava.http;

/**
 * Renders an HTTP error response for a caught exception. A renderer owns the entire response for the exception type it
 * handles: it sets the status code and writes the body (if any).
 * <p>
 * Renderers are registered with an {@link ExceptionHandler}, either as the default renderer (used for any
 * {@link org.lattejava.web.HTTPException}) or per exception type for responses that need bespoke output.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface ErrorRenderer {
  /**
   * Renders the response for the given exception. The implementation is responsible for setting the response status and
   * writing any body.
   *
   * @param req The request.
   * @param res The response.
   * @param e   The caught exception.
   * @throws Exception if rendering fails; the exception propagates out of the middleware chain.
   */
  void render(HTTPRequest req, HTTPResponse res, Exception e) throws Exception;
}
