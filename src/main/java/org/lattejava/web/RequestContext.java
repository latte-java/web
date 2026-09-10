/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module org.lattejava.http;

/**
 * Gives code that runs inside a request access to the current {@link HTTPRequest} and {@link HTTPResponse} without
 * receiving them as parameters. {@link Web} binds both for the duration of every request, so handlers, middlewares, and
 * anything they call (including objects created by an {@link Injector}) can read them.
 * <p>
 * The values are {@link ScopedValue}s. They are bound only on the thread that handles the request. A thread that a
 * handler starts does not see them.
 * <p>
 * To make the request and response injectable, register a prototype-scoped provider with the DI framework that reads
 * from this class. With Avaje Inject:
 * <pre>{@code
 * @Factory
 * public class WebFactory {
 *   @Bean
 *   @Prototype
 *   HTTPRequest request() {
 *     return RequestContext.request();
 *   }
 *
 *   @Bean
 *   @Prototype
 *   HTTPResponse response() {
 *     return RequestContext.response();
 *   }
 * }
 * }</pre>
 * The provider must be prototype-scoped (or consumed through a {@code Provider}) so each request resolves its own
 * instance. A singleton would capture the first request forever.
 * <p>
 * Code that exercises such objects outside a running server can bind the values itself:
 * <pre>{@code
 * ScopedValue.where(RequestContext.REQUEST, req)
 *            .where(RequestContext.RESPONSE, res)
 *            .run(() -> scope.get(UserController.class).show());
 * }</pre>
 *
 * @author Brian Pontarelli
 */
public final class RequestContext {
  /**
   * The current request. {@link Web} binds it for the duration of each request.
   */
  public static final ScopedValue<HTTPRequest> REQUEST = ScopedValue.newInstance();

  /**
   * The current response. {@link Web} binds it for the duration of each request.
   */
  public static final ScopedValue<HTTPResponse> RESPONSE = ScopedValue.newInstance();

  private RequestContext() {
  }

  /**
   * @return The request being handled on the current thread.
   * @throws IllegalStateException if called outside a request.
   */
  public static HTTPRequest request() {
    return REQUEST.orElseThrow(() -> new IllegalStateException(
        "No request is bound to the current thread. RequestContext.request() can only be called while a request is being handled"));
  }

  /**
   * @return The response being handled on the current thread.
   * @throws IllegalStateException if called outside a request.
   */
  public static HTTPResponse response() {
    return RESPONSE.orElseThrow(() -> new IllegalStateException(
        "No response is bound to the current thread. RequestContext.response() can only be called while a request is being handled"));
  }
}
