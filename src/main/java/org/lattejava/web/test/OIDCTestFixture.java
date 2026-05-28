/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.test;

import module com.fasterxml.jackson.databind;
import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;

import org.lattejava.web.oidc.BrowserSettings;
import org.lattejava.web.oidc.internal.Tools;

/**
 * Test fixture for driving Open ID Connect login and logout against a real provider so that tests can run against a
 * running {@link Web} application with an authenticated user (or not).
 * <p>
 * The fixture walks the OAuth2 authorization-code flow against {@link OIDCConfig#authorizeEndpoint()} as a browser
 * would, exchanges the resulting code at {@link OIDCConfig#tokenEndpoint()}, and stores the issued tokens in the
 * {@link WebTest}'s cookie jar under the default cookie names ({@code access_token}, {@code refresh_token},
 * {@code id_token}). Subsequent requests through the same {@link WebTest} are authenticated as the logged-in user.
 * {@link #logout()} removes those cookies from the jar.
 * <p>
 * One fixture represents one OAuth client: {@link OIDCConfig#clientId()} identifies the client on every request, and
 * the token-exchange shape is dictated by {@link OIDCConfig#publicClient()} — confidential clients
 * ({@code publicClient=false}, the default) authenticate via HTTP Basic with {@link OIDCConfig#clientSecret()}; public
 * clients ({@code publicClient=true} — CLI, native, desktop, console, SPA) send {@code client_id} in the form body
 * and rely on PKCE. PKCE is used in both modes. Tests that need to drive logins for multiple clients should construct
 * one {@code OIDCConfig} + {@code OIDCTestFixture} per client.
 * <p>
 * The redirect URI is supplied per {@link #login} call. The two-arg overload defaults to
 * {@code http://localhost:<webTest.port><browser.callbackPath()>} for the SSR web-app case; the three-arg overload
 * takes an explicit URI so the fixture can drive flows whose registered redirect is a loopback
 * ({@code http://127.0.0.1:PORT/...}, RFC 8252) or a custom scheme ({@code myapp://callback}).
 * <p>
 * The provider is assumed to already be running and configured to accept the client identified by
 * {@link OIDCConfig#clientId()}.
 *
 * @author Brian Pontarelli
 */
public class OIDCTestFixture {
  private static final String ACCESS_COOKIE_NAME = "access_token";
  private static final String ID_COOKIE_NAME = "id_token";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String REFRESH_COOKIE_NAME = "refresh_token";
  private final BrowserSettings browser;
  private final OIDCConfig config;
  private final WebTest webTest;

  /**
   * Creates a new fixture with default {@link BrowserSettings}.
   *
   * @param webTest The test client whose cookie jar will hold auth cookies after a successful {@link #login}.
   * @param config  The OIDC configuration for the client under test.
   */
  public OIDCTestFixture(WebTest webTest, OIDCConfig config) {
    this(webTest, config, BrowserSettings.builder().build());
  }

  /**
   * Creates a new fixture bound to the given test client, OIDC configuration, and browser settings.
   *
   * @param webTest  The test client whose cookie jar will hold auth cookies after a successful {@link #login}.
   * @param config   The OIDC configuration for the client under test.
   * @param browser  The browser settings that determine cookie names and paths.
   */
  public OIDCTestFixture(WebTest webTest, OIDCConfig config, BrowserSettings browser) {
    this.webTest = webTest;
    this.config = config;
    this.browser = browser;
  }

  private static String formEncode(Map<String, String> form) {
    return form.entrySet()
               .stream()
               .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                   + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
               .reduce((a, b) -> a + "&" + b)
               .orElse("");
  }

