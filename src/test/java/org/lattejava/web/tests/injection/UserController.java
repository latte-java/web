/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module java.base;
import module org.lattejava.http;

import io.avaje.inject.Prototype;

@Prototype
public class UserController {
  private final Greeter greeter;
  private final UserService users;

  public UserController(Greeter greeter, InstanceCounter counter, UserService users) {
    counter.increment();
    this.greeter = greeter;
    this.users = users;
  }

  public void create(HTTPRequest req, HTTPResponse res, String body) {
    users.save((String) req.getAttribute("id"), body);
    res.setStatus(201);
  }

  public void show(HTTPRequest req, HTTPResponse res) throws Exception {
    String name = users.find((String) req.getAttribute("id"));
    if (name == null) {
      res.setStatus(404);
      return;
    }

    res.setStatus(200);
    res.getOutputStream().write(greeter.greet(name).getBytes(StandardCharsets.UTF_8));
  }
}
