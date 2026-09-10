/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.middleware;

import module org.lattejava.http;
import module org.lattejava.web;

/**
 * A middleware that emits a strict set of HTTP security headers on every response. Headers are written before the
 * chain is invoked, so 404 and 5xx responses also carry them. A header already present on the response is left alone,
 * and a downstream handler can override any header by calling {@code setHeader} again.
 * <p>
 * Instances are immutable. Obtain one from {@link #defaults()} (every header at its most-secure value) or
 * {@link #empty()} (no headers at all), then derive variants with the per-header methods. Each returns a new
 * {@code SecurityHeaders} with a single header changed; passing {@code null} clears that header.
 * <p>
 * When the request host is {@code localhost} or {@code 127.0.0.1}, the {@code upgrade-insecure-requests} directive is
 * removed from the Content-Security-Policy so local development over plain HTTP works.
 * <p>
 * This middleware does not run on {@code 405 Method Not Allowed} responses, which bypass the middleware chain. Those
 * responses carry only {@code Allow} and have no body, so the missing headers are not a meaningful gap.
 *
 * @author Brian Pontarelli
 */
public class SecurityHeaders implements Middleware {
  private final String contentSecurityPolicy;
  private final String crossOriginEmbedderPolicy;
  private final String crossOriginOpenerPolicy;
  private final String crossOriginResourcePolicy;
  private final String permissionsPolicy;
  private final String referrerPolicy;
  private final String strictTransportSecurity;
  private final String xContentTypeOptions;
  private final String xFrameOptions;
  private final String xXSSProtection;

  private SecurityHeaders(String contentSecurityPolicy, String crossOriginEmbedderPolicy,
                          String crossOriginOpenerPolicy, String crossOriginResourcePolicy,
                          String permissionsPolicy, String referrerPolicy, String strictTransportSecurity,
                          String xContentTypeOptions, String xFrameOptions, String xXSSProtection) {
    this.contentSecurityPolicy = contentSecurityPolicy;
    this.crossOriginEmbedderPolicy = crossOriginEmbedderPolicy;
    this.crossOriginOpenerPolicy = crossOriginOpenerPolicy;
    this.crossOriginResourcePolicy = crossOriginResourcePolicy;
    this.permissionsPolicy = permissionsPolicy;
    this.referrerPolicy = referrerPolicy;
    this.strictTransportSecurity = strictTransportSecurity;
    this.xContentTypeOptions = xContentTypeOptions;
    this.xFrameOptions = xFrameOptions;
    this.xXSSProtection = xXSSProtection;
  }

  /**
   * @return A new instance with every header set to its most-secure default value. The Content-Security-Policy default
   *     is {@code CSP.defaults().build()}.
   */
  public static SecurityHeaders defaults() {
    return new SecurityHeaders(
        CSP.defaults().build(),
        "require-corp",
        "same-origin",
        "same-origin",
        "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()",
        "no-referrer",
        "max-age=31536000; includeSubDomains; preload",
        "nosniff",
        "DENY",
        "0");
  }

  /**
   * @return A new instance with no headers set. Installed as-is it emits nothing; add headers with the per-header
   *     methods.
   */
  public static SecurityHeaders empty() {
    return new SecurityHeaders(null, null, null, null, null, null, null, null, null, null);
  }

  /**
   * @param csp The Content-Security-Policy builder, or {@code null} to clear the header.
   * @return A new instance with the Content-Security-Policy set to {@code csp.build()} (or cleared when {@code null}).
   */
  public SecurityHeaders contentSecurityPolicy(CSP csp) {
    return contentSecurityPolicy(csp == null ? null : csp.build());
  }

