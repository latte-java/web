/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
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
public class Web implements AutoCloseable, Configurable<Web> {
  private static final System.Logger LOG = System.getLogger(Web.class.getName());
  private final boolean isChild;
  private final MiddlewareTrie middlewareTrie;
  private final String pathPrefix;
  private final HTTPServer server = new HTTPServer();
  private final List<Runnable> shutdownTasks = new ArrayList<>();
  private final AtomicBoolean started;
  private final RouteTrie trie;
  private Injector injector;
  private Handler missingHandler;
  private Thread shutdownHook;

  public Web() {
    this.isChild = false;
    this.pathPrefix = "";
    this.trie = new RouteTrie();
    this.started = new AtomicBoolean(false);
    this.middlewareTrie = new MiddlewareTrie();
  }

  private Web(String pathPrefix, RouteTrie trie, AtomicBoolean started, MiddlewareTrie middlewareTrie, Injector injector) {
    this.isChild = true;
    this.pathPrefix = pathPrefix;
    this.trie = trie;
    this.started = started;
    this.middlewareTrie = middlewareTrie;
    this.injector = injector;
  }

  /**
   * Adds a task that runs after the server is shut down, whether by {@link #close()} or by the JVM shutdown hook that
   * {@link #start(int)} registers. Tasks run in the order they were added.
   *
   * @param task The task to run.
   * @return This Web instance for chaining.
   */
  public Web addShutdownTask(Runnable task) {
    Objects.requireNonNull(task, "task must not be null");
    shutdownTasks.add(task);
    return this;
  }

  /**
   * Sets the base directory for this Web's HTTP context. If not called, then {@code .} is used. Static files served by
   * {@link #files(String)} and the {@link Messages} directory are resolved under it.
   *
   * @param baseDir The base directory for file resolution.
   * @return This Web instance for chaining.
   * @throws IllegalStateException if called after {@link #start(int)}.
   */
  public Web baseDir(Path baseDir) {
    if (started.get()) {
      throw new IllegalStateException("Cannot set baseDir after Web has been started");
    }
    return withBaseDir(baseDir);
  }

  /**
   * Shuts down the server, runs the shutdown tasks, and removes the JVM shutdown hook.
   *
   * @throws IllegalStateException if called on a prefix child Web.
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

  @Override
  public HTTPServerConfiguration configuration() {
    return server.configuration();
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
   * Returns a middleware that resolves an instance of the given type from the injector on each request and delegates to
   * it.
   *
   * @param type The middleware type.
   * @return The delegating middleware.
   * @throws IllegalStateException if no injector has been configured.
   */
  public Middleware inject(Class<? extends Middleware> type) {
    Objects.requireNonNull(type, "type cannot be null");
    Injector current = requireInjector();
    return (req, res, chain) -> current.get(type).handle(req, res, chain);
  }

  /**
   * Returns a handler that resolves a controller of the given type from the injector on each request and invokes the
   * given method on it.
   *
   * @param <C>    The controller type.
   * @param type   The controller type.
   * @param method The controller method to invoke.
   * @return The delegating handler.
   * @throws IllegalStateException if no injector has been configured.
   */
  public <C> Handler inject(Class<C> type, ControllerHandler<C> method) {
    Objects.requireNonNull(type, "type cannot be null");
    Objects.requireNonNull(method, "method cannot be null");
    Injector current = requireInjector();
    return (req, res) -> method.handle(current.get(type), req, res);
  }

  /**
   * Returns a body handler that resolves a controller of the given type from the injector on each request and invokes
   * the given method on it with the parsed body.
   *
   * @param <C>    The controller type.
   * @param <T>    The type of the parsed body.
   * @param type   The controller type.
   * @param method The controller method to invoke.
   * @return The delegating body handler.
   * @throws IllegalStateException if no injector has been configured.
   */
  public <C, T> BodyHandler<T> inject(Class<C> type, ControllerBodyHandler<C, T> method) {
    Objects.requireNonNull(type, "type cannot be null");
    Objects.requireNonNull(method, "method cannot be null");
    Injector current = requireInjector();
    return (req, res, body) -> method.handle(current.get(type), req, res, body);
  }

