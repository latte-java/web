/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.test;

import module com.fasterxml.jackson.databind;
import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Test fixture for driving Open ID Connect login and logout against a real provider so that tests can run against a
 * running {@link Web} application with an authenticated user (or not).
 * <p>
 * The fixture walks the OAuth2 authorization-code flow against {@link OIDCConfig#authorizeEndpoint()} as a browser
 * would, exchanges the resulting code at {@link OIDCConfig#tokenEndpoint()} using HTTP Basic with
 * {@link OIDCConfig#clientSecret()}, and stores the issued tokens in the {@link WebTest}'s cookie jar under the names
 * configured on the {@link OIDCConfig} ({@code accessTokenCookieName}, {@code idTokenCookieName},
 * {@code refreshTokenCookieName}). Subsequent requests through the same {@link WebTest} are authenticated as the
 * logged-in user. {@link #logout()} removes those cookies from the jar.
 * <p>
 * The provider is assumed to already be running and configured to accept the application identified by
 * {@link OIDCConfig#clientId()}. The OAuth client must be registered with a redirect URI of
 * {@code http://localhost:<webTest.port><config.callbackPath()>}.
 *
 * @author Brian Pontarelli
 */
public class OIDCTestFixture {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final OIDCConfig config;
  private final WebTest webTest;

  /**
   * Creates a new fixture bound to the given test client and OIDC configuration.
   *
   * @param webTest The test client whose cookie jar will hold auth cookies after a successful {@link #login}.
   * @param config  The OIDC configuration matching the application under test.
   */
  public OIDCTestFixture(WebTest webTest, OIDCConfig config) {
    this.webTest = webTest;
    this.config = config;
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

  private static String walkRedirects(HttpClient client, URI current, HttpResponse<String> res, String redirectURI) throws Exception {
    while (res.statusCode() / 100 == 3) {
      String location = res.headers().firstValue("Location").orElse(null);
      if (location == null) {
        throw new IllegalStateException("Redirect with no Location at [" + current + "] status=[" + res.statusCode() + "]");
      }

      URI next = current.resolve(location);
      if (next.toString().startsWith(redirectURI)) {
        String code = parseQuery(next.getRawQuery()).get("code");
        if (code == null) {
          throw new IllegalStateException("OIDC authorize landed at [" + next + "] without a code");
        }
        return code;
      }

      current = next;
      res = client.send(HttpRequest.newBuilder(current).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    return null;
  }

  /**
   * Returns the client secret to send in the token-exchange Basic auth header for the given {@code applicationId}.
   * The default returns {@link OIDCConfig#clientSecret()}, which is correct when the fixture's config is bound to
   * the same OAuth client as {@code applicationId}. Subclasses that support multiple applications (e.g.
   * {@code FusionAuthFixture}, which knows several kickstart-provisioned apps) override this to look the secret up
   * by application id.
   *
   * @param applicationId The OAuth client UUID being logged in to.
   * @return The corresponding client secret.
   */
  protected String clientSecretFor(String applicationId) {
    return config.clientSecret();
  }

  /**
   * Walks the OAuth2 authorization-code flow against the configured IdP for the given user, exchanges the resulting
   * code for tokens, stores those tokens as cookies in the {@link WebTest} cookie jar, and returns them. After this
   * call returns, subsequent requests through the test client are authenticated as the user; callers that need the
   * raw tokens (e.g. for explicit {@code Cookie} headers) can use the returned {@link Tokens}.
   *
   * @param email         The user's email address.
   * @param password      The user's password.
   * @param applicationId The OAuth client (application) UUID. Sent as the {@code client_id} on both the authorize
   *                      request and the token-exchange request. The corresponding secret is resolved via
   *                      {@link #clientSecretFor(String)}.
   * @return The access, refresh, and id tokens (any may be null if the IdP omits them).
   * @throws Exception If the OAuth flow or token exchange fails.
   */
  public Tokens login(String email, String password, String applicationId) throws Exception {
    String redirectURI = "http://localhost:" + webTest.port + config.callbackPath();
    AuthorizationCode auth = fetchAuthorizationCode(email, password, applicationId, redirectURI);

    String body = formEncode(Map.of(
        "grant_type", "authorization_code",
        "code", auth.code(),
        "redirect_uri", redirectURI,
        "code_verifier", auth.state()
    ));
    String basic = "Basic " + Base64.getEncoder().encodeToString(
        (applicationId + ":" + clientSecretFor(applicationId)).getBytes(StandardCharsets.UTF_8));
    HttpRequest req = HttpRequest.newBuilder(config.tokenEndpoint())
                                 .header("Authorization", basic)
                                 .header("Content-Type", "application/x-www-form-urlencoded")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .build();

    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) {
        throw new IllegalStateException("OIDC token exchange failed [" + res.statusCode() + "]: [" + res.body() + "]");
      }

      JsonNode json = MAPPER.readTree(res.body());
      JsonNode access = json.get("access_token");
      if (access == null || access.isNull()) {
        throw new IllegalStateException("OIDC token response missing [access_token]: [" + res.body() + "]");
      }

      String accessToken = access.asText();
      webTest.cookies.add(new Cookie(config.accessTokenCookieName(), accessToken));

      JsonNode id = json.get("id_token");
      String idToken = id != null && !id.isNull() ? id.asText() : null;
      if (idToken != null) {
        webTest.cookies.add(new Cookie(config.idTokenCookieName(), idToken));
      }

      JsonNode refresh = json.get("refresh_token");
      String refreshToken = refresh != null && !refresh.isNull() ? refresh.asText() : null;
      if (refreshToken != null) {
        webTest.cookies.add(new Cookie(config.refreshTokenCookieName(), refreshToken));
      }

      return new Tokens(accessToken, refreshToken, idToken);
    }
  }

  /**
   * Removes all OIDC auth cookies (access, refresh, id) from the {@link WebTest} cookie jar so subsequent requests
   * through the test client are unauthenticated.
   */
  public void logout() {
    webTest.cookies.cookies.remove(config.accessTokenCookieName());
    webTest.cookies.cookies.remove(config.idTokenCookieName());
    webTest.cookies.cookies.remove(config.refreshTokenCookieName());
  }

  /**
   * Drives the IdP's hosted-login OAuth2 authorize flow as a browser would, then returns the resulting authorization
   * code along with the {@code state} value (also the PKCE code-verifier under Latte's single-value scheme). Exposed
   * to subclasses for fixtures that need direct access to the issued code without performing the token exchange.
   *
   * @param email         The user's email.
   * @param password      The user's password.
   * @param applicationId The OAuth client (application) UUID.
   * @param redirectURI   The redirect URI registered on the application; the walker stops as soon as a
   *                      {@code Location} header points at this URI and parses the code from the query string.
   * @return The issued authorization code and the state used to obtain it.
   * @throws Exception If the authorize chain does not terminate at {@code redirectURI}.
   */
  protected AuthorizationCode fetchAuthorizationCode(String email, String password, String applicationId, String redirectURI) throws Exception {
    byte[] stateBytes = new byte[22];
    new SecureRandom().nextBytes(stateBytes);
    String state = HexFormat.of().formatHex(stateBytes);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

    Map<String, String> oauthParams = new LinkedHashMap<>();
    oauthParams.put("client_id", applicationId);
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

    String codeFromGet = walkRedirects(client, current, res, redirectURI);
    if (codeFromGet != null) {
      return new AuthorizationCode(codeFromGet, state);
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

    String codeFromPost = walkRedirects(client, current, res, redirectURI);
    if (codeFromPost != null) {
      return new AuthorizationCode(codeFromPost, state);
    }

    throw new IllegalStateException("OIDC authorize chain did not land at [" + redirectURI + "]");
  }

  /**
   * Output of the authorize step. {@code state} doubles as the PKCE code-verifier under Latte's single-value scheme.
   */
  public record AuthorizationCode(String code, String state) {
  }

  /**
   * The bundle the IdP returned from the token-exchange step. {@code refreshToken} and {@code idToken} are nullable;
   * {@code accessToken} is always present (or {@link #login} would have thrown).
   */
  public record Tokens(String accessToken, String refreshToken, String idToken) {
  }
}
