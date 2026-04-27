/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web;

import module org.lattejava.http;

/**
 * A middleware intercepts requests in the pipeline. It can:
 * <ul>
 *   <li>Inspect or modify the request or response</li>
 *   <li>Short-circuit by NOT calling {@link MiddlewareChain#next} (e.g., return a 401)</li>
 *   <li>Pass control downstream by calling {@link MiddlewareChain#next}</li>
 * </ul>
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface Middleware {
  void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception;
}
