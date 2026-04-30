/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.test;

import module com.fasterxml.jackson.databind;
import module java.base;

/**
 * A {@link BodyAsserter} that parses the response body as JSON and offers assertions over the parsed tree.
 * <p>
 * Element paths are <a href="https://www.rfc-editor.org/rfc/rfc6901">JSON Pointers</a> (RFC 6901): the empty string
 * refers to the root, {@code "/foo"} refers to the {@code foo} property, {@code "/items/0/id"} indexes into an array,
 * and {@code "/"} characters embedded in a property name are escaped as {@code "~1"}.
 * <p>
 * Equality comparisons always treat JSON objects as unordered (per JSON semantics). Arrays are unordered multisets by
 * default ({@code [1, 2, 3]} matches {@code [3, 2, 1]}, but {@code [1, 1, 2]} does not match {@code [1, 2, 2]}); pass
 * {@code false} to {@link #JSONBodyAsserter(boolean)} or call {@link #unorderedArrays(boolean)} to switch to strict
 * positional array comparison. Element types are always compared strictly — {@code 1} (int) does not match {@code 1.0}
 * (float).
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings({"unused", "UnusedReturnValue"})
public class JSONBodyAsserter extends BodyAsserter {
  private final ObjectMapper objectMapper;
  private JsonNode root;
  private boolean unorderedArrays;

  public JSONBodyAsserter() {
    this(new ObjectMapper(), true);
  }

  public JSONBodyAsserter(boolean unorderedArrays) {
    this(new ObjectMapper(), unorderedArrays);
  }

  public JSONBodyAsserter(ObjectMapper objectMapper) {
    this(objectMapper, true);
  }

  public JSONBodyAsserter(ObjectMapper objectMapper, boolean unorderedArrays) {
    this.objectMapper = objectMapper;
    this.unorderedArrays = unorderedArrays;
  }

  @Override
  public void body(byte[] body) {
    super.body(body);
    this.root = null;
  }

  /**
   * Asserts that the body is JSON-equivalent to the given expected string. Whitespace and key ordering are ignored.
   *
   * @param expected The expected JSON document.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter equalTo(String expected) {
    JsonNode actualNode = parseActual();
    JsonNode expectedNode = parse(expected, "expected");
    if (!deepEquals(actualNode, expectedNode)) {
      Assertions.failNotEqual(actualNode, expectedNode, "JSON body does not match");
    }
    return this;
  }

  /**
   * Asserts that the body, parsed as JSON, is equal to the JSON form of the given Java object. The expected object is
   * serialized to a {@link JsonNode} via {@link ObjectMapper#valueToTree}, so {@link java.util.Map}s,
   * {@link java.util.List}s, primitives, strings, {@code null}, and arbitrary POJOs all work. Object-key ordering is
   * always ignored; array ordering follows the {@link #unorderedArrays(boolean)} setting.
   *
   * @param expected The expected value.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter equalTo(Object expected) {
    JsonNode actualNode = parseActual();
    JsonNode expectedNode = objectMapper.valueToTree(expected);
    if (!deepEquals(actualNode, expectedNode)) {
      Assertions.failNotEqual(actualNode, expectedNode, "JSON body does not match");
    }
    return this;
  }

  /**
   * Asserts that the JSON tree contains a node at the given JSON Pointer.
   *
   * @param pointer The JSON Pointer (e.g. {@code "/user/name"} or {@code "/items/0/id"}).
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter hasElement(String pointer) {
    JsonNode node = parseActual().at(pointer);
    Assertions.assertTrue(!node.isMissingNode(), "JSON body is missing element at pointer [" + pointer + "]");
    return this;
  }

  /**
   * Asserts that the JSON tree does not contain a node at the given JSON Pointer.
   *
   * @param pointer The JSON Pointer.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter hasNoElement(String pointer) {
    JsonNode node = parseActual().at(pointer);
    Assertions.assertTrue(node.isMissingNode(), "JSON body unexpectedly contains element at pointer [" + pointer + "]");
    return this;
  }

  /**
   * Asserts that the JSON value at the given JSON Pointer equals the expected string (compared via the node's text
   * value, so the JSON {@code 33} matches the expected {@code "33"} here).
   *
   * @param pointer  The JSON Pointer.
   * @param expected The expected text value.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter hasValue(String pointer, String expected) {
    JsonNode node = parseActual().at(pointer);
    String actual = (node.isMissingNode() || node.isNull()) ? null : node.asText();
    Assertions.assertEquals(actual, expected, "JSON value at pointer [" + pointer + "] does not match");
    return this;
  }

  /**
   * Asserts that the JSON value at the given JSON Pointer equals the JSON form of the given Java value. The expected
   * value is serialized to a {@link JsonNode} via {@link ObjectMapper#valueToTree}, so numbers, booleans, strings,
   * {@code null}, {@link java.util.Map}s, {@link java.util.List}s, and POJOs are all accepted. Comparison is strict on
   * JSON node type — {@code 33} (an int) does not match the JSON string {@code "33"}; use the {@code String} overload
   * if you want loose text comparison.
   * <p>
   * A missing pointer is treated as a JSON {@code null} for this comparison, so {@code hasValue("/missing", null)}
   * succeeds whether the pointer is missing or explicitly {@code null}. Array ordering follows the
   * {@link #unorderedArrays(boolean)} setting.
   *
   * @param pointer  The JSON Pointer.
   * @param expected The expected value.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter hasValue(String pointer, Object expected) {
    JsonNode actualNode = parseActual().at(pointer);
    if (actualNode.isMissingNode()) {
      actualNode = NullNode.getInstance();
    }
    JsonNode expectedNode = objectMapper.valueToTree(expected);
    if (!deepEquals(actualNode, expectedNode)) {
      Assertions.failNotEqual(actualNode, expectedNode, "JSON value at pointer [" + pointer + "] does not match");
    }
    return this;
  }

  /**
   * Returns whether array equality is currently order-insensitive.
   *
   * @return {@code true} if arrays are compared as multisets, {@code false} for strict positional comparison.
   */
  public boolean unorderedArrays() {
    return unorderedArrays;
  }

  /**
   * Toggles whether array equality ignores element order. May be called at any time, including between assertions on
   * the same instance.
   *
   * @param unorderedArrays {@code true} to compare arrays as multisets, {@code false} to require positional equality.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter unorderedArrays(boolean unorderedArrays) {
    this.unorderedArrays = unorderedArrays;
    return this;
  }

  /**
   * Deep equality between two JSON nodes. JSON objects are always compared as unordered (compare key sets, recurse on
   * values). JSON arrays compare as multisets when {@link #unorderedArrays} is {@code true}, or positionally when
   * {@code false}. ValueNodes defer to {@link JsonNode#equals(Object)}, which keeps numeric type comparisons strict
   * (int vs. float).
   */
  private boolean deepEquals(JsonNode a, JsonNode b) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    if (a.isObject() && b.isObject()) {
      if (a.size() != b.size()) {
        return false;
      }
      Iterator<String> fields = a.fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if (!b.has(field) || !deepEquals(a.get(field), b.get(field))) {
          return false;
        }
      }
      return true;
    }
    if (a.isArray() && b.isArray()) {
      int size = a.size();
      if (size != b.size()) {
        return false;
      }
      if (unorderedArrays) {
        boolean[] used = new boolean[size];
        for (int i = 0; i < size; i++) {
          JsonNode elemA = a.get(i);
          boolean found = false;
          for (int j = 0; j < size; j++) {
            if (!used[j] && deepEquals(elemA, b.get(j))) {
              used[j] = true;
              found = true;
              break;
            }
          }
          if (!found) {
            return false;
          }
        }
        return true;
      }
      for (int i = 0; i < size; i++) {
        if (!deepEquals(a.get(i), b.get(i))) {
          return false;
        }
      }
      return true;
    }
    return a.equals(b);
  }

  private JsonNode parse(String json, String label) {
    if (json == null) {
      throw new AssertionError("JSON body for [" + label + "] is null");
    }
    try {
      return objectMapper.readTree(json);
    } catch (IOException e) {
      throw new AssertionError("Could not parse [" + label + "] as JSON: " + e.getMessage(), e);
    }
  }

  private JsonNode parseActual() {
    if (root == null) {
      root = parse(body == null ? null : new String(body, StandardCharsets.UTF_8), "body");
    }
    return root;
  }
}
