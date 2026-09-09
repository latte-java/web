/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module org.lattejava.http;

/**
 * Invokes a handler method on a controller that is resolved from the {@link Injector} for each request.
 *
 * @param <C> The controller type.
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface ControllerHandler<C> {
  void handle(C controller, HTTPRequest req, HTTPResponse res) throws Exception;
}
