/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.oidc;

import module java.base;
import module java.net.http;
import module org.lattejava.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.lattejava.jwt.Verifier;
import org.lattejava.jwt.alg.rsa.RSAVerifier;
import org.lattejava.jwt.domain.JWT;
import org.lattejava.jwt.jwks.JSONWebKey;
import org.lattejava.jwt.jwks.JSONWebKeyParser;
import org.lattejava.jwt.jwks.JSONWebKeySetHelper;
import org.lattejava.jwt.oauth2.AuthorizationServerMetaData;
import org.lattejava.jwt.oauth2.ServerMetaDataHelper;
import org.lattejava.web.*;

/**
 * The OpenID Connect entry point for Latte Web. Install the instance at the root with
 * {@code web.install(oidc)} — it handles the callback, logout, and logout-return paths by virtue of being a
 * {@link Middleware} itself. Attach protection where needed via {@link #authenticated()}, {@link #hasAnyRole(String...)},
 * or {@link #hasAllRoles(String...)}, which are thin factories around the public {@link Authenticated}, {@link HasAnyRole},
 * and {@link HasAllRoles} middlewares.
 * <p>
 * Request-scoped access to the authenticated JWT is available via the static {@link #jwt()} / {@link #optionalJWT()} and
 * the instance-level {@link #user()} / {@link #optionalUser()}, which apply the configured translator. See
 * {@code docs/design/oidc.md} for the full design.
 *
 * @param <U> The translated-user type. Use {@link #create(OIDCConfig)} for the untyped
 *     {@code OpenIDConnect<JWT>} or {@link #create(OIDCConfig, Function)} with a custom translator.
 *
 * @author Brian Pontarelli
 */
public class OpenIDConnect<U> implements Middleware {
  static final ScopedValue<JWT> CURRENT_JWT = ScopedValue.newInstance();

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  private final URI authorizeEndpoint;
  private final OIDCConfig config;
  private final HttpClient http;
  private final URI jwksEndpoint;
  private final Map<String, Verifier> jwksVerifiers;
  private final URI logoutEndpoint;
  private final URI tokenEndpoint;
  private final Function<JWT, U> translator;
  private final URI userinfoEndpoint;

  /**
   * Constructs an untyped {@code OpenIDConnect<JWT>} — {@link #user()} returns the bound JWT directly.
   *
   * @param config The configuration.
   * @return The constructed OpenIDConnect instance.
   * @throws IllegalStateException If discovery fails or a required endpoint can't be resolved.
   */
  public static OpenIDConnect<JWT> create(OIDCConfig config) {
    return new OpenIDConnect<>(config, Function.identity());
  }

  /**
   * Constructs a typed OpenIDConnect, applying the translator on every call to {@link #user()}.
   *
   * @param config     The configuration.
   * @param translator Maps a JWT to the user's domain object.
   * @return The constructed OpenIDConnect instance.
   * @throws IllegalStateException If discovery fails or a required endpoint can't be resolved.
   */
  public static <U> OpenIDConnect<U> create(OIDCConfig config, Function<JWT, U> translator) {
    return new OpenIDConnect<>(config, translator);
  }

  /**
   * Returns the raw JWT bound to the current request. Throws if called outside a protected route.
   *
   * @return The bound JWT.
   * @throws UnauthenticatedException If no JWT is currently bound.
   */
  public static JWT jwt() {
    if (!CURRENT_JWT.isBound()) {
      throw new UnauthenticatedException();
    }
    return CURRENT_JWT.get();
  }

  /**
   * Returns the raw JWT bound to the current request, or empty if none is bound.
   *
   * @return An Optional carrying the bound JWT or empty.
   */
  public static Optional<JWT> optionalJWT() {
    return CURRENT_JWT.isBound() ? Optional.of(CURRENT_JWT.get()) : Optional.empty();
  }

