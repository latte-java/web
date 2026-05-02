/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
module org.lattejava.web.tests {
  requires com.fasterxml.jackson.databind;
  requires java.net.http;
  requires org.lattejava.http;
  requires org.lattejava.jwt;
  requires org.lattejava.web;
  requires org.testng;

  exports org.lattejava.web.tests;
  exports org.lattejava.web.tests.jte;

  opens org.lattejava.web.tests to com.fasterxml.jackson.databind, org.testng;
  opens org.lattejava.web.tests.jte to com.fasterxml.jackson.databind, org.testng;
  opens org.lattejava.web.tests.log to com.fasterxml.jackson.databind, org.testng;
  opens org.lattejava.web.tests.middleware to com.fasterxml.jackson.databind, org.testng;
  opens org.lattejava.web.tests.oidc to com.fasterxml.jackson.databind, org.testng;
  opens org.lattejava.web.tests.test to com.fasterxml.jackson.databind, org.testng;
}