/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * In-process mock IdP used by {@code [Mock]}-tagged OIDC tests. Stands up a {@link Web} instance on a configurable
 * port that serves:
 * <ul>
 *   <li>{@code GET /.well-known/openid-configuration} — minimal OIDC discovery document. The {@code userinfo_endpoint}
 *       can be omitted via {@link #MockIdP(int, boolean)} to drive the {@code validateAccessToken=false + missing
 *       userinfoEndpoint} validation path.</li>
 *   <li>{@code GET /.well-known/jwks.json} — empty JWKS. Tests that need real signature verification should use FA.</li>
 *   <li>{@code POST /oauth2/token} — returns whatever {@link #onTokenEndpoint} was last configured to return; defaults
 *       to {@code 500}.</li>
 *   <li>{@code GET /oauth2/userinfo} — same shape via {@link #onUserinfoEndpoint}.</li>
 * </ul>
 * The mock is single-threaded test scaffolding — not safe to share across parallel tests on the same port.
 */
public class MockIdP implements AutoCloseable {
  private final boolean includeUserinfoEndpoint;
  private final String issuer;
  private final int port;
  private final Web web;
  private volatile MockResponse tokenResponse = new MockResponse(500, null);
  private volatile MockResponse userinfoResponse = new MockResponse(500, null);

  public MockIdP(int port) {
    this(port, true);
  }

  public MockIdP(int port, boolean includeUserinfoEndpoint) {
    this.port = port;
    this.issuer = "http://localhost:" + port;
    this.includeUserinfoEndpoint = includeUserinfoEndpoint;
    this.web = new Web();
    web.get("/.well-known/openid-configuration", this::handleDiscovery);
    web.get("/.well-known/jwks.json", this::handleJWKS);
    web.post("/oauth2/token", (_, res) -> writeMock(res, tokenResponse));
    web.get("/oauth2/userinfo", (_, res) -> writeMock(res, userinfoResponse));
    web.start(port);
  }

  @Override
  public void close() {
    web.close();
  }

  public String issuer() {
    return issuer;
  }

  public void onTokenEndpoint(int status, String body) {
    this.tokenResponse = new MockResponse(status, body);
  }

  public void onUserinfoEndpoint(int status, String body) {
    this.userinfoResponse = new MockResponse(status, body);
  }

  private void handleDiscovery(HTTPRequest req, HTTPResponse res) throws Exception {
    StringBuilder b = new StringBuilder("{");
    b.append("\"issuer\":\"").append(issuer).append("\",");
    b.append("\"authorization_endpoint\":\"").append(issuer).append("/oauth2/authorize\",");
    b.append("\"token_endpoint\":\"").append(issuer).append("/oauth2/token\",");
    if (includeUserinfoEndpoint) {
      b.append("\"userinfo_endpoint\":\"").append(issuer).append("/oauth2/userinfo\",");
    }
    b.append("\"jwks_uri\":\"").append(issuer).append("/.well-known/jwks.json\"");
    b.append("}");
    res.setStatus(200);
    res.setContentType("application/json");
    res.getWriter().write(b.toString());
  }

  private void handleJWKS(HTTPRequest req, HTTPResponse res) throws Exception {
    res.setStatus(200);
    res.setContentType("application/json");
    res.getWriter().write("{\"keys\":[]}");
  }

  private void writeMock(HTTPResponse res, MockResponse mock) throws Exception {
    res.setStatus(mock.status);
    if (mock.body != null) {
      res.setContentType("application/json");
      res.getWriter().write(mock.body);
    }
  }

  private record MockResponse(int status, String body) {
  }
}
