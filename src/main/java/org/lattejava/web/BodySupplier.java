/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module org.lattejava.http;

/**
 * Parses the request body into a typed object.
 *
 * @param <T> The type of the parsed body.
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface BodySupplier<T> {
  /**
   * Parses the request body into an object of type {@code T}.
   * <p>
   * Failure and emptiness are signalled differently:
   * <ul>
   *   <li>If the body cannot be parsed, the supplier should throw — typically a {@link BadRequestException} (or another
   *       {@link HTTPException}) so the framework renders the appropriate error response. The body handler is not
   *       invoked.</li>
   *   <li>If the body is empty but that is not an error (an empty body is valid for some routes), the supplier should
   *       return {@code null}. The framework then invokes the body handler with a {@code null} body, leaving the
   *       handler to decide whether a missing body is acceptable.</li>
   * </ul>
   *
   * @param req The request.
   * @param res The response.
   * @return The parsed body, or {@code null} to signal an empty (but valid) body.
   * @throws Exception if the body cannot be parsed; throw an {@link HTTPException} to control the response status.
   */
  T get(HTTPRequest req, HTTPResponse res) throws Exception;
}
