/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web;

import module org.lattejava.http;

/**
 * Optional filter for {@link StaticResourceMiddleware} requests. Implementations can inspect the
 * request and URI and return {@code false} to skip static-file resolution (falling through to the
 * rest of the pipeline).
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface StaticResourceFilter {
  /**
   * @param uri     The request URI.
   * @param request The request.
   * @return true to attempt static-file resolution; false to fall through.
   */
  boolean allow(String uri, HTTPRequest request);
}