  private static Map<String, String> parseQuery(String query) {
    Map<String, String> out = new LinkedHashMap<>();
    if (query == null || query.isEmpty()) {
      return out;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String k = eq >= 0 ? pair.substring(0, eq) : pair;
      String v = eq >= 0 ? pair.substring(eq + 1) : "";
      out.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
          URLDecoder.decode(v, StandardCharsets.UTF_8));
    }
    return out;
  }

  private static Result walkRedirects(HttpClient client, URI current, HttpResponse<String> res, String redirectURI) throws Exception {
    String location = null;
    while (res.statusCode() / 100 == 3) {
      location = res.headers().firstValue("Location").orElse(null);
      if (location == null) {
        throw new IllegalStateException("Redirect with no Location at [" + current + "] status=[" + res.statusCode() + "]");
      }

      URI next = current.resolve(location);
      if (next.toString().startsWith(redirectURI)) {
        String code = parseQuery(next.getRawQuery()).get("code");
        if (code == null) {
          throw new IllegalStateException("OIDC authorize landed at [" + next + "] without a code");
        }
        return new Result(code, null, 0);
      }

      current = next;
      res = client.send(HttpRequest.newBuilder(current).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    return new Result(null, location, res.statusCode());
  }

  /**
   * SSR convenience: walks the OAuth2 authorization-code flow with the redirect URI defaulted to
   * {@code http://localhost:<webTest.port><browser.callbackPath()>}. Equivalent to
   * {@link #login(String, String, String)} with that URI.
   *
   * @param email    The user's email address.
   * @param password The user's password.
   * @return The access, refresh, and id tokens plus the expiry (any may be null if the IdP omits them).
   * @throws Exception If the OAuth flow or token exchange fails.
   */
  public Tokens login(String email, String password) throws Exception {
    return login(email, password, "http://localhost:" + webTest.port + browser.callbackPath());
  }

  /**
   * Walks the OAuth2 authorization-code flow against the configured IdP for the given user with an explicit redirect
   * URI, exchanges the resulting code for tokens, stores those tokens as cookies in the {@link WebTest} cookie jar,
   * and returns them. After this call returns, subsequent requests through the test client are authenticated as the
   * user; callers that need the raw tokens (e.g. for explicit {@code Cookie} headers or {@code Authorization: Bearer}
   * usage from a CLI/native simulation) can use the returned {@link Tokens}.
   *
   * @param email       The user's email address.
   * @param password    The user's password.
   * @param redirectURI The redirect URI registered for {@link OIDCConfig#clientId()}. The fixture stops walking
   *                    redirects as soon as a {@code Location} header points at this URI and parses the code from the
   *                    query string; it is also sent verbatim as {@code redirect_uri} on the token exchange.
   * @return The access, refresh, and id tokens plus the expiry (any may be null if the IdP omits them).
   * @throws Exception If the OAuth flow or token exchange fails.
   */
  public Tokens login(String email, String password, String redirectURI) throws Exception {
    AuthorizationCode auth = fetchAuthorizationCode(email, password, redirectURI);

    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", auth.code());
    form.put("redirect_uri", redirectURI);
    form.put("code_verifier", auth.state());

    Tools.TokenEndpointResponse res = Tools.postToken(config, form);
    if (res.statusCode() != 200) {
      throw new IllegalStateException("OIDC token exchange failed [" + res.statusCode() + "]: [" + res.body() + "]");
    }

    JsonNode json = MAPPER.readTree(res.body());
    JsonNode access = json.get("access_token");
    if (access == null || access.isNull()) {
      throw new IllegalStateException("OIDC token response missing [access_token]: [" + res.body() + "]");
    }

    String accessToken = access.asText();
    webTest.cookies.add(new Cookie(ACCESS_COOKIE_NAME, accessToken));

    JsonNode id = json.get("id_token");
    String idToken = id != null && !id.isNull() ? id.asText() : null;
    if (idToken != null) {
      webTest.cookies.add(new Cookie(ID_COOKIE_NAME, idToken));
    }

    JsonNode refresh = json.get("refresh_token");
    String refreshToken = refresh != null && !refresh.isNull() ? refresh.asText() : null;
    if (refreshToken != null) {
      webTest.cookies.add(new Cookie(REFRESH_COOKIE_NAME, refreshToken));
    }

    JsonNode expires = json.get("expires_in");
    Long expiresIn = expires != null && !expires.isNull() ? expires.asLong() : null;

    return new Tokens(accessToken, refreshToken, idToken, expiresIn);
  }

  /**
   * Removes all OIDC auth cookies (access, refresh, id) from the {@link WebTest} cookie jar so subsequent requests
   * through the test client are unauthenticated.
   */
  public void logout() {
    webTest.cookies.cookies.remove(ACCESS_COOKIE_NAME);
    webTest.cookies.cookies.remove(ID_COOKIE_NAME);
    webTest.cookies.cookies.remove(REFRESH_COOKIE_NAME);
  }

  /**
   * Drives the IdP's hosted-login OAuth2 authorize flow as a browser would, then returns the resulting authorization
   * code along with the {@code state} value (also the PKCE code-verifier under Latte's single-value scheme). Exposed
   * to subclasses for fixtures that need direct access to the issued code without performing the token exchange.
   *
   * @param email       The user's email.
   * @param password    The user's password.
   * @param redirectURI The redirect URI registered for {@link OIDCConfig#clientId()}; the walker stops as soon as a
   *                    {@code Location} header points at this URI and parses the code from the query string.
   * @return The issued authorization code and the state used to obtain it.
   * @throws Exception If the authorize chain does not terminate at {@code redirectURI}.
   */
  protected AuthorizationCode fetchAuthorizationCode(String email, String password, String redirectURI) throws Exception {
    byte[] stateBytes = new byte[22];
    new SecureRandom().nextBytes(stateBytes);
    String state = HexFormat.of().formatHex(stateBytes);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

    Map<String, String> oauthParams = new LinkedHashMap<>();
    oauthParams.put("client_id", config.clientId());
    oauthParams.put("redirect_uri", redirectURI);
    oauthParams.put("response_type", "code");
    oauthParams.put("scope", String.join(" ", config.scopes()));
    oauthParams.put("state", state);
    oauthParams.put("code_challenge", challenge);
    oauthParams.put("code_challenge_method", "S256");

    HttpClient client = HttpClient.newBuilder()
                                  .cookieHandler(new CookieManager())
                                  .followRedirects(HttpClient.Redirect.NEVER)
                                  .build();

    URI authorize = config.authorizeEndpoint();
    URI current = URI.create(authorize + "?" + formEncode(oauthParams));
    HttpResponse<String> res = client.send(HttpRequest.newBuilder(current).GET().build(), HttpResponse.BodyHandlers.ofString());

    Result result = walkRedirects(client, current, res, redirectURI);
    if (result.code() != null) {
      return new AuthorizationCode(result.code(), state);
    }

    Map<String, String> postForm = new LinkedHashMap<>(oauthParams);
    postForm.put("loginId", email);
    postForm.put("password", password);
    current = authorize;
    res = client.send(HttpRequest.newBuilder(current)
                                 .header("Content-Type", "application/x-www-form-urlencoded")
                                 .POST(HttpRequest.BodyPublishers.ofString(formEncode(postForm)))
                                 .build(),
        HttpResponse.BodyHandlers.ofString());

    result = walkRedirects(client, current, res, redirectURI);
    if (result.code() != null) {
      return new AuthorizationCode(result.code(), state);
    }

    throw new IllegalStateException("OIDC authorize chain did not land at [" + redirectURI + "]. The last redirect was [" + result.lastLocation() + "] with status [" + result.lastStatusCode() + "]");
  }

  /**
   * Output of the authorize step. {@code state} doubles as the PKCE code-verifier under Latte's single-value scheme.
   */
  public record AuthorizationCode(String code, String state) {
  }

  public record Result(String code, String lastLocation, int lastStatusCode) {
  }
}
