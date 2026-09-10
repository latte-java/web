/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module org.lattejava.http;
import module org.lattejava.web;

import io.avaje.inject.Bean;
import io.avaje.inject.Factory;
import io.avaje.inject.Prototype;

/**
 * Makes the current request and response injectable. Prototype scope so every resolution reads the current binding.
 */
@Factory
public class RequestFactory {
  @Bean
  @Prototype
  HTTPRequest request() {
    return RequestContext.request();
  }

  @Bean
  @Prototype
  HTTPResponse response() {
    return RequestContext.response();
  }
}
