/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web;

import org.lattejava.http.server.HTTPRequest;
import org.lattejava.http.server.HTTPResponse;

/**
 * The middleware chain. Calling {@link #next} invokes the next middleware, or the handler if no
 * more middlewares remain. Not calling {@link #next} short-circuits the chain.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface MiddlewareChain {
  void next(HTTPRequest req, HTTPResponse res) throws Exception;
}
