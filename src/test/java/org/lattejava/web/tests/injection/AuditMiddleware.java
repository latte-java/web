/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module org.lattejava.http;
import module org.lattejava.web;

import jakarta.inject.Singleton;

@Singleton
public class AuditMiddleware implements Middleware {
  private final AuditLog log;

  public AuditMiddleware(AuditLog log) {
    this.log = log;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    log.record(req.getPath());
    res.setHeader("X-Audited", "true");
    chain.next(req, res);
  }
}