  /**
   * @param value The Content-Security-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Content-Security-Policy set to {@code value}.
   */
  public SecurityHeaders contentSecurityPolicy(String value) {
    return new SecurityHeaders(value, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The Cross-Origin-Embedder-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Cross-Origin-Embedder-Policy set to {@code value}.
   */
  public SecurityHeaders crossOriginEmbedderPolicy(String value) {
    return new SecurityHeaders(contentSecurityPolicy, value, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The Cross-Origin-Opener-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Cross-Origin-Opener-Policy set to {@code value}.
   */
  public SecurityHeaders crossOriginOpenerPolicy(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, value,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The Cross-Origin-Resource-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Cross-Origin-Resource-Policy set to {@code value}.
   */
  public SecurityHeaders crossOriginResourcePolicy(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        value, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    if (strictTransportSecurity != null && res.getHeader("Strict-Transport-Security") == null) {
      res.setHeader("Strict-Transport-Security", strictTransportSecurity);
    }
    if (contentSecurityPolicy != null && res.getHeader("Content-Security-Policy") == null) {
      String host = req.getHost();
      String csp = contentSecurityPolicy;
      if (host.equalsIgnoreCase("localhost") || host.equalsIgnoreCase("127.0.0.1")) {
        csp = csp.replaceAll(
            "(?:\\bupgrade-insecure-requests\\s*;\\s*|\\s*;\\s*\\bupgrade-insecure-requests\\b\\s*$|^\\s*upgrade-insecure-requests\\s*$)",
            "").trim();
      }

      res.setHeader("Content-Security-Policy", csp);
    }
    if (xContentTypeOptions != null && res.getHeader("X-Content-Type-Options") == null) {
      res.setHeader("X-Content-Type-Options", xContentTypeOptions);
    }
    if (xFrameOptions != null && res.getHeader("X-Frame-Options") == null) {
      res.setHeader("X-Frame-Options", xFrameOptions);
    }
    if (xXSSProtection != null && res.getHeader("X-XSS-Protection") == null) {
      res.setHeader("X-XSS-Protection", xXSSProtection);
    }
    if (referrerPolicy != null && res.getHeader("Referrer-Policy") == null) {
      res.setHeader("Referrer-Policy", referrerPolicy);
    }
    if (permissionsPolicy != null && res.getHeader("Permissions-Policy") == null) {
      res.setHeader("Permissions-Policy", permissionsPolicy);
    }
    if (crossOriginOpenerPolicy != null && res.getHeader("Cross-Origin-Opener-Policy") == null) {
      res.setHeader("Cross-Origin-Opener-Policy", crossOriginOpenerPolicy);
    }
    if (crossOriginEmbedderPolicy != null && res.getHeader("Cross-Origin-Embedder-Policy") == null) {
      res.setHeader("Cross-Origin-Embedder-Policy", crossOriginEmbedderPolicy);
    }
    if (crossOriginResourcePolicy != null && res.getHeader("Cross-Origin-Resource-Policy") == null) {
      res.setHeader("Cross-Origin-Resource-Policy", crossOriginResourcePolicy);
    }
    chain.next(req, res);
  }

  /**
   * @param value The Permissions-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Permissions-Policy set to {@code value}.
   */
  public SecurityHeaders permissionsPolicy(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, value, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The Referrer-Policy header value, or {@code null} to clear the header.
   * @return A new instance with the Referrer-Policy set to {@code value}.
   */
  public SecurityHeaders referrerPolicy(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, value, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The Strict-Transport-Security header value, or {@code null} to clear the header.
   * @return A new instance with the Strict-Transport-Security set to {@code value}.
   */
  public SecurityHeaders strictTransportSecurity(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, value,
        xContentTypeOptions, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The X-Content-Type-Options header value, or {@code null} to clear the header.
   * @return A new instance with the X-Content-Type-Options set to {@code value}.
   */
  public SecurityHeaders xContentTypeOptions(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        value, xFrameOptions, xXSSProtection);
  }

  /**
   * @param value The X-Frame-Options header value, or {@code null} to clear the header.
   * @return A new instance with the X-Frame-Options set to {@code value}.
   */
  public SecurityHeaders xFrameOptions(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, value, xXSSProtection);
  }

  /**
   * @param value The X-XSS-Protection header value, or {@code null} to clear the header.
   * @return A new instance with the X-XSS-Protection set to {@code value}.
   */
  public SecurityHeaders xXSSProtection(String value) {
    return new SecurityHeaders(contentSecurityPolicy, crossOriginEmbedderPolicy, crossOriginOpenerPolicy,
        crossOriginResourcePolicy, permissionsPolicy, referrerPolicy, strictTransportSecurity,
        xContentTypeOptions, xFrameOptions, value);
  }
}
