/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

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
  @Test
  public void apiSettings_customSupersedesDefaults() {
    TokenReader reader = req -> new Tokens(null, null, null, null);
    TokenWriter writer = new HeaderTokenWriter("X-Custom-Access", "X-Custom-Refresh");
    APISettings settings = APISettings.builder().tokenReader(reader).tokenWriter(writer).build();
    assertSame(settings.tokenReader(), reader);
    assertSame(settings.tokenWriter(), writer);
  }

  @Test
  public void apiSettings_defaultsToHeaderTransport() {
    APISettings settings = APISettings.builder().build();
    assertTrue(settings.tokenReader() instanceof HeaderTokenReader);
    assertTrue(settings.tokenWriter() instanceof HeaderTokenWriter);
  }

  @Test
  public void browserSettings_customSupersedesDefaults() {
    // A browser profile may even use a header transport — the settings object does not care; it only holds the pair.
    TokenReader reader = new CookieTokenReader("a", "r", "i");
    TokenWriter writer = new HeaderTokenWriter("X-Custom-Access", "X-Custom-Refresh");
    BrowserSettings settings = BrowserSettings.builder().tokenReader(reader).tokenWriter(writer).build();
    assertSame(settings.tokenReader(), reader);
    assertSame(settings.tokenWriter(), writer);
  }

  @Test
  public void browserSettings_defaultsToCookieTransport() {
    BrowserSettings settings = BrowserSettings.builder().build();
    assertTrue(settings.tokenReader() instanceof CookieTokenReader);
    assertTrue(settings.tokenWriter() instanceof CookieTokenWriter);
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