  private OpenIDConnect(OIDCConfig config, Function<JWT, U> translator) {
    this.config = config;
    this.translator = translator;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    URI authorize = config.authorizeEndpoint();
    URI token = config.tokenEndpoint();
    URI userinfo = config.userinfoEndpoint();
    URI jwks = config.jwksEndpoint();
    URI logout = config.logoutEndpoint();

    if (config.issuer() != null) {
      AuthorizationServerMetaData meta;
      try {
        meta = ServerMetaDataHelper.retrieveFromWellKnownConfiguration(
            stripTrailingSlash(config.issuer()) + "/.well-known/openid-configuration");
      } catch (Exception e) {
        throw new IllegalStateException("Failed to fetch OIDC discovery document for issuer [" + config.issuer() + "]", e);
      }
      if (authorize == null && meta.authorization_endpoint != null) {
        authorize = URI.create(meta.authorization_endpoint);
      }
      if (token == null && meta.token_endpoint != null) {
        token = URI.create(meta.token_endpoint);
      }
      if (userinfo == null && meta.otherClaims.get("userinfo_endpoint") instanceof String s) {
        userinfo = URI.create(s);
      }
      if (jwks == null && meta.jwks_uri != null) {
        jwks = URI.create(meta.jwks_uri);
      }
      if (logout == null && meta.otherClaims.get("end_session_endpoint") instanceof String s) {
        logout = URI.create(s);
      }
    }

    if (authorize == null || token == null || jwks == null) {
      throw new IllegalStateException(
          "Required endpoint unresolved — set issuer or provide explicit authorize/token/jwks endpoints");
    }
    if (!config.validateAccessToken() && userinfo == null) {
      throw new IllegalStateException(
          "validateAccessToken=false requires userinfoEndpoint — set explicitly or provide issuer for discovery");
    }

    this.authorizeEndpoint = authorize;
    this.tokenEndpoint = token;
    this.userinfoEndpoint = userinfo;
    this.jwksEndpoint = jwks;
    this.logoutEndpoint = logout;

    this.jwksVerifiers = new ConcurrentHashMap<>();
    refreshJWKS();
  }

