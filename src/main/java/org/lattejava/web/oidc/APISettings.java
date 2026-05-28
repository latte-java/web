/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module org.lattejava.web;

/**
 * Per-API-profile settings: token transport defaults for header-based clients. All fields have sensible defaults;
 * override via {@link #builder()}.
 *
 * @author Brian Pontarelli
 */
public record APISettings(TokenReader tokenReader, TokenWriter tokenWriter) {
  public static final String AUTHORIZATION = "Authorization";
  public static final String X_ACCESS_TOKEN = "X-Access-Token";
  public static final String X_REFRESH_TOKEN = "X-Refresh-Token";

  /**
   * @return A new Builder pre-populated with sensible defaults.
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder for {@link APISettings}. Defaults to reading the access token from {@code Authorization: Bearer} and the
   * refresh token from {@code X-Refresh-Token}, and writing refreshed tokens to {@code X-Access-Token} and
   * {@code X-Refresh-Token}.
   */
  public static class Builder {
    private TokenReader tokenReader;
    private TokenWriter tokenWriter;

    /**
     * Validates the builder state and returns a new immutable {@link APISettings}.
     *
     * @return The immutable settings.
     */
    public APISettings build() {
      TokenReader reader = tokenReader != null ? tokenReader : new HeaderTokenReader(AUTHORIZATION, X_REFRESH_TOKEN);
      TokenWriter writer = tokenWriter != null ? tokenWriter : new HeaderTokenWriter(X_ACCESS_TOKEN, X_REFRESH_TOKEN);
      return new APISettings(reader, writer);
    }

    public Builder tokenReader(TokenReader value) {
      this.tokenReader = value;
      return this;
    }

    public Builder tokenWriter(TokenWriter value) {
      this.tokenWriter = value;
      return this;
    }
  }
}
