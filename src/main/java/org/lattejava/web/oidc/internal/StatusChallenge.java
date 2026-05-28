/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;

import org.lattejava.web.oidc.AuthChallenge;
import org.lattejava.web.oidc.TokenWriter;

/**
 * Status-code challenge for SPA and API profiles. Clears credentials and returns 401 on an authentication failure
 * (the {@code retryable} flag is ignored — a fetch client cannot follow a meta-refresh); 403 on authorization failure;
 * 503 when the IdP is unreachable.
 *
 * @author Brian Pontarelli
 */
public class StatusChallenge implements AuthChallenge {
  @Override
  public void forbidden(HTTPRequest req, HTTPResponse res) {
    res.setStatus(403);
  }

  @Override
  public void unauthenticated(HTTPRequest req, HTTPResponse res, TokenWriter writer, boolean retryable) {
    writer.clear(req, res);
    res.setStatus(401);
  }

  @Override
  public void unavailable(HTTPRequest req, HTTPResponse res) {
    res.setStatus(503);
  }
}
