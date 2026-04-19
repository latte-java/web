/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web;

import org.lattejava.http.server.HTTPRequest;
import org.lattejava.http.server.HTTPResponse;

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
   * @param body The parsed body.
   */
  void handle(HTTPRequest req, HTTPResponse res, T body) throws Exception;
}
