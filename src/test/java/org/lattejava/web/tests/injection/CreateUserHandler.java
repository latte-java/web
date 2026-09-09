/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module org.lattejava.http;
import module org.lattejava.web;

import jakarta.inject.Singleton;

@Singleton
public class CreateUserHandler implements BodyHandler<String> {
  private final UserService users;

  public CreateUserHandler(UserService users) {
    this.users = users;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, String body) {
    users.save((String) req.getAttribute("id"), body);
    res.setStatus(201);
  }
}
