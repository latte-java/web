/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.test.json;

import module java.base;

import org.lattejava.web.internal.*;

/**
 * Toolkit for working with JSON and objects.
 *
 * @author Brian Pontarelli
 */
public class JSONTools {
  private JSONTools() {
  }

  static final Object MISSING = new Object() {
    @Override
    public String toString() {
      return "<missing>";
    }
  };

  /**
   * The text form of a scalar JSON value, mirroring the loose text comparison of
   * {@link JSONBodyAsserter#hasValue(String, String)}. Containers have no text form and yield the empty string.
   */
  public static String asText(Object node) {
    return switch (node) {
      case String s -> s;
      case BigDecimal decimal -> decimal.toPlainString();
      case Map<?, ?> _, List<?> _ -> "";
      default -> node.toString();
    };
  }

  /**
   * Converts a record or a POJO with public fields to a JSON object shape via reflection. Null-valued components and
   * fields are omitted, mirroring the <code>json</code> library's {@code omitNulls} default.
   */
  public static Object objectToJSONMap(Object value) {
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
            map.put(component.getName(), toJSONObject(componentValue));
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
          map.put(field.getName(), toJSONObject(fieldValue));
        }
      }
      return map;
    } catch (ReflectiveOperationException | InaccessibleObjectException e) {
      throw new AssertionError("Could not convert [" + type.getName() + "] to a JSON value via reflection."
          + " If the type is in a named module, its package must be opened (or exported) to [org.lattejava.web]: " + e.getMessage(), e);
    }
  }

  /**
   * Parses a JSON document into its natural Java shape. The underlying parser only accepts object-rooted documents, so
   * the document is wrapped in a single-key envelope before parsing; this lets bodies be any JSON value (object, array,
   * or scalar).
   */
  public static Object parse(String json, String label) {
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

  public static int parseIndex(String token) {
    try {
      return Integer.parseInt(token);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Renders a parsed JSON value as pretty-printed JSON text via the vendored {@link JSONWriter} (2-space indent, map
   * insertion order preserved). When {@code escapeTokens} is {@code true}, literal <code>${</code> sequences in string
   * values are escaped as <code>$${</code> so the output can be written as an expected JSON file; preserved tokens
   * ({@link ExpectedJSONFile.Placeholder} and {@link Verbatim} nodes) are rendered exactly as they appeared in the
   * file.
   */
  public static String prettify(Object node, boolean escapeTokens) {
    return JSONWriter.acquire(false, true).anyElement(unwrap(node, escapeTokens)).finishString();
  }

  /**
   * Renders a parsed JSON value back to compact JSON text for failure messages, via the vendored {@link JSONWriter}.
   */
  public static String stringify(Object node) {
    return JSONWriter.acquire(false, false).anyElement(unwrap(node, false)).finishString();
  }

  /**
   * Normalizes an expected Java value to the natural JSON shape produced by the parser, so comparisons see identical
   * types on both sides (all JSON integers are {@link Long}, all JSON decimals are {@link BigDecimal}, and so on).
   */
  public static Object toJSONObject(Object value) {
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
          tree.put(String.valueOf(entry.getKey()), toJSONObject(entry.getValue()));
        }
        yield tree;
      }
      case Iterable<?> iterable -> {
        var tree = new ArrayList<>();
        for (Object element : iterable) {
          tree.add(toJSONObject(element));
        }
        yield tree;
      }
      default -> {
        if (value.getClass().isArray()) {
          int length = Array.getLength(value);
          var tree = new ArrayList<>(length);
          for (int i = 0; i < length; i++) {
            tree.add(toJSONObject(Array.get(value, i)));
          }
          yield tree;
        }
        yield objectToJSONMap(value);
      }
    };
  }

  /**
   * Normalizes a tree to the natural shapes {@link JSONWriter} writes: {@link ExpectedJSONFile.Placeholder} and
   * {@link Verbatim} nodes become their original file text, and literal <code>${</code> sequences in string values are
   * escaped as <code>$${</code> when {@code escapeTokens} is {@code true}.
   */
  public static Object unwrap(Object node, boolean escapeTokens) {
    return switch (node) {
      case ExpectedJSONFile.Placeholder placeholder -> placeholder.raw();
      case Verbatim verbatim -> verbatim.text();
      case String s -> escapeTokens ? s.replace("${", "$${") : s;
      case Map<?, ?> map -> {
        var tree = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          tree.put(String.valueOf(entry.getKey()), unwrap(entry.getValue(), escapeTokens));
        }
        yield tree;
      }
      case List<?> list -> {
        var tree = new ArrayList<>();
        for (Object element : list) {
          tree.add(unwrap(element, escapeTokens));
        }
        yield tree;
      }
      case null, default -> node;
    };
  }

  /**
   * Writes a tree to an expected JSON file as pretty-printed JSON with token escaping and a trailing newline, creating
   * parent directories as needed.
   */
  public static void write(Path file, Object tree) {
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, prettify(tree, true) + "\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError("Could not write the expected JSON file [" + file + "]: " + e.getMessage(), e);
    }
  }

  /**
   * Resolves an RFC 6901 JSON Pointer against the tree, returning {@link #MISSING} when any step of the pointer does
   * not exist (distinct from an explicit JSON {@code null}).
   */
  static Object at(Object node, String pointer) {
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
   * A string node written back to an expected JSON file exactly as it appeared in the original (a preserved token),
   * exempt from the <code>$${</code> escaping applied to literal strings.
   */
  record Verbatim(String text) {
  }
}
