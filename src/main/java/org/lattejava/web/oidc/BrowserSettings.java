/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module org.lattejava.http;

import org.lattejava.web.*;
import org.lattejava.web.oidc.internal.*;

/**
 * Per-browser-profile settings: the session-flow cookie names, paths, redirect targets, and pluggable challenge
 * handlers, plus the token transport. The settings do not model cookie or header details themselves — token transport
 * is entirely the concern of the {@link TokenReader} and {@link TokenWriter}, which default to cookie-based
 * implementations. All fields have sensible defaults; override via {@link #builder()}.
 *
 * @author Brian Pontarelli
 */
public record BrowserSettings(
    TokenReader tokenReader,
    TokenWriter tokenWriter,
    String stateCookieName,
    String returnToCookieName,
    String loginPath,
    String callbackPath,
    String logoutPath,
    String logoutReturnPath,
    String postLoginPage,
    String postLogoutPage,
    String errorPage,
    Handler forbiddenHandler,
    Handler unavailableHandler
) {
  /**
   * @return A new Builder pre-populated with sensible defaults.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link BrowserSettings}. The token transport defaults to a {@link CookieTokenReader} /
   * {@link CookieTokenWriter} pair using the default cookie names ({@code access_token}, {@code refresh_token},
   * {@code id_token}) and a 30-day refresh-cookie max age. To change cookie names, max age, or the transport mechanism
   * entirely, supply a configured {@link #tokenReader(TokenReader)} / {@link #tokenWriter(TokenWriter)}.
   */
  public static class Builder {
    private String callbackPath = "/oidc/return";
    private String errorPage = "/";
    private Handler forbiddenHandler = Defaults::forbidden;
    private String loginPath = "/login";
    private String logoutPath = "/logout";
    private String logoutReturnPath = "/oidc/logout-return";
    private String postLoginPage = "/";
    private String postLogoutPage = "/";
    private String returnToCookieName = "oidc_return_to";
    private String stateCookieName = "oidc_state";
    private TokenReader tokenReader;
    private TokenWriter tokenWriter;
    private Handler unavailableHandler = Defaults::unavailable;

    /**
     * Validates the builder state and returns a new immutable {@link BrowserSettings}.
     *
     * @return The immutable settings.
     */
    public BrowserSettings build() {
      TokenReader reader = tokenReader != null ? tokenReader : new CookieTokenReader();
      TokenWriter writer = tokenWriter != null ? tokenWriter : new CookieTokenWriter();
      return new BrowserSettings(reader, writer, stateCookieName, returnToCookieName, loginPath, callbackPath,
          logoutPath, logoutReturnPath, postLoginPage, postLogoutPage, errorPage, forbiddenHandler, unavailableHandler);
    }

    public Builder callbackPath(String value) {
      this.callbackPath = value;
      return this;
    }

    public Builder errorPage(String value) {
      this.errorPage = value;
      return this;
    }

    public Builder forbiddenHandler(Handler value) {
      this.forbiddenHandler = value;
      return this;
    }

    public Builder loginPath(String value) {
      this.loginPath = value;
      return this;
    }

    public Builder logoutPath(String value) {
      this.logoutPath = value;
      return this;
    }

    public Builder logoutReturnPath(String value) {
      this.logoutReturnPath = value;
      return this;
    }

    public Builder postLoginPage(String value) {
      this.postLoginPage = value;
      return this;
    }

    public Builder postLogoutPage(String value) {
      this.postLogoutPage = value;
      return this;
    }

    public Builder returnToCookieName(String value) {
      this.returnToCookieName = value;
      return this;
    }

    public Builder stateCookieName(String value) {
      this.stateCookieName = value;
      return this;
    }

    public Builder tokenReader(TokenReader value) {
      this.tokenReader = value;
      return this;
    }

    public Builder tokenWriter(TokenWriter value) {
      this.tokenWriter = value;
      return this;
    }

    public Builder unavailableHandler(Handler value) {
      this.unavailableHandler = value;
      return this;
    }
  }

  /**
   * Minimal default page handlers for the redirect challenge.
   */
  static final class Defaults {
    private Defaults() {
    }

    static void forbidden(HTTPRequest req, HTTPResponse res) throws Exception {
      res.setStatus(403);
      res.setContentType("text/html; charset=utf-8");
      res.getWriter().write("<!DOCTYPE html><html lang=\"en\"><head><title>Forbidden</title></head>"
          + "<body><h1>403 Forbidden</h1></body></html>");
    }

    static void unavailable(HTTPRequest req, HTTPResponse res) throws Exception {
      res.setStatus(503);
      res.setContentType("text/html; charset=utf-8");
      res.getWriter().write(
          "<!DOCTYPE html><html lang=\"en\"><head><title>Service Unavailable</title></head>"
              + "<body><h1>503 Service Unavailable</h1></body></html>");
    }
  }
}
