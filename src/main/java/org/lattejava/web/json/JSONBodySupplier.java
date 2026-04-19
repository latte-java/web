/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.json;

import java.io.*;

import com.fasterxml.jackson.databind.*;
import org.lattejava.http.server.*;
import org.lattejava.web.*;

/**
 * A {@link BodySupplier} that parses the request body as JSON into an instance of the given type using Jackson.
 * <p>
 * On parse failure, the supplier sets the response status to {@code 400 Bad Request} and returns {@code null}, causing
 * the framework to short-circuit the handler. No response body is written by the supplier itself; subclass or compose
 * with additional middleware if you need an error payload.
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
    try {
      return objectMapper.readValue(req.getInputStream(), type);
    } catch (IOException e) {
      res.setStatus(400);
      return null;
    }
  }
}
