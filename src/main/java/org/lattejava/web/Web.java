/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.lattejava.http.server.HTTPListenerConfiguration;
import org.lattejava.http.server.HTTPRequest;
import org.lattejava.http.server.HTTPResponse;
import org.lattejava.http.server.HTTPServer;
import org.lattejava.web.internal.MiddlewareChainImpl;
import org.lattejava.web.internal.RouteTrie;

/**
 * A lightweight web framework built on top of the Latte Java HTTP server.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("UnusedReturnValue")
public class Web implements AutoCloseable {
  private final boolean isChild;

  private final String pathPrefix;

  private final AtomicBoolean started;

  private final RouteTrie trie;

  private final List<Middleware> globalMiddlewares;

  private HTTPServer server;

  private Thread shutdownHook;

  public Web() {
    this.isChild = false;
    this.pathPrefix = "";
    this.trie = new RouteTrie();
    this.started = new AtomicBoolean(false);
    this.globalMiddlewares = new ArrayList<>();
  }

  private Web(String pathPrefix, RouteTrie trie, AtomicBoolean started, List<Middleware> globalMiddlewares) {
    this.isChild = true;
    this.pathPrefix = pathPrefix;
    this.trie = trie;
    this.started = started;
    this.globalMiddlewares = globalMiddlewares;
  }

  /**
   * Shuts down the server and removes the JVM shutdown hook.
   */
  public void close() {
    if (isChild) {
      throw new IllegalStateException("Cannot call close on a prefix child Web instance");
    }

    if (shutdownHook != null) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // JVM is already shutting down — nothing to remove
      }
      shutdownHook = null;
    }

    closeServer();
  }

  /**
   * Registers a route that responds to DELETE requests on the given path.
   *
   * @param pathSpec    The path pattern to match.
   * @param handler     The handler to invoke when the route matches.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @see #route(Collection, String, Handler, Middleware...)
   */
  public Web delete(String pathSpec, Handler handler, Middleware... middlewares) {
    return route(List.of("DELETE"), pathSpec, handler, middlewares);
  }

  /**
   * Registers global middlewares that run for every matched request, in registration order, before
   * any per-route middlewares and the handler. Multiple calls append to the existing list.
   * <p>
   * Must be called before {@link #start(int)}.
   *
   * @param middlewares One or more middlewares to install globally.
   * @return This Web instance for chaining.
   * @throws IllegalStateException    if called after {@link #start(int)}.
   * @throws IllegalArgumentException if any entry in {@code middlewares} is null.
   */
  public Web install(Middleware... middlewares) {
    if (started.get()) {
      throw new IllegalStateException("Cannot register middlewares after Web has been started");
    }
    Objects.requireNonNull(middlewares, "middlewares must not be null");
    for (Middleware m : middlewares) {
      if (m == null) {
        throw new IllegalArgumentException("Middleware must not be null");
      }
    }
    Collections.addAll(globalMiddlewares, middlewares);
    return this;
  }

  /**
   * Registers a route that responds to GET requests on the given path.
   *
   * @param pathSpec    The path pattern to match (e.g., {@code /api/user/{id}}).
   * @param handler     The handler to invoke when the route matches.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @see #route(Collection, String, Handler, Middleware...)
   */
  public Web get(String pathSpec, Handler handler, Middleware... middlewares) {
    return route(List.of("GET"), pathSpec, handler, middlewares);
  }

  /**
   * Registers a route that responds to HEAD requests on the given path.
   *
   * @param pathSpec    The path pattern to match.
   * @param handler     The handler to invoke when the route matches.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @see #route(Collection, String, Handler, Middleware...)
   */
  public Web head(String pathSpec, Handler handler, Middleware... middlewares) {
    return route(List.of("HEAD"), pathSpec, handler, middlewares);
  }

  /**
   * Registers a route that responds to OPTIONS requests on the given path.
   *
   * @param pathSpec    The path pattern to match.
   * @param handler     The handler to invoke when the route matches.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @see #route(Collection, String, Handler, Middleware...)
   */
  public Web options(String pathSpec, Handler handler, Middleware... middlewares) {
    return route(List.of("OPTIONS"), pathSpec, handler, middlewares);
  }

  /**
   * Registers a route that responds to PATCH requests on the given path.
   *
   * @param pathSpec    The path pattern to match.
   * @param handler     The handler to invoke when the route matches.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @see #route(Collection, String, Handler, Middleware...)
   */
  public Web patch(String pathSpec, Handler handler, Middleware... middlewares) {
    return route(List.of("PATCH"), pathSpec, handler, middlewares);
  }

  /**
   * Registers a route that responds to POST requests on the given path.
   *
   * @param pathSpec    The path pattern to match.
   * @param handler     The handler to invoke when the route matches.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @see #route(Collection, String, Handler, Middleware...)
   */
  public Web post(String pathSpec, Handler handler, Middleware... middlewares) {
    return route(List.of("POST"), pathSpec, handler, middlewares);
  }

  /**
   * Groups routes under a common path prefix. Routes registered inside the callback have the prefix prepended.
   * Prefixes nest when called inside another prefix callback.
   *
   * @param newPrefix The prefix to prepend to all routes in the group.
   * @param group     A consumer that receives a Web instance scoped to the prefix.
   * @return This Web instance for chaining.
   */
  public Web prefix(String newPrefix, Consumer<Web> group) {
    if (started.get()) {
      throw new IllegalStateException("Cannot register routes after Web has been started");
    }
    Objects.requireNonNull(newPrefix, "newPrefix cannot be null");
    Objects.requireNonNull(group, "group cannot be null");
    Web child = new Web(pathPrefix + newPrefix, trie, started, globalMiddlewares);
    group.accept(child);
    return this;
  }

  /**
   * Registers a route that responds to PUT requests on the given path.
   *
   * @param pathSpec    The path pattern to match.
   * @param handler     The handler to invoke when the route matches.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @see #route(Collection, String, Handler, Middleware...)
   */
  public Web put(String pathSpec, Handler handler, Middleware... middlewares) {
    return route(List.of("PUT"), pathSpec, handler, middlewares);
  }

  /**
   * Registers a route that matches the given HTTP methods on the given path.
   * <p>
   * Path parameters are supported using curly brace syntax (e.g., {@code /api/user/{id}}). Matched parameter values are
   * stored as request attributes accessible via {@link HTTPRequest#getAttribute(String)}.
   *
   * @param methods     The HTTP methods this route responds to (e.g., {@code List.of("GET", "POST")}).
   * @param pathSpec    The path pattern to match (e.g., {@code /api/user/{id}}).
   * @param handler     The handler to invoke when the route matches.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   */
  public Web route(Collection<String> methods, String pathSpec, Handler handler, Middleware... middlewares) {
    if (started.get()) {
      throw new IllegalStateException("Cannot register routes after Web has been started");
    }
    Objects.requireNonNull(methods, "methods cannot be null");
    Objects.requireNonNull(pathSpec, "pathSpec cannot be null");
    Objects.requireNonNull(handler, "handler cannot be null");
    Objects.requireNonNull(middlewares, "middlewares must not be null");
    for (Middleware m : middlewares) {
      if (m == null) {
        throw new IllegalArgumentException("Middleware must not be null");
      }
    }
    if (methods.isEmpty()) {
      throw new IllegalArgumentException("At least one HTTP method is required");
    }
    Set<String> normalizedMethods = new LinkedHashSet<>();
    for (String method : methods) {
      if (method == null) {
        throw new IllegalArgumentException("HTTP method must not be null");
      }
      if (method.isBlank()) {
        throw new IllegalArgumentException("HTTP method must not be blank");
      }
      if (!isValidMethodToken(method)) {
        throw new IllegalArgumentException("Invalid HTTP method [" + method + "]");
      }
      normalizedMethods.add(method.toUpperCase(Locale.ROOT));
    }
    trie.insert(pathPrefix + pathSpec, normalizedMethods, handler, List.of(middlewares));
    return this;
  }

  /**
   * Starts the HTTP server on the given port.
   *
   * @param port The port to listen on.
   * @return This Web instance for chaining.
   */
  public Web start(int port) {
    if (isChild) {
      throw new IllegalStateException("Cannot call start on a prefix child Web instance");
    }
    if (started.get()) {
      throw new IllegalStateException("Web has already been started");
    }

    HTTPServer newServer = new HTTPServer()
        .withHandler(this::handleRequest)
        .withListener(new HTTPListenerConfiguration(port))
        .start();

    Thread hook;
    try {
      hook = new Thread(this::closeServer, "Web shutdown hook");
      Runtime.getRuntime().addShutdownHook(hook);
    } catch (IllegalStateException e) {
      // JVM is already shutting down; clean up the server we just started
      newServer.close();
      throw e;
    }

    server = newServer;
    shutdownHook = hook;
    started.set(true);
    return this;
  }

  private static boolean isValidMethodToken(String method) {
    int len = method.length();
    if (len == 0) {
      return false;
    }
    for (int i = 0; i < len; i++) {
      char c = method.charAt(i);
      if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
        return false;
      }
    }
    return true;
  }

  private void closeServer() {
    // HTTPServer.close() is idempotent, so the rare close()/shutdown-hook race is harmless.
    HTTPServer toClose = server;
    if (toClose != null) {
      server = null;
      toClose.close();
    }
  }

  private void handleRequest(HTTPRequest request, HTTPResponse response) throws Exception {
    String path = request.getPath();
    String method = request.getMethod().name();
    RouteTrie.Outcome outcome = trie.match(path, method);

    switch (outcome) {
      case RouteTrie.Outcome.Found(var handler, var routeMiddlewares, var pathParams) -> {
        for (var entry : pathParams.entrySet()) {
          request.setAttribute(entry.getKey(), entry.getValue());
        }

        List<Middleware> chainMiddlewares;
        if (globalMiddlewares.isEmpty() && routeMiddlewares.isEmpty()) {
          chainMiddlewares = List.of();
        } else {
          chainMiddlewares = new ArrayList<>(globalMiddlewares.size() + routeMiddlewares.size());
          chainMiddlewares.addAll(globalMiddlewares);
          chainMiddlewares.addAll(routeMiddlewares);
        }

        new MiddlewareChainImpl(chainMiddlewares, handler).next(request, response);
      }
      case RouteTrie.Outcome.MethodNotAllowed(var allowedMethods) -> {
        response.setStatus(405);
        response.setHeader("Allow", String.join(", ", allowedMethods));
      }
      case RouteTrie.Outcome.NotFound __ -> response.setStatus(404);
    }
  }
}
