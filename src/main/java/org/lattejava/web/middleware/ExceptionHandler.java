/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.middleware;

import java.util.HashMap;
import java.util.Map;

import org.lattejava.http.server.HTTPRequest;
import org.lattejava.http.server.HTTPResponse;
import org.lattejava.web.*;

/**
 * A middleware that catches exceptions thrown by downstream middlewares or handlers and maps them
 * to HTTP status codes using a caller-supplied mapping.
 * <p>
 * When an exception is caught, the middleware walks the exception's class hierarchy (from most
 * specific to most general) and uses the first matching entry. If no entry matches, the exception
 * is re-thrown so the HTTP server's default handling (typically a 500) applies.
 * <p>
 * By default this middleware sets the status code but writes no response body. Subclasses can
 * override {@link #writeBody(HTTPRequest, HTTPResponse, Exception, int)} to emit an error payload
 * (for example, a JSON error document), or override {@link #lookupStatus(Class)} to change the
 * status-resolution strategy.
 *
 * @author Brian Pontarelli
 */
public class ExceptionHandler implements Middleware {
  private static final Map<Class<? extends Throwable>, Integer> DEFAULT_STATUS_BY_EXCEPTION =
      Map.of(UnauthenticatedException.class, 401);

  protected final Map<Class<? extends Throwable>, Integer> statusByException;

  /**
   * Constructs an ExceptionMiddleware with the given mapping. Any entry in {@code statusByException} overrides the
   * built-in defaults. Built-in defaults: {@link UnauthenticatedException} maps to {@code 401}.
   *
   * @param statusByException A map from exception class to HTTP status code. The map is defensively copied and merged
   *                          with built-in defaults (user-supplied entries win).
   */
  public ExceptionHandler(Map<Class<? extends Throwable>, Integer> statusByException) {
    Map<Class<? extends Throwable>, Integer> merged = new HashMap<>(DEFAULT_STATUS_BY_EXCEPTION);
    merged.putAll(statusByException);
    this.statusByException = Map.copyOf(merged);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    try {
      chain.next(req, res);
    } catch (Exception e) {
      Integer status = lookupStatus(e.getClass());
      if (status == null) {
        throw e;
      }
      res.setStatus(status);
      writeBody(req, res, e, status);
    }
  }

  /**
   * Looks up the status code for the given exception class, walking up the class hierarchy until
   * a match is found in {@link #statusByException} or the walk reaches {@code Object}. Subclasses
   * may override this to change the resolution strategy.
   *
   * @param type The exception class.
   * @return The mapped status code, or {@code null} if no mapping applies (in which case the
   *     exception will propagate).
   */
  protected Integer lookupStatus(Class<?> type) {
    Class<?> c = type;
    while (c != null && c != Object.class) {
      Integer status = statusByException.get(c);
      if (status != null) {
        return status;
      }
      c = c.getSuperclass();
    }
    return null;
  }

  /**
   * Called after the response status has been set in response to a mapped exception. The default
   * implementation writes nothing. Subclasses may override to emit an error body (JSON, HTML, etc.).
   * Any exception thrown from this method will propagate out of the middleware chain.
   *
   * @param req       The request.
   * @param res       The response; the status has already been set.
   * @param exception The caught exception that was mapped.
   * @param status    The status code that was mapped.
   */
  protected void writeBody(HTTPRequest req, HTTPResponse res, Exception exception, int status) throws Exception {
    // Default: no body.
  }
}
