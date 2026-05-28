/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Authorization middleware. Reads the bound JWT and asks an {@link Authorizer}; denial routes through the profile's
 * {@link AuthChallenge#forbidden}. A missing bound JWT means authentication did not run upstream — fail closed via
 * {@link AuthChallenge#unauthenticated}.
 *
 * @author Brian Pontarelli
 */
public class Authorization implements Middleware {
  private final Authorizer authorizer;
  private final AuthChallenge challenge;
  private final TokenWriter writer;

  public Authorization(Authorizer authorizer, AuthChallenge challenge, TokenWriter writer) {
    this.authorizer = authorizer;
    this.challenge = challenge;
    this.writer = writer;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    if (!Tools.CURRENT_JWT.isBound()) {
      challenge.unauthenticated(req, res, writer, false);
      return;
    }

    if (authorizer.authorize(req, Tools.CURRENT_JWT.get())) {
      chain.next(req, res);
      return;
    }

    challenge.forbidden(req, res);
  }
}
