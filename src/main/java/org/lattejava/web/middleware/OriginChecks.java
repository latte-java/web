/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.middleware;

import module java.base;
import module org.lattejava.http;

import org.lattejava.web.*;

/**
 * A middleware that rejects unsafe-method requests whose {@code Origin} header does not match the server's own origin
 * (or one of a configured list). This is a defense-in-depth CSRF layer that complements {@code SameSite=Strict} on
 * session cookies.
 * <p>
 * Safe methods ({@code GET}, {@code HEAD}, {@code OPTIONS}) always pass through unchecked. For unsafe methods:
 * <ul>
 *   <li>A missing {@code Origin} header is allowed when {@code requireOrigin} is {@code false}
 *       (the default) — non-browser clients such as curl and build tools don't auto-attach cookies,
 *       so there is no CSRF vector to defend against.</li>
 *   <li>A present {@code Origin} header must match either one of the configured {@code allowedOrigins}
 *       or, when unconfigured, the request's own {@link HTTPRequest#getBaseURL() base URL}. A mismatch
 *       returns 403.</li>
 * </ul>
 *
 * @author Brian Pontarelli
 */
public class OriginChecks implements Middleware {
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  private final List<URI> allowedOrigins;
  private final boolean requireOrigin;

  /**
   * Constructs an origin check with defaults: {@code requireOrigin=false} and auto-derive from the request's
   * {@link HTTPRequest#getBaseURL()}.
   */
  public OriginChecks() {
    this(false, null);
  }

  /**
   * Constructs an origin check with the given {@code requireOrigin} flag; origins auto-derive from the request's
   * {@link HTTPRequest#getBaseURL()}.
   *
   * @param requireOrigin When {@code true}, a missing {@code Origin} header returns 403.
   */
  public OriginChecks(boolean requireOrigin) {
    this(requireOrigin, null);
  }

  /**
   * Constructs an origin check with an explicit list of allowed origins; {@code requireOrigin} defaults to
   * {@code false}.
   *
   * @param allowedOrigins The origins permitted on unsafe-method requests. Must be non-empty. Each URI must be
   *                       origin-only (scheme + host, optional port, no path beyond {@code /}, no
   *                       userinfo/query/fragment).
   */
  public OriginChecks(List<URI> allowedOrigins) {
    this(false, allowedOrigins);
  }

  /**
   * Constructs an origin check with the given {@code requireOrigin} flag and allowed-origins list.
   *
   * @param requireOrigin  When {@code true}, a missing {@code Origin} header returns 403.
   * @param allowedOrigins The origins permitted on unsafe-method requests, or {@code null} to auto-derive from
   *                       {@link HTTPRequest#getBaseURL()}. When non-null, must be non-empty; each URI must be
   *                       origin-only.
   */
  public OriginChecks(boolean requireOrigin, List<URI> allowedOrigins) {
    this.requireOrigin = requireOrigin;

    if (allowedOrigins != null) {
      if (allowedOrigins.isEmpty()) {
        throw new IllegalArgumentException("allowedOrigins must not be empty");
      }

      this.allowedOrigins = allowedOrigins.stream().map(OriginChecks::normalizeStrict).toList();
    } else {
      this.allowedOrigins = null;
    }
  }

  private static URI canonicalize(URI uri) {
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    if (scheme.equals("http") && port == 80) {
      port = -1;
    } else if (scheme.equals("https") && port == 443) {
      port = -1;
    }

    try {
      return new URI(scheme, null, host, port, null, null, null);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("Cannot canonicalize origin: [" + uri + "]", e);
    }
  }

  private static URI normalizeStrict(URI uri) {
    Objects.requireNonNull(uri, "allowed origin must not be null");
    if (uri.getScheme() == null) {
      throw new IllegalArgumentException("allowed origin must have a scheme: [" + uri + "]");
    }
    if (uri.getHost() == null) {
      throw new IllegalArgumentException("allowed origin must have a host: [" + uri + "]");
    }
    if (uri.getUserInfo() != null) {
      throw new IllegalArgumentException("allowed origin must not have userinfo: [" + uri + "]");
    }
    if (uri.getQuery() != null) {
      throw new IllegalArgumentException("allowed origin must not have a query: [" + uri + "]");
    }
    if (uri.getFragment() != null) {
      throw new IllegalArgumentException("allowed origin must not have a fragment: [" + uri + "]");
    }
    String path = uri.getPath();
    if (path != null && !path.isEmpty() && !path.equals("/")) {
      throw new IllegalArgumentException("allowed origin must not have a path: [" + uri + "]");
    }
    return canonicalize(uri);
  }

  private static URI tryNormalize(String s) {
    try {
      URI uri = URI.create(s);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return null;
      }

      return canonicalize(uri);
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    if (SAFE_METHODS.contains(req.getMethod().name())) {
      chain.next(req, res);
      return;
    }

    String originHeader = req.getHeader("Origin");
    if (originHeader == null) {
      if (requireOrigin) {
        res.setStatus(403);
        return;
      }

      chain.next(req, res);
      return;
    }

    URI origin = tryNormalize(originHeader);
    if (origin == null) {
      res.setStatus(403);
      return;
    }

    boolean match;
    if (allowedOrigins != null) {
      match = allowedOrigins.contains(origin);
    } else {
      URI baseOrigin = tryNormalize(req.getBaseURL());
      match = baseOrigin != null && baseOrigin.equals(origin);
    }

    if (!match) {
      res.setStatus(403);
      return;
    }

    chain.next(req, res);
  }
}
