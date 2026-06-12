/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
module org.lattejava.web {
  requires gg.jte;
  requires gg.jte.runtime;
  requires java.net.http;
  requires org.lattejava.http;
  requires org.lattejava.jwt;

  requires static org.lattejava.json;

  exports org.lattejava.web;
  exports org.lattejava.web.json;
  exports org.lattejava.web.jte;
  exports org.lattejava.web.log;
  exports org.lattejava.web.middleware;
  exports org.lattejava.web.oidc;
  exports org.lattejava.web.test;
}
