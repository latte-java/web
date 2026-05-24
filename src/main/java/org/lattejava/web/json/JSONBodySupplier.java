/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.json;

import module com.fasterxml.jackson.databind;
import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * A {@link BodySupplier} that parses the request body as JSON into an instance of the given type using Jackson.
 * <p>
 * Parse and data-binding failures (malformed JSON, type mismatches, unknown properties, and the like) are translated
 * into a {@link BadRequestException}, which the framework renders as {@code 400 Bad Request}. The caller never has to
 * know about Jackson's exception types. An empty request body is not treated as an error: the supplier returns
 * {@code null} so the body handler is invoked with a {@code null} body and can decide whether a missing body is
 * acceptable.
 *
 * @param <T> The target type.
 * @author Brian Pontarelli
 */
public class JSONBodySupplier<T> implements BodySupplier<T> {
  private final ObjectMapper objectMapper;
  private final Class<T> type;

  /**
   * Constructs a supplier using a default {@link ObjectMapper}.
   *
   * @param type The target type the body should be deserialized into.
   */
  public JSONBodySupplier(Class<T> type) {
    this(type, new ObjectMapper());
  }

  /**
   * Constructs a supplier using a caller-supplied {@link ObjectMapper}.
   *
   * @param type         The target type the body should be deserialized into.
   * @param objectMapper The Jackson mapper to use for deserialization.
   */
  public JSONBodySupplier(Class<T> type, ObjectMapper objectMapper) {
    this.type = type;
    this.objectMapper = objectMapper;
  }

  /**
   * Factory method for creating a supplier with a default {@link ObjectMapper}.
   *
   * @param type The target type the body should be deserialized into.
   * @param <T>  The target type.
   * @return The supplier instance.
   */
  public static <T> JSONBodySupplier<T> of(Class<T> type) {
    return new JSONBodySupplier<>(type);
  }

  /**
   * Factory method for creating a supplier with a specific {@link ObjectMapper}.
   *
   * @param type         The target type the body should be deserialized into.
   * @param objectMapper The Jackson mapper to use for deserialization.
   * @param <T>          The target type.
   * @return The supplier instance.
   */
  public static <T> JSONBodySupplier<T> of(Class<T> type, ObjectMapper objectMapper) {
    return new JSONBodySupplier<>(type, objectMapper);
  }

  @Override
  public T get(HTTPRequest req, HTTPResponse res) throws Exception {
    java.io.PushbackInputStream is = new java.io.PushbackInputStream(req.getInputStream());
    int first = is.read();
    if (first == -1) {
      // Empty body: not a parse failure. Let the handler decide whether a missing body is acceptable.
      return null;
    }
    is.unread(first);

    try {
      return objectMapper.readValue(is, type);
    } catch (IOException | RuntimeException e) {
      // Everything Jackson can throw while reading a value — parse errors and data-binding failures — means the client
      // sent a body we cannot use. Translate it into a 400 so the caller is not exposed to Jackson's exception types.
      throw new BadRequestException("The request body could not be parsed as JSON", e);
    }
  }
}
