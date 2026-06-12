/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
module org.lattejava.web.tests {
  requires java.net.http;
  requires org.lattejava.http;
  requires org.lattejava.jwt;
  requires org.lattejava.web;
  requires org.testng;

  requires static org.lattejava.json;

  exports org.lattejava.web.tests;
  exports org.lattejava.web.tests.json;
  exports org.lattejava.web.tests.jte;

  opens org.lattejava.web.tests to org.lattejava.web, org.testng;
  opens org.lattejava.web.tests.json to org.lattejava.web, org.testng;
  opens org.lattejava.web.tests.jte to org.lattejava.web, org.testng;
  opens org.lattejava.web.tests.log to org.lattejava.web, org.testng;
  opens org.lattejava.web.tests.middleware to org.lattejava.web, org.testng;
  opens org.lattejava.web.tests.oidc to org.lattejava.web, org.testng;
  opens org.lattejava.web.tests.test to org.lattejava.web, org.testng;
}