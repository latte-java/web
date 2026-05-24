/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module org.lattejava.http;

/**
 * Handles an incoming web request using a pre-parsed body object.
 *
 * @param <T> The type of the parsed body.
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface BodyHandler<T> {
  /**
   * Handles the request using the parsed body object.
   *
   * @param req  The request.
   * @param res  The response.
   * @param body The parsed body, or {@code null} if the {@link BodySupplier} signalled an empty (but valid) body. The
   *             handler decides whether a {@code null} body is acceptable.
   */
  void handle(HTTPRequest req, HTTPResponse res, T body) throws Exception;
}
