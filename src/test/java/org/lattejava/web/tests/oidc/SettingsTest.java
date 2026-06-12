/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module org.lattejava.http;
import module org.lattejava.web;

import org.testng.annotations.*;

import static org.testng.Assert.*;

/**
 * Verifies the settings pattern: {@link APISettings} and {@link BrowserSettings} carry only a {@link TokenReader} and
 * {@link TokenWriter} (plus, for the browser, session config). The settings do not model cookie or header details
 * themselves — those live entirely on the reader/writer. Defaults resolve to the mode's standard transport; a supplied
 * reader/writer supersedes the default regardless of what mechanism it uses.
 *
 * @author Brian Pontarelli
 */
public class SettingsTest {
  private static final TokenWriter NO_OP_WRITER = new TokenWriter() {
    @Override
    public void clear(HTTPRequest req, HTTPResponse res) {
    }

    @Override
    public void write(HTTPRequest req, HTTPResponse res, Tokens tokens) {
    }
  };

  @Test
  public void apiSettings_customSupersedesDefaults() {
    TokenReader reader = req -> new Tokens(null, null, null, null);
    APISettings settings = APISettings.builder().tokenReader(reader).tokenWriter(NO_OP_WRITER).build();
    assertSame(settings.tokenReader(), reader);
    assertSame(settings.tokenWriter(), NO_OP_WRITER);
  }

  @Test
  public void apiSettings_defaultsToHeaderTransport() {
    APISettings settings = APISettings.builder().build();

    // The default reader is the header transport: Authorization: Bearer plus X-Refresh-Token.
    var req = new HTTPRequest();
    req.addHeader("Authorization", "Bearer ACCESS");
    req.addHeader("X-Refresh-Token", "REFRESH");
    Tokens read = settings.tokenReader().read(req);
    assertEquals(read.accessToken(), "ACCESS");
    assertEquals(read.refreshToken(), "REFRESH");

    // The default writer is the header transport: response headers, never cookies.
    var res = new HTTPResponse();
    settings.tokenWriter().write(req, res, new Tokens("NEW_ACCESS", "NEW_REFRESH", null, null));
    assertEquals(res.getHeader("X-Access-Token"), "NEW_ACCESS");
    assertEquals(res.getHeader("X-Refresh-Token"), "NEW_REFRESH");
    assertTrue(res.getCookies().isEmpty());
  }

  @Test
  public void browserSettings_customSupersedesDefaults() {
    // A browser profile may even use a header transport — the settings object does not care; it only holds the pair.
    TokenReader reader = req -> new Tokens(null, null, null, null);
    BrowserSettings settings = BrowserSettings.builder().tokenReader(reader).tokenWriter(NO_OP_WRITER).build();
    assertSame(settings.tokenReader(), reader);
    assertSame(settings.tokenWriter(), NO_OP_WRITER);
  }

  @Test
  public void browserSettings_defaultsToCookieTransport() {
    BrowserSettings settings = BrowserSettings.builder().build();

    // The default reader is the cookie transport over the standard three cookie names.
    var req = new HTTPRequest();
    req.addCookies(new Cookie("access_token", "ACCESS"), new Cookie("refresh_token", "REFRESH"),
        new Cookie("id_token", "ID"));
    Tokens read = settings.tokenReader().read(req);
    assertEquals(read.accessToken(), "ACCESS");
    assertEquals(read.refreshToken(), "REFRESH");
    assertEquals(read.idToken(), "ID");

    // The default writer is the cookie transport: cookies, not response headers.
    var res = new HTTPResponse();
    settings.tokenWriter().write(req, res, new Tokens("NEW_ACCESS", "NEW_REFRESH", "NEW_ID", null));
    assertFalse(res.getCookies().isEmpty());
    assertNull(res.getHeader("X-Access-Token"));
  }

  @Test
  public void browserSettings_sessionDefaults() {
    BrowserSettings settings = BrowserSettings.builder().build();
    assertEquals(settings.loginPath(), "/login");
    assertEquals(settings.callbackPath(), "/oidc/return");
    assertEquals(settings.logoutPath(), "/logout");
    assertEquals(settings.logoutReturnPath(), "/oidc/logout-return");
    assertEquals(settings.stateCookieName(), "oidc_state");
    assertEquals(settings.returnToCookieName(), "oidc_return_to");
    assertNotNull(settings.forbiddenHandler());
    assertNotNull(settings.unavailableHandler());
  }
}
