/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.json;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * A {@link BodySupplier} that parses the request body as JSON into an instance of the given type using the Latte
 * <code>json</code> library. This uses annotation processors to build companion classes that handle serializing
 * and deserializing JSON to and from objects.
 * <p>
 * Parse and data-binding failures (malformed JSON, type mismatches, unknown properties, and the like) are translated
 * into a {@link BadRequestException}, which the framework renders as {@code 400 Bad Request}. The caller never has to
 * know about <code>json</code> exception types. An empty request body is not treated as an error: the supplier returns
 * {@code null} so the body handler is invoked with a {@code null} body and can decide whether a missing body is
 * acceptable.
 *
 * @param <T> The target type.
 * @author Brian Pontarelli
 */
public class JSONBodySupplier<T> implements BodySupplier<T> {
  private final Function<byte[], T> function;

  /**
   * Constructs a supplier by providing a function that will be used to deserialize the body from bytes. This generally
   * looks like this:
   * <p>
   * <code>
   * new JSONBodyHandler(MyObject::fromJSONBytes);
   * </code>
   *
   * @param function The function that will be used to deserialize the body.
   */
  public JSONBodySupplier(Function<byte[], T> function) {
    this.function = function;
  }

  /**
   * Factory method for creating a supplier with a function in a readable way.
   *
   * @param function The function that will be used to deserialize the body.
   * @param <T>      The target type.
   * @return The supplier instance.
   */
  public static <T> JSONBodySupplier<T> of(Function<byte[], T> function) {
    return new JSONBodySupplier<>(function);
  }

  @Override
  public T get(HTTPRequest req, HTTPResponse res) throws Exception {
    try {
      var body = req.getBodyBytes();
      if (body == null || body.length == 0) {
        return null;
      }

      return function.apply(body);
    } catch (RuntimeException e) {
      throw new BadRequestException("The request body could not be parsed as JSON", e);
    }
  }
}
