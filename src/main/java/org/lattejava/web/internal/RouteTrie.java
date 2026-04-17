/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.internal;

import java.util.*;

import org.lattejava.web.Handler;
import org.lattejava.web.Middleware;

/**
 * A segment-level trie for O(path_length) route matching.
 * <p>
 * Literal children are always tried before the param child (structural priority — no scoring
 * needed). The matcher backtracks from a static dead-end to the param child.
 *
 * @author Brian Pontarelli
 */
public class RouteTrie {
  private static final int MAX_SEGMENTS = 256;

  private final Node root = new Node();

  /**
   * Inserts a route into the trie.
   *
   * @param pathSpec    the path specification (e.g., {@code /api/users/{id}})
   * @param methods     the HTTP methods this route handles
   * @param handler     the handler to invoke
   * @param middlewares the per-route middlewares to run before the handler
   * @throws IllegalArgumentException if the pathSpec is invalid or contains duplicate param names
   * @throws IllegalStateException    if conflicting parameter names exist at the same trie position
   */
  public void insert(String pathSpec, Collection<String> methods, Handler handler, List<Middleware> middlewares) {
    var segments = PathParser.parse(pathSpec);
    Node node = root;

    for (var segment : segments) {
      if (segment instanceof PathParser.Segment.Literal(var value)) {
        if (node.staticChildren == null) {
          node.staticChildren = new HashMap<>();
        }
        node = node.staticChildren.computeIfAbsent(value, k -> new Node());
      } else if (segment instanceof PathParser.Segment.Param(var name)) {
        if (node.paramChild == null) {
          node.paramChild = new Node();
          node.paramName = name;
        } else if (!node.paramName.equals(name)) {
          throw new IllegalStateException(
              "Conflicting parameter names at same position: existing=[" + node.paramName + "], new=[" + name + "]");
        }
        node = node.paramChild;
      }
    }

    // At terminal node — register methods
    if (node.entriesByMethod == null) {
      node.entriesByMethod = new LinkedHashMap<>();
    }
    RouteEntry entry = new RouteEntry(handler, List.copyOf(middlewares));
    for (String method : methods) {
      String upper = method.toUpperCase(Locale.ROOT);
      if (node.entriesByMethod.containsKey(upper)) {
        throw new IllegalStateException(
            "Duplicate route registration for method [" + upper + "] on pathSpec [" + pathSpec + "]");
      }
      node.entriesByMethod.put(upper, entry);
    }
  }

  /**
   * Matches a request path and method against the trie.
   *
   * @param path   the request path (e.g., {@code /api/users/42})
   * @param method the HTTP method (e.g., {@code GET})
   * @return the match outcome
   */
  public Outcome match(String path, String method) {
    String[] parts = path.split("/", -1);
    // parts[0] is always "" (before the leading '/') — drop it
    String[] segments = new String[parts.length - 1];
    System.arraycopy(parts, 1, segments, 0, segments.length);

    // Defend against adversarially deep paths
    if (segments.length > MAX_SEGMENTS) {
      return Outcome.NotFound.INSTANCE;
    }

    Map<String, String> params = new HashMap<>();
    return matchRecursive(root, segments, 0, params, method.toUpperCase(Locale.ROOT));
  }

  private Outcome matchRecursive(Node node, String[] segments, int idx,
                                 Map<String, String> params, String method) {
    if (idx == segments.length) {
      // End of path — check for terminal handler
      if (node.entriesByMethod == null || node.entriesByMethod.isEmpty()) {
        return Outcome.NotFound.INSTANCE;
      }
      RouteEntry entry = node.entriesByMethod.get(method);
      if (entry != null) {
        return new Outcome.Found(entry.handler(), entry.middlewares(), Map.copyOf(params));
      }
      return new Outcome.MethodNotAllowed(Collections.unmodifiableSet(new LinkedHashSet<>(node.entriesByMethod.keySet())));
    }

    String segment = segments[idx];
    Set<String> aggregatedAllowed = null;

    // 1. Try static child first (literal beats param structurally)
    if (node.staticChildren != null) {
      Node staticChild = node.staticChildren.get(segment);
      if (staticChild != null) {
        Outcome result = matchRecursive(staticChild, segments, idx + 1, params, method);
        if (result instanceof Outcome.Found) {
          return result;  // literal wins on success
        }
        if (result instanceof Outcome.MethodNotAllowed mna) {
          aggregatedAllowed = new LinkedHashSet<>(mna.allowedMethods());
          // fall through to try param branch
        }
        // NotFound: fall through
      }
    }

    // 2. Try param child — but only for non-empty segments
    if (node.paramChild != null && !segment.isEmpty()) {
      params.put(node.paramName, segment);
      Outcome result = matchRecursive(node.paramChild, segments, idx + 1, params, method);
      params.remove(node.paramName);  // backtrack
      if (result instanceof Outcome.Found) {
        return result;
      }
      if (result instanceof Outcome.MethodNotAllowed mna) {
        if (aggregatedAllowed == null) {
          aggregatedAllowed = new LinkedHashSet<>(mna.allowedMethods());
        } else {
          aggregatedAllowed.addAll(mna.allowedMethods());
        }
      }
      // NotFound: fall through
    }

    if (aggregatedAllowed != null) {
      return new Outcome.MethodNotAllowed(Collections.unmodifiableSet(aggregatedAllowed));
    }
    return Outcome.NotFound.INSTANCE;
  }

  /**
   * Pairs a handler with its per-route middlewares.
   */
  public record RouteEntry(Handler handler, List<Middleware> middlewares) {}

  /**
   * The result of a route match.
   */
  public sealed interface Outcome permits Outcome.Found, Outcome.MethodNotAllowed, Outcome.NotFound {
    enum NotFound implements Outcome {
      INSTANCE
    }

    record Found(Handler handler, List<Middleware> middlewares, Map<String, String> pathParams) implements Outcome {
    }

    record MethodNotAllowed(Set<String> allowedMethods) implements Outcome {
    }
  }

  static class Node {
    // terminal data; null on internal nodes; lazy-init
    Map<String, RouteEntry> entriesByMethod;

    // single {name} child; lazy-init
    Node paramChild;

    // name of the param captured at this node
    String paramName;

    // literal segment text -> child; lazy-init
    Map<String, Node> staticChildren;
  }
}
