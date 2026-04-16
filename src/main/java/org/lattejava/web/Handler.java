/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web;

import org.lattejava.http.server.HTTPRequest;
import org.lattejava.http.server.HTTPResponse;

/**
 * Handles an incoming web request.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface Handler {
  void handle(HTTPRequest req, HTTPResponse res) throws Exception;
}
