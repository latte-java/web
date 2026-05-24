/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

/**
 * Base class for exceptions that carry the HTTP status code that should be returned to the client. Throwing an
 * {@code HTTPException} (or a subtype) from a handler or middleware lets the framework render the response without any
 * caller-supplied exception-to-status mapping: the
 * {@link org.lattejava.web.middleware.ExceptionHandler#DEFAULT_RENDERER default renderer} reads {@link #status()} and
 * writes the message as the body.
 * <p>
 * Semantic subtypes ({@link BadRequestException}, {@link UnauthenticatedException}, {@link ForbiddenException},
 * {@link ServiceUnavailableException}) preset the status, so application and framework code can throw a meaningful type
 * rather than passing a bare integer.
 *
 * @author Brian Pontarelli
 */
public class HTTPException extends RuntimeException {
  private final int status;

  public HTTPException(int status) {
    this.status = status;
  }

  public HTTPException(int status, String message) {
    super(message);
    this.status = status;
  }

  public HTTPException(int status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  /**
   * @return The HTTP status code that should be sent to the client for this exception.
   */
  public int status() {
    return status;
  }
}
