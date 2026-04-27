/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.internal;

import module java.base;
import module org.lattejava.web;

/**
 * A literal-segment trie that maps path prefixes to middlewares. Installation appends to a node's middleware list;
 * collection walks from root along a request's segments, accumulating middlewares at each node visited. This yields
 * outer-to-inner ordering implicitly and {@code O(depth)} lookup per request, with no sort and no entry-set scan.
 * <p>
 * Prefixes are treated as literal segments only — no {@code {name}} parameter support, which matches how
 * {@link org.lattejava.web.Web#install(Middleware...)} scoping is defined.
 *
 * @author Brian Pontarelli
 */
public class MiddlewareTrie {
  private final Node root = new Node();

  /**
   * Collects middlewares that apply to the given request segments. Walks from the root along the segments, appending
   * middlewares from each node visited. Stops at the first segment with no matching child.
   *
   * @param requestSegments The parsed segments of the request path.
   * @return A freshly-allocated list of middlewares in outer-to-inner, FIFO order; empty if none apply.
   */
  public List<Middleware> collect(List<String> requestSegments) {
    Node node = root;
    List<Middleware> result = null;
    if (!node.middlewares.isEmpty()) {
      result = new ArrayList<>(node.middlewares);
    }

    for (String segment : requestSegments) {
      Node child = node.children.get(segment);
      if (child == null) {
        break;
      }

      if (!child.middlewares.isEmpty()) {
        if (result == null) {
          result = new ArrayList<>(child.middlewares);
        } else {
          result.addAll(child.middlewares);
        }
      }

      node = child;
    }

    return result == null ? List.of() : result;
  }

  /**
   * Installs middlewares at the given path prefix. The prefix is parsed via {@link PathParser#parsePath(String)}; an
   * empty or root-equivalent prefix installs at the root.
   *
   * @param pathPrefix  The path prefix (e.g., {@code ""}, {@code "/api"}, {@code "/api/v1"}).
   * @param middlewares The middlewares to append at that prefix, in order.
   */
  public void install(String pathPrefix, Middleware... middlewares) {
    if (middlewares.length == 0) {
      return;
    }

    Node node = root;
    for (String segment : PathParser.parsePath(pathPrefix)) {
      node = node.children.computeIfAbsent(segment, _ -> new Node());
    }

    Collections.addAll(node.middlewares, middlewares);
  }

  private static class Node {
    final Map<String, Node> children = new HashMap<>();
    final List<Middleware> middlewares = new ArrayList<>();
  }
}
