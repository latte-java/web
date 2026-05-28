/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;
import module org.testng;

import org.lattejava.web.tests.*;

import static org.lattejava.web.tests.oidc.FusionAuthFixture.*;
import static org.testng.Assert.*;

/**
 * Exercises the per-branch behavior of the {@link Authentication} orchestrator using the MockIdP to avoid requiring
 * FusionAuth for the negative/error paths.
 *
 * @author Brian Pontarelli
 */
public class AuthenticationTest extends BaseWebTest {
  private static final int MOCK_PORT = 9098;

  @Test
  public void api_cookieTransport_readsTokenFromCookie() throws Exception {
    // A real FA access token, read from a cookie instead of the Authorization header.
    // Demonstrates that swapping the TokenReader in APISettings supersedes the default header transport.
    String accessToken = new FusionAuthFixture().login(USER_EMAIL, DEFAULT_PASSWORD, STANDARD_APP_ID).accessToken();
    var cookieReader = new CookieTokenReader("access_token", "refresh_token", "id_token");
    var cookieWriter = new CookieTokenWriter("access_token", "refresh_token", "id_token", Duration.ofDays(30));
    var apiSettings = APISettings.builder()
                                 .tokenReader(cookieReader)
                                 .tokenWriter(cookieWriter)
                                 .build();
    OIDC<String> cookieApi = OIDC.api(
        OIDCConfig.builder()
                  .issuer(STANDARD_ISSUER)
                  .clientId(STANDARD_APP_ID)
                  .clientSecret(STANDARD_APP_SECRET)
                  .build(),
        apiSettings,
        JWT::subject);

    try (var web = new Web()) {
      web.prefix("/api", p -> {
        p.install(cookieApi.authenticated());
        p.get("/me", (_, res) -> {
          res.setStatus(200);
          res.getWriter().write(cookieApi.user());
        });
      });
      web.start(PORT);

      HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/me"))
                                   .header("Cookie", "access_token=" + accessToken)
                                   .GET()
                                   .build();
      try (var client = HttpClient.newHttpClient()) {
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(res.statusCode(), 200);
        assertEquals(res.body(), STANDARD_USER_ID);
      }
    }
  }

  @Test
  public void api_missingToken_returns401NoSetCookie() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      OIDCConfig config = OIDCConfig.builder()
                                    .issuer(mock.issuer())
                                    .clientId("c")
                                    .clientSecret("s")
                                    .build();
      var api = APISettings.builder().build();
      var validator = new TokenValidator(config, null);
      var challenge = new StatusChallenge();
      var mw = new Authentication(config, api.tokenReader(), api.tokenWriter(), challenge, validator);

