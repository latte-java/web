/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module org.lattejava.http;

/**
 * Intercepts requests in the pipeline. A middleware can inspect or modify the request and response, pass control
 * downstream by calling {@link MiddlewareChain#next}, or short-circuit by not calling it (for example, to return a
 * 401).
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface Middleware {
  void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception;
}
