/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module org.lattejava.http;

/**
 * Handles an incoming web request.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface Handler {
  void handle(HTTPRequest req, HTTPResponse res) throws Exception;
}
