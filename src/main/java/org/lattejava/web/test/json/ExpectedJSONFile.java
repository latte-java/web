/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.test.json;

import module java.base;

/**
 * An expected (golden) JSON file for {@link JSONBodyAsserter#equalToFile(Path, Object...)}. Loading reads the file as
 * UTF-8, parses it, and walks the parsed tree replacing tokens in string nodes: a whole-node substitution token
 * becomes its typed value, a whole-node placeholder token becomes a {@link Placeholder}, embedded tokens are spliced
 * into the surrounding text, and <code>$${</code> unescapes to a literal <code>${</code>. Loading fails with
 * {@link AssertionError} on unparseable JSON, on unknown, malformed, or unterminated tokens, and on supplied
 * substitutions the file never references.
 *
 * @author Brian Pontarelli
 */
final class ExpectedJSONFile {
  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Set<String> PLACEHOLDER_NAMES = Set.of("anyBoolean", "anyInstant", "anyNumber", "anyString", "anyUUID");
  private static final Pattern TOKEN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

  final Object original;
  final Path path;
  final Object tree;
  private final Map<String, Object> substitutions;

  private ExpectedJSONFile(Path path, String text, Map<String, Object> substitutions) {
    this.path = path;
    this.substitutions = substitutions;
    this.original = JSONTools.parse(text, path.toString());

    var used = new HashSet<String>();
    this.tree = substitute(original, used);
    var unused = new TreeSet<>(substitutions.keySet());
    unused.removeAll(used);
    if (!unused.isEmpty()) {
      throw new AssertionError("Unused substitutions [" + String.join(", ", unused) + "] were never referenced in the expected JSON file [" + path + "]");
    }
  }

  /**
   * Loads and substitutes an expected JSON file, or returns {@code null} when the file does not exist so the caller
   * can bootstrap it.
   */
  static ExpectedJSONFile load(Path path, Map<String, Object> substitutions) {
    String text;
    try {
      text = Files.readString(path, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return null;
    } catch (IOException e) {
      throw new AssertionError("Could not read the expected JSON file [" + path + "]: " + e.getMessage(), e);
    }

    return new ExpectedJSONFile(path, text, substitutions);
  }

  /**
   * Re-substitutes a single string node from the original tree, for update mode to test whether a token still
   * describes the actual value. Usage tracking is discarded — the full tree was already checked during loading.
   */
  Object resolve(String node) {
    return substituteString(node, new HashSet<>());
  }

  /**
   * Splices substitution values into a string node containing embedded tokens, unescaping <code>$${</code> to a
   * literal <code>${</code> along the way. Fails on unterminated, malformed, or unknown tokens, and on placeholder
   * tokens, which must be the entire string node.
   */
  private String interpolate(String s, Set<String> used) {
    var builder = new StringBuilder();
    int i = 0;
    while (i < s.length()) {
      if (s.startsWith("$${", i)) {
        builder.append("${");
        i += 3;
        continue;
      }

      if (s.startsWith("${", i)) {
        int close = s.indexOf('}', i + 2);
        if (close == -1) {
          throw new AssertionError("Unterminated token [" + s.substring(i) + "] in the expected JSON file [" + path + "]");
        }

        String name = s.substring(i + 2, close);
        if (!NAME.matcher(name).matches()) {
          throw new AssertionError("Malformed token [" + s.substring(i, close + 1) + "] in the expected JSON file [" + path + "]");
        }
        if (PLACEHOLDER_NAMES.contains(name)) {
          throw new AssertionError("Placeholder token [${" + name + "}] must be the entire string node in the expected JSON file [" + path + "]");
        }
        if (!substitutions.containsKey(name)) {
          throw new AssertionError("Unknown token [${" + name + "}] in the expected JSON file [" + path + "]");
        }

        used.add(name);
        Object value = JSONTools.toJSONObject(substitutions.get(name));
        builder.append(value == null ? "null" : JSONTools.asText(value));
        i = close + 1;
        continue;
      }

      builder.append(s.charAt(i));
      i++;
    }
    return builder.toString();
  }

  /**
   * Walks the parsed tree, replacing tokens in string nodes and recording every referenced substitution name in
   * {@code used}.
   */
  private Object substitute(Object node, Set<String> used) {
    return switch (node) {
      case String s -> substituteString(s, used);
      case Map<?, ?> map -> {
        var tree = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          tree.put(String.valueOf(entry.getKey()), substitute(entry.getValue(), used));
        }
        yield tree;
      }
      case List<?> list -> {
        var tree = new ArrayList<>();
        for (Object element : list) {
          tree.add(substitute(element, used));
        }
        yield tree;
      }
      case null, default -> node;
    };
  }

  /**
   * Substitutes tokens in a single string node. A string that is exactly one token yields a {@link Placeholder} or
   * the typed substitution value; anything else is interpolated as text.
   */
  private Object substituteString(String s, Set<String> used) {
    if (s.startsWith("${regex:")) {
      if (!s.endsWith("}")) {
        throw new AssertionError("Malformed token [" + s + "] in the expected JSON file [" + path + "]");
      }

      try {
        return new Placeholder("regex", Pattern.compile(s.substring(8, s.length() - 1)), s);
      } catch (PatternSyntaxException e) {
        throw new AssertionError("Invalid regular expression in token [" + s + "] in the expected JSON file [" + path + "]: " + e.getMessage(), e);
      }
    }

    Matcher matcher = TOKEN.matcher(s);
    if (matcher.matches()) {
      String name = matcher.group(1);
      if (PLACEHOLDER_NAMES.contains(name)) {
        return new Placeholder(name, null, s);
      }
      if (substitutions.containsKey(name)) {
        used.add(name);
        return JSONTools.toJSONObject(substitutions.get(name));
      }
      throw new AssertionError("Unknown token [" + s + "] in the expected JSON file [" + path + "]");
    }

    return interpolate(s, used);
  }

  /**
   * A placeholder token from an expected JSON file ({@code ${anyString}}, {@code ${regex:PATTERN}}, and so on) that
   * matches the actual value by type or pattern; {@code raw} is the original string node so that failure output and
   * update mode can render the token as it appeared.
   */
  record Placeholder(String name, Pattern pattern, String raw) {
  }
}
