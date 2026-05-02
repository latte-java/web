/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.middleware;

import module org.lattejava.http;
import module org.lattejava.web;

/**
 * A middleware that emits a strict set of HTTP security headers on every response. Headers are written to the response
 * before the chain is invoked, so error responses (404/405/5xx) also carry them; a downstream handler can override any
 * header by calling {@code setHeader} again.
 * <p>
 * Defaults match the most-secure reasonable value for each header. Every header is individually overridable (or
 * suppressible by passing {@code null}) via the inner {@link Builder}.
 * <p>
 * Caveat: this middleware does not run on {@code 405 Method Not Allowed} responses, which bypass the middleware chain.
 * Those responses carry only {@code Allow} and have no body, so the missing headers are not a meaningful gap.
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

  /**
   * Constructs a SecurityHeaders middleware with all defaults. Equivalent to {@code builder().build()}.
   */
  public SecurityHeaders() {
    this(builder());
  }

  private SecurityHeaders(Builder b) {
    this.contentSecurityPolicy = b.contentSecurityPolicy;
    this.crossOriginEmbedderPolicy = b.crossOriginEmbedderPolicy;
    this.crossOriginOpenerPolicy = b.crossOriginOpenerPolicy;
    this.crossOriginResourcePolicy = b.crossOriginResourcePolicy;
    this.permissionsPolicy = b.permissionsPolicy;
    this.referrerPolicy = b.referrerPolicy;
    this.strictTransportSecurity = b.strictTransportSecurity;
    this.xContentTypeOptions = b.xContentTypeOptions;
    this.xFrameOptions = b.xFrameOptions;
    this.xXSSProtection = b.xXSSProtection;
  }

  /**
   * @return A new Builder pre-populated with all default header values.
   */
  public static Builder builder() {
    return new Builder();
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
   * A builder for {@link SecurityHeaders}. All header fields are pre-populated with their most-secure defaults; call
   * any setter to override, or pass {@code null} to suppress that header entirely.
   */
  public static class Builder {
    private String contentSecurityPolicy = "default-src 'self'; style-src 'self' https://fonts.googleapis.com; " +
        "font-src 'self' https://fonts.gstatic.com; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; " +
        "form-action 'self'; upgrade-insecure-requests";
    private String crossOriginEmbedderPolicy = "require-corp";
    private String crossOriginOpenerPolicy = "same-origin";
    private String crossOriginResourcePolicy = "same-origin";
    private String permissionsPolicy = "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()";
    private String referrerPolicy = "no-referrer";
    private String strictTransportSecurity = "max-age=31536000; includeSubDomains; preload";
    private String xContentTypeOptions = "nosniff";
    private String xFrameOptions = "DENY";
    private String xXSSProtection = "0";

    /**
     * @return A new SecurityHeaders middleware with the current builder values.
     */
    public SecurityHeaders build() {
      return new SecurityHeaders(this);
    }

    public Builder contentSecurityPolicy(String value) {
      this.contentSecurityPolicy = value;
      return this;
    }

    public Builder crossOriginEmbedderPolicy(String value) {
      this.crossOriginEmbedderPolicy = value;
      return this;
    }

    public Builder crossOriginOpenerPolicy(String value) {
      this.crossOriginOpenerPolicy = value;
      return this;
    }

    public Builder crossOriginResourcePolicy(String value) {
      this.crossOriginResourcePolicy = value;
      return this;
    }

    public Builder permissionsPolicy(String value) {
      this.permissionsPolicy = value;
      return this;
    }

    public Builder referrerPolicy(String value) {
      this.referrerPolicy = value;
      return this;
    }

    public Builder strictTransportSecurity(String value) {
      this.strictTransportSecurity = value;
      return this;
    }

    public Builder xContentTypeOptions(String value) {
      this.xContentTypeOptions = value;
      return this;
    }

    public Builder xFrameOptions(String value) {
      this.xFrameOptions = value;
      return this;
    }

    public Builder xXSSProtection(String value) {
      this.xXSSProtection = value;
      return this;
    }
  }
}