  /**
   * System middleware dispatch: handles {@code callbackPath}, {@code logoutPath}, and {@code logoutReturnPath}. Any
   * other path passes through to the chain.
   */
  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    String path = req.getPath();
    if (path.equals(config.callbackPath())) {
      handleCallback(req, res);
      return;
    }
    if (path.equals(config.logoutPath())) {
      handleLogout(req, res);
      return;
    }
    if (path.equals(config.logoutReturnPath())) {
      handleLogoutReturn(req, res);
      return;
    }
    chain.next(req, res);
  }

  /**
   * @return A new {@link Authenticated} middleware bound to this OpenIDConnect instance.
   */
  public Authenticated authenticated() {
    return new Authenticated(this);
  }

  /**
   * @param roles One or more roles; the middleware lets the request through when the authenticated user has at least
   *              one of them.
   * @return A new {@link HasAnyRole} middleware bound to this OpenIDConnect instance.
   */
  public HasAnyRole hasAnyRole(String... roles) {
    return new HasAnyRole(this, roles);
  }

  /**
   * @param roles One or more roles; the middleware requires the authenticated user to possess every listed role.
   * @return A new {@link HasAllRoles} middleware bound to this OpenIDConnect instance.
   */
  public HasAllRoles hasAllRoles(String... roles) {
    return new HasAllRoles(this, roles);
  }

  /**
   * Translates the JWT bound to the current request into the configured user type. Throws if called outside a
   * protected route.
   *
   * @return The translated user.
   * @throws UnauthenticatedException If no JWT is currently bound.
   */
  public U user() {
    if (!CURRENT_JWT.isBound()) {
      throw new UnauthenticatedException();
    }
    return translator.apply(CURRENT_JWT.get());
  }

  /**
   * Translates the JWT bound to the current request into the configured user type, or returns empty if no JWT is
   * bound.
   *
   * @return An Optional carrying the translated user or empty.
   */
  public Optional<U> optionalUser() {
    return CURRENT_JWT.isBound() ? Optional.of(translator.apply(CURRENT_JWT.get())) : Optional.empty();
  }

  OIDCConfig config() {
    return config;
  }

  URI authorizeEndpoint() {
    return authorizeEndpoint;
  }

  URI tokenEndpoint() {
    return tokenEndpoint;
  }

  URI userinfoEndpoint() {
    return userinfoEndpoint;
  }

  URI jwksEndpoint() {
    return jwksEndpoint;
  }

  URI logoutEndpoint() {
    return logoutEndpoint;
  }

  HttpClient http() {
    return http;
  }

  /**
   * Returns a verifier function the JWT decoder can use to look up a verifier by {@code kid}. On cache miss, refreshes
   * the JWKS once and retries — handles key rotation transparently.
   */
  Function<String, Verifier> verifierFunction() {
    return kid -> {
      Verifier v = jwksVerifiers.get(kid);
      if (v != null) {
        return v;
      }
      refreshJWKS();
      return jwksVerifiers.get(kid);
    };
  }

  private void refreshJWKS() {
    try {
      List<JSONWebKey> keys = JSONWebKeySetHelper.retrieveKeysFromJWKS(jwksEndpoint.toString());
      JSONWebKeyParser parser = new JSONWebKeyParser();
      Map<String, Verifier> next = new HashMap<>();
      for (JSONWebKey key : keys) {
        String kid = key.kid;
        if (kid == null) {
          continue;
        }
        try {
          PublicKey publicKey = parser.parse(key);
          if (publicKey != null) {
            next.put(kid, RSAVerifier.newVerifier(publicKey));
          }
        } catch (Exception ignored) {
          // Skip keys we can't parse (e.g., non-RSA)
        }
      }
      jwksVerifiers.clear();
      jwksVerifiers.putAll(next);
    } catch (Exception e) {
      // Don't take down OIDC if JWKS refresh fails transiently; existing cache still usable.
    }
  }

  private void handleCallback(HTTPRequest req, HTTPResponse res) throws Exception {
    Map<String, String> query = parseQuery(req.getQueryString());

    String error = query.get("error");
    if (error != null) {
      OIDCCookies.clear(res, config.stateCookieName());
      OIDCCookies.clear(res, config.returnToCookieName());
      String desc = query.getOrDefault("error_description", error);
      String target = config.postLoginLanding()
          + (config.postLoginLanding().contains("?") ? "&" : "?")
          + "oidc_error=" + URLEncoder.encode(desc, StandardCharsets.UTF_8);
      res.sendRedirect(target);
      return;
    }

    String queryState = query.get("state");
    String cookieState = readCookie(req, config.stateCookieName());
    if (queryState == null || cookieState == null || !queryState.equals(cookieState)) {
      res.setStatus(400);
      return;
    }

    String code = query.get("code");
    if (code == null || code.isBlank()) {
      res.setStatus(400);
      return;
    }

    URI redirectURI = redirectURIFor(req);
    TokenEndpointResponse tok;
    try {
      tok = exchangeCode(code, redirectURI, cookieState);
    } catch (Exception e) {
      res.setStatus(500);
      return;
    }
    if (!tok.success()) {
      res.setStatus(400);
      return;
    }

    JsonNode body = MAPPER.readTree(tok.body());
    String accessToken = textOrNull(body, "access_token");
    String idToken = textOrNull(body, "id_token");
    String refreshToken = textOrNull(body, "refresh_token");
    long expiresIn = body.has("expires_in") ? body.get("expires_in").asLong() : 3600L;

    if (accessToken == null || idToken == null) {
      res.setStatus(400);
      return;
    }

    // Always verify the id_token signature (OIDC spec requires it).
    try {
      JWT.getDecoder().decode(idToken, verifierFunction());
    } catch (Exception e) {
      res.setStatus(400);
      return;
    }
    // When validateAccessToken=true, verify the access-token JWT too.
    if (config.validateAccessToken()) {
      try {
        JWT.getDecoder().decode(accessToken, verifierFunction());
      } catch (Exception e) {
        res.setStatus(400);
        return;
      }
    }

    OIDCCookies.setAuthCookie(res, config.accessTokenCookieName(), accessToken, expiresIn, true);
    OIDCCookies.setAuthCookie(res, config.idTokenCookieName(), idToken, expiresIn, false);
    if (refreshToken != null) {
      OIDCCookies.setAuthCookie(res, config.refreshTokenCookieName(), refreshToken,
          config.refreshTokenMaxAge().toSeconds(), true);
    }
    OIDCCookies.clear(res, config.stateCookieName());
    String returnTo = readCookie(req, config.returnToCookieName());
    OIDCCookies.clear(res, config.returnToCookieName());

    res.sendRedirect(returnTo != null && !returnTo.isBlank() ? returnTo : config.postLoginLanding());
  }

  private void handleLogout(HTTPRequest req, HTTPResponse res) throws Exception {
    if (logoutEndpoint == null) {
      clearAllAuthCookies(res);
      res.sendRedirect(config.postLogoutLanding());
      return;
    }

    String idToken = readCookie(req, config.idTokenCookieName());
    URI returnURI = URI.create(req.getBaseURL() + config.logoutReturnPath());

    StringBuilder url = new StringBuilder(logoutEndpoint.toString());
    url.append(url.indexOf("?") < 0 ? '?' : '&');
    url.append("post_logout_redirect_uri=")
        .append(URLEncoder.encode(returnURI.toString(), StandardCharsets.UTF_8));
    url.append("&client_id=")
        .append(URLEncoder.encode(config.clientId(), StandardCharsets.UTF_8));
    if (idToken != null) {
      url.append("&id_token_hint=")
          .append(URLEncoder.encode(idToken, StandardCharsets.UTF_8));
    }
    res.sendRedirect(url.toString());
  }

  private void handleLogoutReturn(HTTPRequest req, HTTPResponse res) throws Exception {
    clearAllAuthCookies(res);
    OIDCCookies.clear(res, config.stateCookieName());
    OIDCCookies.clear(res, config.returnToCookieName());
    res.sendRedirect(config.postLogoutLanding());
  }

  private void clearAllAuthCookies(HTTPResponse res) {
    OIDCCookies.clear(res, config.accessTokenCookieName());
    OIDCCookies.clear(res, config.idTokenCookieName());
    OIDCCookies.clear(res, config.refreshTokenCookieName());
  }

  URI redirectURIFor(HTTPRequest req) {
    return config.redirectURI() != null
        ? config.redirectURI()
        : URI.create(req.getBaseURL() + config.callbackPath());
  }

  TokenEndpointResponse exchangeCode(String code, URI redirectURI, String codeVerifier)
      throws IOException, InterruptedException {
    String body = formEncode(Map.of(
        "grant_type", "authorization_code",
        "code", code,
        "redirect_uri", redirectURI.toString(),
        "code_verifier", codeVerifier));
    return postToken(body);
  }

  TokenEndpointResponse refresh(String refreshToken) throws IOException, InterruptedException {
    String body = formEncode(Map.of(
        "grant_type", "refresh_token",
        "refresh_token", refreshToken));
    return postToken(body);
  }

  private TokenEndpointResponse postToken(String body) throws IOException, InterruptedException {
    String basic = "Basic " + Base64.getEncoder().encodeToString(
        (config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8));
    HttpRequest req = HttpRequest.newBuilder(tokenEndpoint)
        .header("Authorization", basic)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
    return new TokenEndpointResponse(res.statusCode(), res.body());
  }

  /**
   * Calls the userinfo endpoint with the bearer access token. Wraps the resulting claims in a {@link JWT} for
   * ScopedValue binding when {@code validateAccessToken=false}.
   */
  UserinfoResult callUserinfo(String accessToken) {
    try {
      HttpRequest req = HttpRequest.newBuilder(userinfoEndpoint)
          .header("Authorization", "Bearer " + accessToken)
          .GET()
          .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      int status = res.statusCode();
      if (status == 200) {
        JsonNode json = MAPPER.readTree(res.body());
        return UserinfoResult.valid(jwtFromClaims(json));
      }
      if (status == 401) {
        return UserinfoResult.invalid();
      }
      return UserinfoResult.networkError();
    } catch (Exception e) {
      return UserinfoResult.networkError();
    }
  }

  private static JWT jwtFromClaims(JsonNode json) {
    JWT jwt = new JWT();
    Iterator<Map.Entry<String, JsonNode>> it = json.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      jwt.addClaim(e.getKey(), unwrap(e.getValue()));
    }
    return jwt;
  }

  private static Object unwrap(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isBoolean()) {
      return node.asBoolean();
    }
    if (node.isInt()) {
      return node.asInt();
    }
    if (node.isLong()) {
      return node.asLong();
    }
    if (node.isDouble() || node.isFloat()) {
      return node.asDouble();
    }
    if (node.isArray()) {
      List<Object> out = new ArrayList<>();
      for (JsonNode child : node) {
        out.add(unwrap(child));
      }
      return out;
    }
    if (node.isObject()) {
      Map<String, Object> out = new LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> it = node.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        out.put(e.getKey(), unwrap(e.getValue()));
      }
      return out;
    }
    return node.asText();
  }

  static Map<String, String> parseQuery(String raw) {
    Map<String, String> out = new HashMap<>();
    if (raw == null || raw.isEmpty()) {
      return out;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      if (eq < 0) {
        out.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
      } else {
        out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
      }
    }
    return out;
  }

  static String readCookie(HTTPRequest req, String name) {
    Cookie c = req.getCookie(name);
    return c == null ? null : c.value;
  }

  private static String formEncode(Map<String, String> form) {
    return form.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .reduce((a, b) -> a + "&" + b)
        .orElse("");
  }

  private static String textOrNull(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return (v == null || v.isNull()) ? null : v.asText();
  }

  private static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  /**
   * Token-endpoint response (status + body).
   */
  record TokenEndpointResponse(int statusCode, String body) {
    boolean success() {
      return statusCode >= 200 && statusCode < 300;
    }
  }

  /**
   * Outcome of a call to the userinfo endpoint.
   */
  record UserinfoResult(Status status, JWT claims) {
    enum Status { VALID, INVALID, NETWORK_ERROR }

    static UserinfoResult valid(JWT claims) { return new UserinfoResult(Status.VALID, claims); }

    static UserinfoResult invalid() { return new UserinfoResult(Status.INVALID, null); }

    static UserinfoResult networkError() { return new UserinfoResult(Status.NETWORK_ERROR, null); }
  }
}