      try (var web = new Web()) {
        web.prefix("/api", p -> {
          p.install(mw);
          p.get("/data", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpResponse<String> res = get("/api/data", null);
        assertEquals(res.statusCode(), 401);
        assertTrue(res.headers().allValues("Set-Cookie").isEmpty(), "API must not set cookies on 401");
      }
    }
  }

  @Test
  public void api_networkError_returns503NoSetCookie() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(500, "{\"error\":\"server_error\"}");
      // validateAccessToken=false so the non-JWT token reaches introspect; mock returns 5xx → NetworkError → 503.
      OIDC<?> mockApi = OIDC.api(OIDCConfig.builder()
                                           .issuer(mock.issuer())
                                           .clientId("c")
                                           .clientSecret("s")
                                           .validateAccessToken(false)
                                           .build());

      try (var web = new Web()) {
        web.prefix("/api", p -> {
          p.install(mockApi.authenticated());
          p.get("/data", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/data"))
                                     .header("Authorization", "Bearer tok")
                                     .GET()
                                     .build();
        HttpResponse<String> res;
        try (var client = HttpClient.newHttpClient()) {
          res = client.send(req, HttpResponse.BodyHandlers.ofString());
        }
        assertEquals(res.statusCode(), 503);
        assertTrue(res.headers().allValues("Set-Cookie").isEmpty(), "API must not set cookies on 503");
      }
    }
  }

  @Test
  public void spa_missingToken_returns401() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      OIDCConfig config = OIDCConfig.builder()
                                    .issuer(mock.issuer())
                                    .clientId("c")
                                    .clientSecret("s")
                                    .build();
      var browser = BrowserSettings.builder().build();
      var validator = new TokenValidator(config, null);
      var challenge = new StatusChallenge();
      var mw = new Authentication(config, browser.tokenReader(), browser.tokenWriter(), challenge, validator);

      try (var web = new Web()) {
        web.prefix("/app", p -> {
          p.install(mw);
          p.get("/data", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpResponse<String> res = get("/app/data", null);
        assertEquals(res.statusCode(), 401);
      }
    }
  }

  @Test
  public void spa_networkError_returns503() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(500, "{\"error\":\"server_error\"}");
      // validateAccessToken=false so the non-JWT token reaches introspect; mock returns 5xx → NetworkError → 503.
      OIDC<?> mockSpa = OIDC.spa(OIDCConfig.builder()
                                           .issuer(mock.issuer())
                                           .clientId("c")
                                           .clientSecret("s")
                                           .validateAccessToken(false)
                                           .build());

      try (var web = new Web()) {
        web.prefix("/app", p -> {
          p.install(mockSpa.authenticated());
          p.get("/data", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/app/data"))
                                     .header("Cookie", "access_token=tok")
                                     .GET()
                                     .build();
        HttpResponse<String> res;
        try (var client = HttpClient.newHttpClient()) {
          res = client.send(req, HttpResponse.BodyHandlers.ofString());
        }
        assertEquals(res.statusCode(), 503);
      }
    }
  }

  @Test
  public void ssr_missingToken_redirectsToLogin() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      OIDCConfig config = OIDCConfig.builder()
                                    .issuer(mock.issuer())
                                    .clientId("c")
                                    .clientSecret("s")
                                    .build();
      var browser = BrowserSettings.builder().build();
      var validator = new TokenValidator(config, null);
      var challenge = new RedirectChallenge(browser);
      var mw = new Authentication(config, browser.tokenReader(), browser.tokenWriter(), challenge, validator);

      try (var web = new Web()) {
        web.prefix("/secured", p -> {
          p.install(mw);
          p.get("/page", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpResponse<String> res = get("/secured/page", null);
        assertEquals(res.statusCode(), 302);
        assertEquals(res.headers().firstValue("Location").orElse(null), "/login");
        Cookie returnTo = getCookie(res, "oidc_return_to");
        assertNotNull(returnTo, "Expected oidc_return_to cookie to be set on redirect");
      }
    }
  }

  @Test
  public void ssr_networkError_returns503HTML() throws Exception {
    try (MockIdP mock = new MockIdP(MOCK_PORT)) {
      mock.onIntrospectEndpoint(500, "{\"error\":\"server_error\"}");
      // validateAccessToken=false so the non-JWT token reaches introspect; mock returns 5xx → NetworkError → 503 HTML.
      OIDC<?> mockSsr = OIDC.ssr(OIDCConfig.builder()
                                           .issuer(mock.issuer())
                                           .clientId("c")
                                           .clientSecret("s")
                                           .validateAccessToken(false)
                                           .build());

      try (var web = new Web()) {
        web.prefix("/secured", p -> {
          p.install(mockSsr.authenticated());
          p.get("/page", (_, res) -> res.setStatus(200));
        });
        web.start(PORT);

        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/secured/page"))
                                     .header("Cookie", "access_token=tok")
                                     .GET()
                                     .build();
        HttpResponse<String> res;
        try (var client = HttpClient.newHttpClient()) {
          res = client.send(req, HttpResponse.BodyHandlers.ofString());
        }
        assertEquals(res.statusCode(), 503);
        String ct = res.headers().firstValue("Content-Type").orElse("");
        assertTrue(ct.startsWith("text/html"), "Expected HTML content-type for SSR unavailable, got [" + ct + "]");
        assertTrue(res.body().contains("503"), "Expected 503 in HTML body");
      }
    }
  }
}
