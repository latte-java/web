/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
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
   * If the supplier cannot parse the body and has already written an error response (e.g., 400 Bad Request), it should
   * return {@code null}. The framework will then short-circuit and not invoke the body handler.
   *
   * @param req The request.
   * @param res The response.
   * @return The parsed body, or {@code null} if the supplier handled an error condition.
   */
  T get(HTTPRequest req, HTTPResponse res) throws Exception;
}