  /**
   * Sets the injector used by the {@code inject} methods. The injector is called on each request, so it controls
   * instance lifetime. Must be called before {@code inject}. See {@link RequestContext} to make the request and
   * response themselves injectable.
   *
   * @param injector The injector.
   * @return This Web instance for chaining.
   * @throws IllegalStateException if called on a prefix child Web, or after {@link #start(int)}.
   */
  public Web injector(Injector injector) {
    if (isChild) {
      throw new IllegalStateException("Cannot call injector on a prefix child Web instance");
    }
    if (started.get()) {
      throw new IllegalStateException("Cannot set injector after Web has been started");
    }
    Objects.requireNonNull(injector, "injector must not be null");
    this.injector = injector;
    return this;
  }

  /**
   * Registers middlewares that run, in registration order, before any per-route middlewares and the handler. On the
   * root instance they run for every request; on an instance created by {@link #prefix(String, Consumer)} they run only
   * for requests under that prefix. They also run before the missing handler. Multiple calls append to the existing
   * list.
   *
   * @param middlewares One or more middlewares to install.
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
   * Sets the handler invoked when no route matches the request path. The handler receives the request and response just
   * like a route handler; it is responsible for setting an appropriate status (the default 404 behavior is replaced
   * wholesale). Any prefix middlewares that matched the unmatched path's prefix still run before this handler is
   * invoked.
   *
   * @param handler The handler to invoke for unmatched paths.
   * @return This Web instance for chaining.
   * @throws IllegalStateException if called on a prefix child Web, or after {@link #start(int)}.
   */
  public Web missingHandler(Handler handler) {
    if (isChild) {
      throw new IllegalStateException("Cannot call missingHandler on a prefix child Web instance");
    }
    if (started.get()) {
      throw new IllegalStateException("Cannot set missingHandler after Web has been started");
    }
    Objects.requireNonNull(handler, "handler must not be null");
    this.missingHandler = handler;
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
   * Groups routes under a common path prefix. Routes registered inside the callback have the prefix prepended, and
   * middlewares installed inside it apply only under the prefix. Prefixes nest when called inside another prefix
   * callback.
   *
   * @param newPrefix The prefix to prepend to all routes in the group.
   * @param group     A consumer that receives a Web instance scoped to the prefix.
   * @return This Web instance for chaining.
   * @throws IllegalStateException if called after {@link #start(int)}.
   */
  public Web prefix(String newPrefix, Consumer<Web> group) {
    if (started.get()) {
      throw new IllegalStateException("Cannot register routes after Web has been started");
    }

    Objects.requireNonNull(newPrefix, "newPrefix cannot be null");
    Objects.requireNonNull(group, "group cannot be null");
    Web child = new Web(pathPrefix + newPrefix, trie, started, middlewareTrie, injector);
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
   * @throws IllegalStateException    if called after {@link #start(int)}.
   * @throws IllegalArgumentException if {@code methods} is empty or holds a null, blank, or invalid method, if
   *                                  {@code pathSpec} is invalid, or if any entry in {@code middlewares} is null.
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
      if (!WebTools.isValidMethodToken(method)) {
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
   * The supplier runs after any middlewares. If it throws, the handler is not invoked and the exception propagates (an
   * {@link HTTPException} is rendered with its status). If it returns {@code null}, the handler is invoked with a
   * {@code null} body. See {@link BodySupplier#get(HTTPRequest, HTTPResponse)}.
   *
   * @param <T>         The type of the parsed body.
   * @param methods     The HTTP methods this route responds to (e.g., {@code List.of("POST", "PUT")}).
   * @param pathSpec    The path pattern to match (e.g., {@code /api/user/{id}}).
   * @param bodyHandler The handler to invoke with the parsed body when the route matches.
   * @param supplier    The supplier that parses the request body.
   * @param middlewares Zero or more per-route middlewares to run before the handler.
   * @return This Web instance for chaining.
   * @throws IllegalStateException    if called after {@link #start(int)}.
   * @throws IllegalArgumentException if {@code methods} is empty or holds a null, blank, or invalid method, if
   *                                  {@code pathSpec} is invalid, or if any entry in {@code middlewares} is null.
   * @see #route(Collection, String, Handler, Middleware...)
   */
  public <T> Web route(Collection<String> methods, String pathSpec, BodyHandler<T> bodyHandler, BodySupplier<T> supplier, Middleware... middlewares) {
    Objects.requireNonNull(bodyHandler, "bodyHandler cannot be null");
    Objects.requireNonNull(supplier, "supplier cannot be null");
    Handler adapted = (req, res) -> {
      // The supplier throws to signal a parse failure; a null return means an empty (but valid) body, which the
      // handler is given a chance to handle.
      T body = supplier.get(req, res);
      bodyHandler.handle(req, res, body);
    };
    return route(methods, pathSpec, adapted, middlewares);
  }

  /**
   * Starts the HTTP server.
   *
   * @return This Web instance for chaining.
   * @throws IllegalStateException if called on a prefix child Web, or if the server has already been started.
   */
  public Web start() {
    if (isChild) {
      throw new IllegalStateException("Cannot call start on a prefix child Web instance");
    }
    if (started.get()) {
      throw new IllegalStateException("Web has already been started");
    }
    if (server.configuration().getListeners().isEmpty()) {
      throw new IllegalStateException("No listeners are configured");
    }

    server.withHandler(this::handleRequest)
          .start();

    Thread hook;
    try {
      hook = new Thread(this::closeServer, "Web shutdown hook");
      Runtime.getRuntime().addShutdownHook(hook);
    } catch (IllegalStateException e) {
      // JVM is already shutting down; clean up the server we just started
      server.close();
      throw e;
    }

    shutdownHook = hook;
    started.set(true);

    var urls = server.configuration()
                     .getListeners()
                     .stream()
                     .map(WebTools::buildURL)
                     .collect(Collectors.joining(", "));
    LOG.log(System.Logger.Level.INFO, "Web application is available at [{0}]", urls);
    return this;
  }

  /**
   * Starts the HTTP server on the given port using a default listener configuration (non-TLS, all interfaces).
   *
   * @param port The port to listen on.
   * @return This Web instance for chaining.
   * @throws IllegalStateException if called on a prefix child Web, or if the server has already been started.
   */
  public Web start(int port) {
    server.withListener(new HTTPListenerConfiguration(port));
    return start();
  }

  private void closeServer() {
    // HTTPServer.close() is idempotent, so the rare close()/shutdown-hook race is harmless.
    server.close();

    // Run the shutdown tasks
    for (Runnable shutdownTask : shutdownTasks) {
      shutdownTask.run();
    }
  }

  private void dispatch(HTTPRequest request, HTTPResponse response) throws Exception {
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
        Handler notFound = missingHandler != null ? missingHandler : (_, res) -> res.setStatus(404);
        List<Middleware> prefixMiddlewares = middlewareTrie.collect(segments);
        if (prefixMiddlewares.isEmpty()) {
          notFound.handle(request, response);
        } else {
          new MiddlewareChainImpl(prefixMiddlewares, notFound).next(request, response);
        }
      }
    }
  }

  private void handleRequest(HTTPRequest request, HTTPResponse response) throws Exception {
    // Bind the request and response for everything below this point (middlewares, handlers, and anything an Injector
    // creates for them) so RequestContext can hand them out without parameters.
    ScopedValue.where(RequestContext.REQUEST, request)
               .where(RequestContext.RESPONSE, response)
               .call(() -> {
                 try {
                   dispatch(request, response);
                 } catch (HTTPException e) {
                   // Baseline safety net: render any uncaught HTTPException so framework failures (e.g. a body that
                   // fails to parse) produce their carried status without requiring an ExceptionHandler to be
                   // installed. A user-installed ExceptionHandler runs inside the chain and gets first crack; this
                   // only handles what reaches the top.
                   ExceptionHandler.DEFAULT_RENDERER.render(request, response, e);
                 }
                 return null;
               });
  }

  private Injector requireInjector() {
    if (injector == null) {
      throw new IllegalStateException("No injector configured. Call injector() before inject()");
    }
    return injector;
  }
}