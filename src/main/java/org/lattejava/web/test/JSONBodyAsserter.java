/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.test;

import module java.base;

import org.lattejava.web.internal.AnyObjectObserver;
import org.lattejava.web.internal.JSONParser;
import org.lattejava.web.internal.JSONProcessingException;

/**
 * A {@link BodyAsserter} that parses the response body as JSON and offers assertions over the parsed tree. Parsing
 * uses the Latte <code>json</code> library; values are held in their natural Java shapes ({@link Map}, {@link List},
 * {@link String}, {@link Long}, {@link BigInteger}, {@link BigDecimal}, {@link Boolean}, and {@code null}).
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
  private static final Object MISSING = new Object() {
    @Override
    public String toString() {
      return "<missing>";
    }
  };

  private boolean parsed;
  private Object root;
  private boolean unorderedArrays;

  public JSONBodyAsserter() {
    this(true);
  }

  public JSONBodyAsserter(boolean unorderedArrays) {
    this.unorderedArrays = unorderedArrays;
  }

  @Override
  public void body(byte[] body) {
    super.body(body);
    this.parsed = false;
    this.root = null;
  }

  /**
   * Asserts that the body is JSON-equivalent to the given expected string. Whitespace and key ordering are ignored.
   *
   * @param expected The expected JSON document.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter equalTo(String expected) {
    Object actual = parseActual();
    Object expectedTree = parse(expected, "expected");
    if (!deepEquals(actual, expectedTree)) {
      Assertions.failNotEqual(stringify(actual), stringify(expectedTree), "JSON body does not match");
    }
    return this;
  }

  /**
   * Asserts that the body, parsed as JSON, is equal to the JSON form of the given Java object. The expected object is
   * converted to its JSON shape the same way the Latte <code>json</code> library would serialize it: {@link Map}s
   * become JSON objects, {@link Iterable}s and arrays become JSON arrays, numbers, booleans, strings, and {@code null}
   * map to their JSON scalar forms, enums use their {@code name()}, and {@link UUID}, {@link URI}, {@link URL}, and
   * the ISO-8601 {@code java.time} types use their string forms. Records and POJOs with public fields are converted
   * via reflection; {@code null} components and fields are omitted, mirroring the library's {@code omitNulls} default.
   * Object-key ordering is always ignored; array ordering follows the {@link #unorderedArrays(boolean)} setting.
   * <p>
   * Reflection over a record or POJO in a named module requires its package to be opened (or exported) to
   * {@code org.lattejava.web}.
   *
   * @param expected The expected value.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter equalTo(Object expected) {
    Object actual = parseActual();
    Object expectedTree = toTree(expected);
    if (!deepEquals(actual, expectedTree)) {
      Assertions.failNotEqual(stringify(actual), stringify(expectedTree), "JSON body does not match");
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
    Object node = at(parseActual(), pointer);
    Assertions.assertTrue(node != MISSING, "JSON body is missing element at pointer [" + pointer + "]");
    return this;
  }

  /**
   * Asserts that the JSON tree does not contain a node at the given JSON Pointer.
   *
   * @param pointer The JSON Pointer.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter hasNoElement(String pointer) {
    Object node = at(parseActual(), pointer);
    Assertions.assertTrue(node == MISSING, "JSON body unexpectedly contains element at pointer [" + pointer + "]");
    return this;
  }

  /**
   * Asserts that the JSON value at the given JSON Pointer equals the expected string (compared via the value's text
   * form, so the JSON {@code 33} matches the expected {@code "33"} here).
   *
   * @param pointer  The JSON Pointer.
   * @param expected The expected text value.
   * @return This asserter for chaining.
   */
  public JSONBodyAsserter hasValue(String pointer, String expected) {
    Object node = at(parseActual(), pointer);
    String actual = (node == MISSING || node == null) ? null : asText(node);
    Assertions.assertEquals(actual, expected, "JSON value at pointer [" + pointer + "] does not match");
    return this;
  }

  /**
   * Asserts that the JSON value at the given JSON Pointer equals the JSON form of the given Java value. The expected
   * value is converted to its JSON shape as described on {@link #equalTo(Object)}, so numbers, booleans, strings,
   * {@code null}, {@link Map}s, {@link List}s, records, and POJOs are all accepted. Comparison is strict on JSON
   * type — {@code 33} (an int) does not match the JSON string {@code "33"}; use the {@code String} overload if you
   * want loose text comparison.
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
    Object node = at(parseActual(), pointer);
    if (node == MISSING) {
      node = null;
    }
    Object expectedTree = toTree(expected);
    if (!deepEquals(node, expectedTree)) {
      Assertions.failNotEqual(stringify(node), stringify(expectedTree), "JSON value at pointer [" + pointer + "] does not match");
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
   * The text form of a scalar JSON value, mirroring the loose text comparison of {@link #hasValue(String, String)}.
   * Containers have no text form and yield the empty string.
   */
  private String asText(Object node) {
    return switch (node) {
      case String s -> s;
      case BigDecimal decimal -> decimal.toPlainString();
      case Map<?, ?> _ -> "";
      case List<?> _ -> "";
      default -> node.toString();
    };
  }

  /**
   * Resolves an RFC 6901 JSON Pointer against the tree, returning {@link #MISSING} when any step of the pointer does
   * not exist (distinct from an explicit JSON {@code null}).
   */
  private Object at(Object node, String pointer) {
    if (pointer.isEmpty()) {
      return node;
    }
    if (pointer.charAt(0) != '/') {
      throw new IllegalArgumentException("Invalid JSON Pointer [" + pointer + "]");
    }
    Object current = node;
    for (String token : pointer.substring(1).split("/", -1)) {
      String key = token.replace("~1", "/").replace("~0", "~");
      if (current instanceof Map<?, ?> map) {
        current = map.containsKey(key) ? map.get(key) : MISSING;
      } else if (current instanceof List<?> list) {
        int index = parseIndex(key);
        current = (index >= 0 && index < list.size()) ? list.get(index) : MISSING;
      } else {
        return MISSING;
      }
      if (current == MISSING) {
        return MISSING;
      }
    }
    return current;
  }

  /**
   * Deep equality between two JSON values. JSON objects are always compared as unordered (compare key sets, recurse
   * on values). JSON arrays compare as multisets when {@link #unorderedArrays} is {@code true}, or positionally when
   * {@code false}. Decimals compare numerically (scale-insensitive), but cross-type comparisons stay strict (an
   * integer never equals a decimal, and a number never equals a string).
   */
  private boolean deepEquals(Object a, Object b) {
    if (a == b) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    if (a instanceof Map<?, ?> mapA && b instanceof Map<?, ?> mapB) {
      if (mapA.size() != mapB.size()) {
        return false;
      }
      for (Map.Entry<?, ?> entry : mapA.entrySet()) {
        if (!mapB.containsKey(entry.getKey()) || !deepEquals(entry.getValue(), mapB.get(entry.getKey()))) {
          return false;
        }
      }
      return true;
    }
    if (a instanceof List<?> listA && b instanceof List<?> listB) {
      int size = listA.size();
      if (size != listB.size()) {
        return false;
      }
      if (unorderedArrays) {
        boolean[] used = new boolean[size];
        for (Object elementA : listA) {
          boolean found = false;
          for (int j = 0; j < size; j++) {
            if (!used[j] && deepEquals(elementA, listB.get(j))) {
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
        if (!deepEquals(listA.get(i), listB.get(i))) {
          return false;
        }
      }
      return true;
    }
    if (a instanceof BigDecimal decimalA && b instanceof BigDecimal decimalB) {
      return decimalA.compareTo(decimalB) == 0;
    }
    return a.equals(b);
  }

  /**
   * Parses a JSON document into its natural Java shape. The underlying parser only accepts object-rooted documents,
   * so the document is wrapped in a single-key envelope before parsing; this lets bodies be any JSON value (object,
   * array, or scalar).
   */
  private Object parse(String json, String label) {
    if (json == null) {
      throw new AssertionError("JSON body for [" + label + "] is null");
    }
    try {
      Map<String, Object> wrapper = new JSONParser().parse("{\"v\":" + json + "}", new AnyObjectObserver());
      if (wrapper.size() != 1 || !wrapper.containsKey("v")) {
        throw new JSONProcessingException("Trailing content after JSON value");
      }
      return wrapper.get("v");
    } catch (JSONProcessingException e) {
      throw new AssertionError("Could not parse [" + label + "] as JSON: " + e.getMessage(), e);
    }
  }

  private Object parseActual() {
    if (!parsed) {
      root = parse(body == null ? null : new String(body, StandardCharsets.UTF_8), "body");
      parsed = true;
    }
    return root;
  }

  private int parseIndex(String token) {
    try {
      return Integer.parseInt(token);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Converts a record or a POJO with public fields to a JSON object shape via reflection. Null-valued components and
   * fields are omitted, mirroring the <code>json</code> library's {@code omitNulls} default.
   */
  private Object reflectTree(Object value) {
    Class<?> type = value.getClass();
    var map = new LinkedHashMap<String, Object>();
    try {
      if (type.isRecord()) {
        for (RecordComponent component : type.getRecordComponents()) {
          Method accessor = component.getAccessor();
          if (!accessor.canAccess(value)) {
            accessor.setAccessible(true);
          }
          Object componentValue = accessor.invoke(value);
          if (componentValue != null) {
            map.put(component.getName(), toTree(componentValue));
          }
        }
        return map;
      }

      List<Field> fields = Arrays.stream(type.getFields())
                                 .filter(field -> !Modifier.isStatic(field.getModifiers()))
                                 .toList();
      if (fields.isEmpty()) {
        throw new AssertionError("Cannot convert [" + type.getName() + "] to a JSON value: it is not a record and has no public fields");
      }
      for (Field field : fields) {
        if (!field.canAccess(value)) {
          field.setAccessible(true);
        }
        Object fieldValue = field.get(value);
        if (fieldValue != null) {
          map.put(field.getName(), toTree(fieldValue));
        }
      }
      return map;
    } catch (ReflectiveOperationException | InaccessibleObjectException e) {
      throw new AssertionError("Could not convert [" + type.getName() + "] to a JSON value via reflection."
          + " If the type is in a named module, its package must be opened (or exported) to [org.lattejava.web]: " + e.getMessage(), e);
    }
  }

  /**
   * Renders a parsed JSON value back to JSON text for failure messages.
   */
  private String stringify(Object node) {
    var builder = new StringBuilder();
    stringify(node, builder);
    return builder.toString();
  }

  private void stringify(Object node, StringBuilder builder) {
    switch (node) {
      case null -> builder.append("null");
      case String s -> stringifyString(s, builder);
      case BigDecimal decimal -> builder.append(decimal.toPlainString());
      case Map<?, ?> map -> {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          if (!first) {
            builder.append(',');
          }
          first = false;
          stringifyString(String.valueOf(entry.getKey()), builder);
          builder.append(':');
          stringify(entry.getValue(), builder);
        }
        builder.append('}');
      }
      case List<?> list -> {
        builder.append('[');
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            builder.append(',');
          }
          stringify(list.get(i), builder);
        }
        builder.append(']');
      }
      default -> builder.append(node);
    }
  }

  private void stringifyString(String s, StringBuilder builder) {
    builder.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> builder.append("\\\"");
        case '\\' -> builder.append("\\\\");
        case '\b' -> builder.append("\\b");
        case '\f' -> builder.append("\\f");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        case '\t' -> builder.append("\\t");
        default -> {
          if (c < 0x20) {
            builder.append(String.format("\\u%04x", (int) c));
          } else {
            builder.append(c);
          }
        }
      }
    }
    builder.append('"');
  }

  /**
   * Normalizes an expected Java value to the natural JSON shape produced by the parser, so comparisons see identical
   * types on both sides (all JSON integers are {@link Long}, all JSON decimals are {@link BigDecimal}, and so on).
   */
  private Object toTree(Object value) {
    return switch (value) {
      case null -> null;
      case String s -> s;
      case Boolean b -> b;
      case Character c -> String.valueOf(c);
      case Byte b -> (long) b;
      case Short s -> (long) s;
      case Integer i -> (long) i;
      case Long l -> l;
      case BigInteger bigInteger -> {
        try {
          yield bigInteger.longValueExact();
        } catch (ArithmeticException e) {
          yield bigInteger;
        }
      }
      case BigDecimal decimal -> decimal;
      case Float f -> new BigDecimal(f.toString());
      case Double d -> BigDecimal.valueOf(d);
      case Number number -> {
        BigDecimal decimal = new BigDecimal(number.toString());
        try {
          yield decimal.longValueExact();
        } catch (ArithmeticException e) {
          yield decimal;
        }
      }
      case Enum<?> e -> e.name();
      case UUID uuid -> uuid.toString();
      case URI uri -> uri.toString();
      case URL url -> url.toString();
      case Temporal temporal -> temporal.toString();
      case TemporalAmount amount -> amount.toString();
      case Map<?, ?> map -> {
        var tree = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          tree.put(String.valueOf(entry.getKey()), toTree(entry.getValue()));
        }
        yield tree;
      }
      case Iterable<?> iterable -> {
        var tree = new ArrayList<>();
        for (Object element : iterable) {
          tree.add(toTree(element));
        }
        yield tree;
      }
      default -> {
        if (value.getClass().isArray()) {
          int length = Array.getLength(value);
          var tree = new ArrayList<>(length);
          for (int i = 0; i < length; i++) {
            tree.add(toTree(Array.get(value, i)));
          }
          yield tree;
        }
        yield reflectTree(value);
      }
    };
  }
}
