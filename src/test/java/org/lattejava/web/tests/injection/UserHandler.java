/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import jakarta.inject.Singleton;

@Singleton
public class UserHandler implements Handler {
  private final Greeter greeter;
  private final UserService users;

  public UserHandler(Greeter greeter, UserService users) {
    this.greeter = greeter;
    this.users = users;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) throws Exception {
    String name = users.find((String) req.getAttribute("id"));
    if (name == null) {
      res.setStatus(404);
      return;
    }

    res.setStatus(200);
    res.getOutputStream().write(greeter.greet(name).getBytes(StandardCharsets.UTF_8));
  }
}
