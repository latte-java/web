/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lattejava.http.server.HTTPListenerConfiguration;
import org.lattejava.http.server.HTTPRequest;
import org.lattejava.http.server.HTTPResponse;
import org.lattejava.http.server.HTTPServer;

/**
 * A lightweight web framework built on top of the Latte Java HTTP server.
 *
 * @author Brian Pontarelli
 */
public class Web {
  private final List<Route> routes = new ArrayList<>();

  private HTTPServer server;

  /**
   * Registers a route that matches any HTTP method on the given path.
   * <p>
   * Path parameters are supported using curly brace syntax (e.g., {@code /api/user/{id}}). Matched parameter values are
   * stored as request attributes accessible via {@link HTTPRequest#getAttribute(String)}.
   *
   * @param pathSpec The path pattern to match (e.g., {@code /api/user/{id}}).
   * @param handler  The handler to invoke when the path matches.
   * @return This Web instance for chaining.
   */
  public Web route(String pathSpec, Handler handler) {
    routes.add(new Route(pathSpec, handler));
    return this;
  }

  /**
   * Starts the HTTP server on the given port.
   *
   * @param port The port to listen on.
   * @return This Web instance for chaining.
   */
  public Web start(int port) {
    server = new HTTPServer()
        .withHandler(this::handleRequest)
        .withListener(new HTTPListenerConfiguration(port))
        .start();
    return this;
  }

  /**
   * Blocks the calling thread until the JVM is shut down.
   */
  public void daemon() {
    if (server == null) {
      throw new IllegalStateException("Server has not been started. Call start() first.");
    }

    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Shuts down the server.
   */
  public void close() {
    if (server != null) {
      server.close();
    }
  }

  private void handleRequest(HTTPRequest request, HTTPResponse response) throws Exception {
    String path = request.getPath();

    for (Route route : routes) {
      Matcher matcher = route.pattern.matcher(path);
      if (matcher.matches()) {
        // Extract path parameters and set them as request attributes
        List<String> paramNames = route.paramNames;
        for (int i = 0; i < paramNames.size(); i++) {
          request.setAttribute(paramNames.get(i), matcher.group(i + 1));
        }

        route.handler.handle(request, response);
        return;
      }
    }

    // No route matched
    response.setStatus(404);
    response.getOutputStream().close();
  }

  static class Route {
    final Handler handler;

    final List<String> paramNames;

    final String pathSpec;

    final Pattern pattern;

    Route(String pathSpec, Handler handler) {
      this.pathSpec = pathSpec;
      this.handler = handler;
      this.paramNames = new ArrayList<>();

      // Build a regex from the path spec, extracting parameter names
      // e.g., /api/user/{id} -> /api/user/([^/]+)
      StringBuilder regex = new StringBuilder("^");
      String[] segments = pathSpec.split("/", -1);
      for (int i = 0; i < segments.length; i++) {
        if (i > 0) {
          regex.append("/");
        }

        String segment = segments[i];
        if (segment.startsWith("{") && segment.endsWith("}")) {
          paramNames.add(segment.substring(1, segment.length() - 1));
          regex.append("([^/]+)");
        } else {
          regex.append(Pattern.quote(segment));
        }
      }
      regex.append("$");

      this.pattern = Pattern.compile(regex.toString());
    }
  }
}
