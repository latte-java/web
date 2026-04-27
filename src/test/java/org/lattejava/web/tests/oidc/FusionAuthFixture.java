/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.oidc;

import module com.fasterxml.jackson.databind;
import module java.base;
import module java.net.http;

import org.lattejava.web.tests.BaseWebTest;

/**
 * Constants and helpers for tests that drive the kickstart-provisioned FusionAuth instance at {@code localhost:9011}.
 * <p>
 * Mirrors {@code src/test/docker/kickstart/kickstart.json}.
 */
public final class FusionAuthFixture {
  public static final String ADMIN_EMAIL = "admin@example.com";
  public static final String API_KEY = "bf69486b-4733-4470-a592-f1bfce7af580";
  public static final String DEFAULT_PASSWORD = "password";
  public static final String FA_BASE_URL = "http://localhost:9011";
  public static final String STANDARD_APP_ID = "10000000-0000-0000-0000-000000000002";
  public static final String STANDARD_APP_SECRET = "standard-app-secret-1234567890abcdef";
  public static final String STANDARD_TENANT_ID = "10000000-0000-0000-0000-000000000001";
  public static final String STANDARD_USER_ID = "10000000-0000-0000-0000-000000000010";
  public static final String STANDARD_ADMIN_ID = "10000000-0000-0000-0000-000000000011";
  public static final String USER_EMAIL = "user@example.com";
  public static final String STANDARD_ISSUER = FA_BASE_URL + "/" + STANDARD_TENANT_ID;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private FusionAuthFixture() {
  }

  /**
   * Drives FusionAuth's hosted-login OAuth2 authorize flow as a browser would, then returns the resulting authorization
   * code along with the {@code state} value (also the PKCE code-verifier under Latte's single-value scheme).
   * <p>
   * Generates a 22-byte random state, computes {@code code_challenge = base64url(sha256(state))}, then steps through:
   * <ol>
   *   <li>{@code GET /oauth2/authorize?...} — manually walking redirects until FusionAuth lands on the login form.</li>
   *   <li>{@code POST /oauth2/authorize} with {@code loginId}/{@code password}. FusionAuth treats the {@code state}
   *       parameter as its CSRF token. The POST response is followed manually through any intermediate hops (e.g.
   *       {@code /oauth2/consent}) until the chain redirects back to {@code redirectURI}.</li>
   * </ol>
   * The walker stops as soon as a {@code Location} header points at {@code redirectURI}; the helper does not actually
   * issue a request to the test app — it parses the code straight out of the redirect URI.
   *
   * @param email         The user's email.
   * @param password      The user's password.
   * @param applicationId The OAuth client (application) UUID.
   * @param redirectURI   The redirect URI registered on the application.
   * @return The issued authorization code and the state used to obtain it.
   */
  public static AuthorizationCode fetchAuthorizationCode(String email, String password, String applicationId, String redirectURI) throws Exception {
    byte[] stateBytes = new byte[22];
    new SecureRandom().nextBytes(stateBytes);
    String state = HexFormat.of().formatHex(stateBytes);
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8));
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

    Map<String, String> oauthParams = Map.of(
        "client_id", applicationId,
        "redirect_uri", redirectURI,
        "response_type", "code",
        "scope", "openid profile email offline_access",
        "state", state,
        "code_challenge", challenge,
        "code_challenge_method", "S256"
    );

    HttpClient client = HttpClient.newBuilder()
                                  .cookieHandler(new CookieManager())
                                  .followRedirects(HttpClient.Redirect.NEVER)
                                  .build();

    URI current = URI.create(FA_BASE_URL + "/oauth2/authorize?" + formEncode(oauthParams));
    HttpResponse<String> res = client.send(HttpRequest.newBuilder(current).GET().build(), HttpResponse.BodyHandlers.ofString());

    String codeFromGet = walkRedirects(client, current, res, redirectURI);
    if (codeFromGet != null) {
      return new AuthorizationCode(codeFromGet, state);
    }

    Map<String, String> postForm = new LinkedHashMap<>(oauthParams);
    postForm.put("loginId", email);
    postForm.put("password", password);
    current = URI.create(FA_BASE_URL + "/oauth2/authorize");
    res = client.send(HttpRequest.newBuilder(current)
                                 .header("Content-Type", "application/x-www-form-urlencoded")
                                 .POST(HttpRequest.BodyPublishers.ofString(formEncode(postForm)))
                                 .build(),
        HttpResponse.BodyHandlers.ofString());

    String codeFromPost = walkRedirects(client, current, res, redirectURI);
    if (codeFromPost != null) {
      return new AuthorizationCode(codeFromPost, state);
    }

    throw new IllegalStateException("FusionAuth authorize chain did not land at [" + redirectURI + "]");
  }

  /**
   * Manually walks a redirect chain on {@code client}, hopping GETs along {@code Location} headers. If a {@code
   * Location} ever points at {@code redirectURI}, returns the {@code code} query parameter from that target without
   * actually issuing the request. Otherwise returns {@code null} when the chain ends at a non-redirect response.
   */
  private static String walkRedirects(HttpClient client, URI current, HttpResponse<String> res, String redirectURI) throws Exception {
    while (res.statusCode() / 100 == 3) {
      String location = res.headers().firstValue("Location").orElse(null);
      if (location == null) {
        throw new IllegalStateException("Redirect with no Location at [" + current + "] status=[" + res.statusCode() + "]");
      }

      URI next = current.resolve(location);
      if (next.toString().startsWith(redirectURI)) {
        String code = BaseWebTest.parseQuery(next.getRawQuery()).get("code");
        if (code == null) {
          throw new IllegalStateException("FusionAuth landed at [" + next + "] without a code");
        }
        return code;
      }

      current = next;
      res = client.send(HttpRequest.newBuilder(current).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    return null;
  }

  /**
   * Authenticates a user via FusionAuth's {@code /api/login} endpoint and returns the issued access token (JWT).
   *
   * @param email         The user's email.
   * @param password      The user's password.
   * @param applicationId The application UUID to authenticate against.
   * @return The access token JWT.
   */
  public static String login(String email, String password, String applicationId) throws Exception {
    String body = MAPPER.writeValueAsString(Map.of(
        "loginId", email,
        "password", password,
        "applicationId", applicationId
    ));
    HttpRequest req = HttpRequest.newBuilder(URI.create(FA_BASE_URL + "/api/login"))
                                 .header("Authorization", API_KEY)
                                 .header("Content-Type", "application/json")
                                 .header("X-FusionAuth-TenantId", STANDARD_TENANT_ID)
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .build();
    HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() != 200) {
      throw new IllegalStateException("FusionAuth login failed [" + res.statusCode() + "]: [" + res.body() + "]");
    }

    JsonNode json = MAPPER.readTree(res.body());
    JsonNode token = json.get("token");
    if (token == null || token.isNull()) {
      throw new IllegalStateException("FusionAuth login response missing [token]: [" + res.body() + "]");
    }
    return token.asText();
  }

  private static String formEncode(Map<String, String> form) {
    return form.entrySet()
               .stream()
               .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                   + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
               .reduce((a, b) -> a + "&" + b)
               .orElse("");
  }

  /**
   * The output of {@link #fetchAuthorizationCode}. {@code state} doubles as the PKCE code-verifier under Latte's
   * single-value scheme — set it as the {@code oidc_state} cookie when invoking the callback under test.
   */
  public record AuthorizationCode(String code, String state) {
  }
}
