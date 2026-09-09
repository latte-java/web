/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module org.lattejava.http;
import module org.lattejava.web;

import io.avaje.inject.Prototype;

@Prototype
public class CountingHandler implements Handler {
  public CountingHandler(InstanceCounter counter) {
    counter.increment();
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) {
    res.setStatus(204);
  }
}
