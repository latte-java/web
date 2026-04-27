/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

import org.lattejava.web.internal.*;

/**
 * A lightweight web framework built on top of the Latte Java HTTP server.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("UnusedReturnValue")
public class Web implements AutoCloseable {
  private final boolean isChild;
  private final MiddlewareTrie middlewareTrie;
  private final String pathPrefix;
  private final AtomicBoolean started;
  private final RouteTrie trie;
  private Path baseDir;
  private HTTPServer server;
  private Thread shutdownHook;

  public Web() {
    this.isChild = false;
    this.pathPrefix = "";
    this.trie = new RouteTrie();
    this.started = new AtomicBoolean(false);
    this.middlewareTrie = new MiddlewareTrie();
  }

  private Web(String pathPrefix, RouteTrie trie, AtomicBoolean started, MiddlewareTrie middlewareTrie) {
    this.isChild = true;
    this.pathPrefix = pathPrefix;
    this.trie = trie;
    this.started = started;
    this.middlewareTrie = middlewareTrie;
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

  /**
   * Sets the base directory for this Web's HTTP context. If not called, the default from the underlying HTTP server is
   * used (typically the current working directory).
   *
   * @param baseDir The base directory for file resolution.
   * @return This Web instance for chaining.
   * @throws IllegalStateException if called after {@link #start(int)}.
   */
  public Web baseDir(Path baseDir) {
    if (started.get()) {
      throw new IllegalStateException("Cannot set baseDir after Web has been started");
    }
    Objects.requireNonNull(baseDir, "baseDir must not be null");
    this.baseDir = baseDir;
    return this;
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
   * Installs a {@link StaticResources} that serves files from the subdirectory named after the URL prefix (minus the
   * leading slash). For example, {@code files("/assets")} serves {@code <baseDir>/assets/*} under {@code /assets/*}.
   *
   * @param urlPrefix The URL prefix this middleware will own.
   * @return This Web instance for chaining.
   */
  public Web files(String urlPrefix) {
    return install(new StaticResources(urlPrefix));
  }

  /**
   * Installs a {@link StaticResources} with an explicit mapping from URL prefix to subdirectory under the HTTP
   * context's base directory.
   *
   * @param urlPrefix    The URL prefix.
   * @param subdirectory The subdirectory under the base directory.
   * @return This Web instance for chaining.
   */
  public Web files(String urlPrefix, String subdirectory) {
    return install(new StaticResources(urlPrefix, subdirectory));
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
   * Registers global middlewares that run for every matched request, in registration order, before any per-route
   * middlewares and the handler. Multiple calls append to the existing list.
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

    middlewareTrie.install(pathPrefix, middlewares);
    return this;
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
   * Registers a route that responds to PATCH requests on the given path, parsing the body with the given supplier.
   *
   * @param <T>         The type of the parsed body.
   * @param pathSpec    The path pattern to match.
   * @param bodyHandler The handler to invoke with the parsed body when the route matches.
   * @param supplier    The supplier that parses the request body.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @see #route(Collection, String, BodyHandler, BodySupplier, Middleware...)
   */
  public <T> Web patch(String pathSpec, BodyHandler<T> bodyHandler, BodySupplier<T> supplier, Middleware... middlewares) {
    return route(List.of("PATCH"), pathSpec, bodyHandler, supplier, middlewares);
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
   * Registers a route that responds to POST requests on the given path, parsing the body with the given supplier.
   *
   * @param <T>         The type of the parsed body.
   * @param pathSpec    The path pattern to match.
   * @param bodyHandler The handler to invoke with the parsed body when the route matches.
   * @param supplier    The supplier that parses the request body.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @see #route(Collection, String, BodyHandler, BodySupplier, Middleware...)
   */
  public <T> Web post(String pathSpec, BodyHandler<T> bodyHandler, BodySupplier<T> supplier, Middleware... middlewares) {
    return route(List.of("POST"), pathSpec, bodyHandler, supplier, middlewares);
  }

  /**
   * Groups routes under a common path prefix. Routes registered inside the callback have the prefix prepended. Prefixes
   * nest when called inside another prefix callback.
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
    Web child = new Web(pathPrefix + newPrefix, trie, started, middlewareTrie);
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
   * Registers a route that responds to PUT requests on the given path, parsing the body with the given supplier.
   *
   * @param <T>         The type of the parsed body.
   * @param pathSpec    The path pattern to match.
   * @param bodyHandler The handler to invoke with the parsed body when the route matches.
   * @param supplier    The supplier that parses the request body.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @see #route(Collection, String, BodyHandler, BodySupplier, Middleware...)
   */
  public <T> Web put(String pathSpec, BodyHandler<T> bodyHandler, BodySupplier<T> supplier, Middleware... middlewares) {
    return route(List.of("PUT"), pathSpec, bodyHandler, supplier, middlewares);
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
   * Registers a route that matches the given HTTP methods on the given path, parsing the body with the given supplier.
   * <p>
   * The supplier is called after any middlewares. If the supplier returns {@code null}, it signals a handled error
   * condition (e.g., the supplier already set a 400 status); the body handler is short-circuited and not invoked.
   *
   * @param <T>         The type of the parsed body.
   * @param methods     The HTTP methods this route responds to (e.g., {@code List.of("POST", "PUT")}).
   * @param pathSpec    The path pattern to match (e.g., {@code /api/user/{id}}).
   * @param bodyHandler The handler to invoke with the parsed body when the route matches.
   * @param supplier    The supplier that parses the request body.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   */
  public <T> Web route(Collection<String> methods, String pathSpec, BodyHandler<T> bodyHandler, BodySupplier<T> supplier, Middleware... middlewares) {
    Objects.requireNonNull(bodyHandler, "bodyHandler cannot be null");
    Objects.requireNonNull(supplier, "supplier cannot be null");
    Handler adapted = (req, res) -> {
      T body = supplier.get(req, res);
      if (body == null) {
        // Supplier signalled a handled error (e.g., set 400 status and returned null)
        return;
      }
      bodyHandler.handle(req, res, body);
    };
    return route(methods, pathSpec, adapted, middlewares);
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
        .withBaseDir(baseDir != null ? baseDir : Paths.get(".")) // Default to current working directory
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
      case RouteTrie.Outcome.Found(var handler, var routeMiddlewares, var pathParams, var segments) -> {
        for (var entry : pathParams.entrySet()) {
          request.setAttribute(entry.getKey(), entry.getValue());
        }

        List<Middleware> prefixMiddlewares = middlewareTrie.collect(segments);
        List<Middleware> chainMiddlewares;
        if (prefixMiddlewares.isEmpty() && routeMiddlewares.isEmpty()) {
          chainMiddlewares = List.of();
        } else {
          chainMiddlewares = new ArrayList<>(prefixMiddlewares.size() + routeMiddlewares.size());
          chainMiddlewares.addAll(prefixMiddlewares);
          chainMiddlewares.addAll(routeMiddlewares);
        }

        new MiddlewareChainImpl(chainMiddlewares, handler).next(request, response);
      }
      case RouteTrie.Outcome.MethodNotAllowed(var allowedMethods, var _) -> {
        response.setStatus(405);
        response.setHeader("Allow", String.join(", ", allowedMethods));
      }
      case RouteTrie.Outcome.NotFound(var segments) -> {
        List<Middleware> prefixMiddlewares = middlewareTrie.collect(segments);
        if (prefixMiddlewares.isEmpty()) {
          response.setStatus(404);
        } else {
          new MiddlewareChainImpl(prefixMiddlewares, (_, res) -> res.setStatus(404)).next(request, response);
        }
      }
    }
  }

}
