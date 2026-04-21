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
 * A middleware that serves static files from the server's {@link HTTPContext#baseDir}.
 * <p>
 * The URL prefix determines which requests this middleware handles. The subdirectory (relative to {@code baseDir}) is
 * where the files live on disk. For example, with urlPrefix {@code /assets} and subdirectory {@code assets}, a request
 * to {@code /assets/app.css} will be served from {@code <baseDir>/assets/app.css}.
 * <p>
 * Path traversal is blocked by {@link HTTPContext#resolve(String)}, which returns {@code null} when a resolved path
 * escapes the baseDir.
 * <p>
 * Ported in spirit from Prime MVC's StaticResourceWorkflow, minus classpath loading.
 *
 * @author Brian Pontarelli
 */
public class StaticResources implements Middleware {
  private final Duration cacheDuration;
  private final StaticResourceFilter filter;
  private final String subdirectory;
  private final String urlPrefix;

  /**
   * Constructs a middleware with a 7-day cache duration and no filter. The subdirectory will match the URL prefix
   * (minus the leading slash).
   *
   * @param urlPrefix The URL prefix this middleware owns (e.g., {@code /assets}).
   */
  public StaticResources(String urlPrefix) {
    this(urlPrefix, subdirectoryFromPrefix(urlPrefix), Duration.ofDays(7), null);
  }

  /**
   * Constructs a middleware with a 7-day cache duration and no filter.
   *
   * @param urlPrefix    The URL prefix this middleware owns.
   * @param subdirectory The subdirectory under {@code HTTPContext.baseDir} where files live.
   */
  public StaticResources(String urlPrefix, String subdirectory) {
    this(urlPrefix, subdirectory, Duration.ofDays(7), null);
  }

  /**
   * Constructs a middleware with all options.
   *
   * @param urlPrefix     The URL prefix this middleware owns.
   * @param subdirectory  The subdirectory under {@code HTTPContext.baseDir} where files live.
   * @param cacheDuration Cache duration for {@code Cache-Control}, {@code Expires}.
   * @param filter        Optional per-request filter; may be null.
   */
  public StaticResources(String urlPrefix, String subdirectory, Duration cacheDuration,
                         StaticResourceFilter filter) {
    Objects.requireNonNull(urlPrefix, "urlPrefix must not be null");
    Objects.requireNonNull(subdirectory, "subdirectory must not be null");
    Objects.requireNonNull(cacheDuration, "cacheDuration must not be null");
    if (!urlPrefix.startsWith("/")) {
      throw new IllegalArgumentException("urlPrefix must start with [/]: [" + urlPrefix + "]");
    }
    if (urlPrefix.length() > 1 && urlPrefix.endsWith("/")) {
      throw new IllegalArgumentException("urlPrefix must not end with [/]: [" + urlPrefix + "]");
    }
    this.urlPrefix = urlPrefix;
    this.subdirectory = subdirectory;
    this.cacheDuration = cacheDuration;
    this.filter = filter;
  }

  private static String subdirectoryFromPrefix(String urlPrefix) {
    if (urlPrefix == null || urlPrefix.isEmpty() || urlPrefix.equals("/")) {
      return "";
    }
    if (urlPrefix.startsWith("/")) {
      return urlPrefix.substring(1);
    }
    return urlPrefix;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    String path = req.getPath();

    // Does this request fall under our URL prefix?
    String relative = stripPrefix(path);
    if (relative == null) {
      chain.next(req, res);
      return;
    }

    // Optional filter check
    if (filter != null && !filter.allow(path, req)) {
      chain.next(req, res);
      return;
    }

    HTTPContext ctx = req.getContext();
    String appPath = subdirectory.isEmpty() ? relative : subdirectory + "/" + relative;
    Path resolved = ctx.resolve(appPath);
    if (resolved == null || !Files.isRegularFile(resolved) || !Files.isReadable(resolved)) {
      res.setStatus(404);
      return;
    }

    Instant lastModified = Files.getLastModifiedTime(resolved).toInstant().truncatedTo(ChronoUnit.SECONDS);
    Instant ifModifiedSince = null;
    try {
      ifModifiedSince = req.getDateHeader("If-Modified-Since");
    } catch (Exception ignored) {
      // Malformed header — treat as absent
    }
    if (ifModifiedSince != null && !lastModified.isAfter(ifModifiedSince)) {
      addCacheHeaders(res, lastModified);
      res.setStatus(304);
      return;
    }

    String contentType = Files.probeContentType(resolved);
    if (contentType != null) {
      res.setContentType(contentType);
    }
    res.setContentLength(Files.size(resolved));
    addCacheHeaders(res, lastModified);
    res.setStatus(200);

    // HEAD: headers only — the HTTP server suppresses body bytes anyway, but skipping the transfer avoids pointless file I/O.
    if (req.isHeadRequest()) {
      return;
    }

    try (InputStream in = Files.newInputStream(resolved)) {
      in.transferTo(res.getOutputStream());
    }
  }

  private void addCacheHeaders(HTTPResponse res, Instant lastModified) {
    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    ZonedDateTime expiry = now.plus(cacheDuration);
    res.setHeader("Cache-Control", "public, max-age=" + cacheDuration.toSeconds());
    res.setDateHeader("Date", now);
    res.setDateHeader("Expires", expiry);
    res.setDateHeader("Last-Modified", lastModified.atZone(ZoneOffset.UTC));
  }

  private String stripPrefix(String path) {
    if (urlPrefix.equals("/")) {
      if (path.length() < 2 || !path.startsWith("/")) {
        return null;
      }
      return path.substring(1);
    }
    if (!path.startsWith(urlPrefix)) {
      return null;
    }
    if (path.length() == urlPrefix.length()) {
      return null; // Request is exactly the prefix, no file portion
    }
    if (path.charAt(urlPrefix.length()) != '/') {
      return null; // /assetsX is not under /assets
    }
    return path.substring(urlPrefix.length() + 1);
  }

  /**
   * Optional filter for {@link StaticResources} requests. Implementations can inspect the request and URI and
   * return {@code false} to skip static-file resolution (falling through to the rest of the pipeline).
   *
   * @author Brian Pontarelli
   */
  @FunctionalInterface
  public interface StaticResourceFilter {
    /**
     * @param uri     The request URI.
     * @param request The request.
     * @return true to attempt static-file resolution; false to fall through.
     */
    boolean allow(String uri, HTTPRequest request);
  }
}
