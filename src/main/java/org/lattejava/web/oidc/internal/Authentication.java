/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

/**
 * The single authentication orchestrator shared by every profile. Reads tokens via the profile's {@link TokenReader},
 * validates the access token, reactively refreshes on an invalid result (writing the new tokens via the profile's
 * {@link TokenWriter}), binds the decoded {@link JWT} to {@link Tools#CURRENT_JWT}, and routes failures through the
 * profile's {@link AuthChallenge}.
 *
 * @author Brian Pontarelli
 */
public class Authentication implements Middleware {
  private final AuthChallenge challenge;
  private final OIDCConfig config;
  private final TokenReader reader;
  private final TokenValidator validator;
  private final TokenWriter writer;

  public Authentication(OIDCConfig config, TokenReader reader, TokenWriter writer, AuthChallenge challenge,
                        TokenValidator validator) {
    this.challenge = challenge;
    this.config = config;
    this.reader = reader;
    this.validator = validator;
    this.writer = writer;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    Tokens tokens = reader.read(req);
    if (tokens.accessToken() == null) {
      challenge.unauthenticated(req, res, writer, false);
      return;
    }

    TokenValidator.Result result = validator.validate(tokens.accessToken());
    JWT bound;
    if (result instanceof TokenValidator.Result.Valid(JWT jwt)) {
      bound = jwt;
    } else if (result instanceof TokenValidator.Result.NetworkError) {
      challenge.unavailable(req, res);
      return;
    } else {
      if (tokens.refreshToken() == null) {
        challenge.unauthenticated(req, res, writer, true);
        return;
      }

      Tokens refreshed = Tools.refresh(config, tokens.refreshToken());
      if (refreshed == null) {
        challenge.unauthenticated(req, res, writer, false);
        return;
      }

      TokenValidator.Result refreshResult = validator.validate(refreshed.accessToken());
      if (!(refreshResult instanceof TokenValidator.Result.Valid(JWT refreshedJWT))) {
        challenge.unauthenticated(req, res, writer, false);
        return;
      }

      writer.write(req, res, refreshed);
      bound = refreshedJWT;
    }

    ScopedValue.where(Tools.CURRENT_JWT, bound).call(() -> {
      chain.next(req, res);
      return null;
    });
  }
}
