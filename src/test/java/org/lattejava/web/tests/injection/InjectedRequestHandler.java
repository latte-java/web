/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import io.avaje.inject.Prototype;

/**
 * Receives the request and response through the constructor and verifies they are the same objects the framework passes
 * to {@link #handle}.
 */
@Prototype
public class InjectedRequestHandler implements Handler {
  private final HTTPRequest req;
  private final HTTPResponse res;

  public InjectedRequestHandler(HTTPRequest req, HTTPResponse res) {
    this.req = req;
    this.res = res;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) throws Exception {
    if (req != this.req || res != this.res) {
      res.setStatus(500);
      return;
    }

    this.res.setStatus(200);
    this.res.getOutputStream().write(("Hello, " + this.req.getAttribute("id")).getBytes(StandardCharsets.UTF_8));
  }
}
