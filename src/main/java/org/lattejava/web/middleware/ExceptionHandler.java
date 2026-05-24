/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.middleware;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * A middleware that catches exceptions thrown by downstream middlewares or handlers and renders an HTTP error
 * response.
 * <p>
 * Rendering is resolved in two steps. First, the middleware walks the caught exception's class hierarchy (most specific
 * to most general) looking for a registered per-type {@link ErrorRenderer}; if one matches, it renders the response.
 * Otherwise, if the exception is an {@link HTTPException}, the
 * {@linkplain #ExceptionHandler(ErrorRenderer) default renderer} handles it (reading the carried status and writing the
 * message). Any other exception is re-thrown so the HTTP server's default handling (typically a 500) applies — this
 * keeps unexpected exceptions visible rather than masking them behind a generic error body.
 * <p>
 * Each {@link ErrorRenderer} owns the entire response for its exception: it sets the status and writes the body. To
 * catch every exception (including non-{@code HTTPException} types), register a renderer against {@link Exception} or
 * {@link Throwable}.
 *
 * @author Brian Pontarelli
 */
public class ExceptionHandler implements Middleware {
  /**
   * The default renderer used for any {@link HTTPException} that has no more-specific renderer. It sets the status from
   * {@link HTTPException#status()} and, when the exception has a message, writes it as the response body. For any other
   * exception it sets a {@code 500} status.
   * <p>
   * This always writes the error out as JSON with the simple name of the exception under the key "error" and the
   * exception messages under the key "message".
   */
  public static final ErrorRenderer DEFAULT_RENDERER = (_, res, e) -> {
    int status = (e instanceof HTTPException he) ? he.status() : 500;
    res.setStatus(status);
    String message = e.getMessage();
    if (message != null) {
      res.getWriter().write("{\"error\":\"" + e.getClass().getSimpleName() + "\", \"message\":\"" + message.replace("\"", "\\\"") + "\"}");
    }
  };

  protected final ErrorRenderer defaultRenderer;
  protected final Map<Class<? extends Throwable>, ErrorRenderer> renderersByException;

  /**
   * Constructs an ExceptionHandler with the {@link #DEFAULT_RENDERER} and no per-type renderers.
   */
  public ExceptionHandler() {
    this(DEFAULT_RENDERER, Map.of());
  }

  /**
   * Constructs an ExceptionHandler with the given default renderer and no per-type renderers.
   *
   * @param defaultRenderer The renderer used for any {@link HTTPException} without a more-specific renderer.
   */
  public ExceptionHandler(ErrorRenderer defaultRenderer) {
    this(defaultRenderer, Map.of());
  }

  /**
   * Constructs an ExceptionHandler with the {@link #DEFAULT_RENDERER} and the given per-type renderers.
   *
   * @param renderersByException A map from exception class to the renderer that handles it. The map is defensively
   *                             copied.
   */
  public ExceptionHandler(Map<Class<? extends Throwable>, ErrorRenderer> renderersByException) {
    this(DEFAULT_RENDERER, renderersByException);
  }

  /**
   * Constructs an ExceptionHandler with the given default renderer and per-type renderers.
   *
   * @param defaultRenderer      The renderer used for any {@link HTTPException} without a more-specific renderer.
   * @param renderersByException A map from exception class to the renderer that handles it. The map is defensively
   *                             copied.
   */
  public ExceptionHandler(ErrorRenderer defaultRenderer, Map<Class<? extends Throwable>, ErrorRenderer> renderersByException) {
    this.defaultRenderer = defaultRenderer;
    this.renderersByException = Map.copyOf(renderersByException);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    try {
      chain.next(req, res);
    } catch (Exception e) {
      ErrorRenderer renderer = resolveRenderer(e.getClass());
      if (renderer != null) {
        renderer.render(req, res, e);
      } else if (e instanceof HTTPException) {
        defaultRenderer.render(req, res, e);
      } else {
        throw e;
      }
    }
  }

  /**
   * Resolves the per-type renderer for the given exception class, walking up the class hierarchy until a match is found
   * in {@link #renderersByException} or the walk reaches {@code Object}. Subclasses may override this to change the
   * resolution strategy.
   *
   * @param type The exception class.
   * @return The matching renderer, or {@code null} if none is registered for the class or any of its supertypes.
   */
  protected ErrorRenderer resolveRenderer(Class<?> type) {
    Class<?> c = type;
    while (c != null && c != Object.class) {
      ErrorRenderer renderer = renderersByException.get(c);
      if (renderer != null) {
        return renderer;
      }
      c = c.getSuperclass();
    }
    return null;
  }
}
