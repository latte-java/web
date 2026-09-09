/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module org.lattejava.http;

/**
 * Invokes a handler method on a controller that is resolved from the {@link Injector} for each request, passing the
 * parsed body.
 *
 * @param <C> The controller type.
 * @param <T> The type of the parsed body.
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface ControllerBodyHandler<C, T> {
  void handle(C controller, HTTPRequest req, HTTPResponse res, T body) throws Exception;
}
