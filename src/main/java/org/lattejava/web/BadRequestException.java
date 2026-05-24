/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

/**
 * An {@link HTTPException} that maps to {@code 400 Bad Request}. Thrown when the client's request cannot be processed —
 * for example, when a request body cannot be parsed into the expected type.
 *
 * @author Brian Pontarelli
 */
public class BadRequestException extends HTTPException {
  public BadRequestException() {
    super(400);
  }

  public BadRequestException(String message) {
    super(400, message);
  }

  public BadRequestException(String message, Throwable cause) {
    super(400, message, cause);
  }
}
