package org.lattejava.web.oidc.internal;

import module com.fasterxml.jackson.databind;
import module java.base;
import module java.net.http;
import module jwt;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * Helpers for OIDC, networking, JSON, etc.
 *
 * @author Brian Pontarelli
 */
public class Tools {
  public static final ScopedValue<JWT> CURRENT_JWT = ScopedValue.newInstance();

  public static final HttpClient HTTP = HttpClient.newBuilder()
                                                  .followRedirects(HttpClient.Redirect.ALWAYS)
                                                  .connectTimeout(Duration.ofSeconds(5))
                                                  .build();

  public static final ObjectMapper MAPPER = new ObjectMapper();

  private Tools() {
  }

  /**
   * Sets all the auth cookies using the correct settings for each. If any are null, they are not set. This ensures that
   * the code doesn't drift and set invalid auth cookies.
   */
  public static void addAuthCookies(HTTPRequest req, HTTPResponse res, OIDCConfig config, String idToken, String accessToken, String refreshToken, long expirySeconds) {
    if (idToken != null) {
      Cookie c = new Cookie(config.idTokenCookieName(), idToken);
      addCommonCookieSettings(c, req);
      c.setMaxAge(expirySeconds);
      res.addCookie(c);
    }

    if (accessToken != null) {
      Cookie c = new Cookie(config.accessTokenCookieName(), accessToken);
      addCommonCookieSettings(c, req);
      c.setMaxAge(expirySeconds);
      c.setHttpOnly(true);
      res.addCookie(c);
    }

    if (refreshToken != null) {
      Cookie c = new Cookie(config.refreshTokenCookieName(), refreshToken);
      addCommonCookieSettings(c, req);
      c.setMaxAge(config.refreshTokenMaxAge().toSeconds());
      c.setHttpOnly(true);
      res.addCookie(c);
    }
  }

  /**
   * Sets a transient cookie (no Max-Age, so the browser discards it at the end of the current session).
   */
  public static void addTransientCookie(HTTPRequest req, HTTPResponse res, String name, String value) {
    Cookie c = new Cookie(name, value);
    addCommonCookieSettings(c, req);
    c.setHttpOnly(true);
    res.addCookie(c);
  }

  /**
   * Clears all authentication cookies from the response.
   *
   * @param res    The response.
   * @param config The OIDC configuration used to pull the cookie names from.
   */
  public static void clearAllAuthCookies(HTTPResponse res, OIDCConfig config) {
    clearCookie(res, config.accessTokenCookieName());
    clearCookie(res, config.idTokenCookieName());
    clearCookie(res, config.refreshTokenCookieName());
  }

  /**
   * Clears all the cookies used by the OIDC middleware.
   *
   * @param res    The response.
   * @param config The OIDC configuration used to pull the cookie names from.
   */
  public static void clearAllCookies(HTTPResponse res, OIDCConfig config) {
    clearAllAuthCookies(res, config);
    clearCookie(res, config.stateCookieName());
    clearCookie(res, config.returnToCookieName());
  }

  /**
   * Clears a cookie by setting its value to empty with Max-Age=0.
   */
  public static void clearCookie(HTTPResponse res, String name) {
    Cookie c = new Cookie(name, "");
    c.setPath("/");
    c.setSecure(true);
    c.setSameSite(Cookie.SameSite.Strict);
    c.setMaxAge(0L);
    res.addCookie(c);
  }

  public static String computeCodeChallenge(String verifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * Encodes a map of form data into a URL-encoded string.
   *
   * @param form The form data.
   * @return The URL-encoded string.
   */
  public static String formEncode(Map<String, String> form) {
    return form.entrySet()
               .stream()
               .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                   + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
               .reduce((a, b) -> a + "&" + b)
               .orElse("");
  }

  public static TokenEndpointResponse postToken(OIDCConfig config, Map<String, String> form) throws IOException, InterruptedException {
    String body = Tools.formEncode(form);
    String basic = "Basic " + Base64.getEncoder().encodeToString(
        (config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8)
    );
    HttpRequest req = HttpRequest.newBuilder(config.tokenEndpoint())
                                 .header("Authorization", basic)
                                 .header("Content-Type", "application/x-www-form-urlencoded")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .build();
    HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    return new TokenEndpointResponse(res.statusCode(), res.body());
  }

  /**
   * Safely reads a cookie from the request. If the cookie is not present, null is returned.
   *
   * @param req  The request.
   * @param name The name of the cookie.
   * @return The cookie value, or null.
   */
  public static String readCookie(HTTPRequest req, String name) {
    Cookie c = req.getCookie(name);
    return c != null ? c.value : null;
  }

  /**
   * Enforces that the URI uses HTTPS, except when the host is a loopback address. This makes local development with
   * {@code http://localhost:9011} (etc.) workable without undermining production security posture.
   */
  public static void requireSecureURI(String field, URI uri) {
    if (uri == null) {
      return;
    }

    String scheme = uri.getScheme();
    if ("https".equalsIgnoreCase(scheme)) {
      return;
    }

    String host = uri.getHost();
    boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    if ("http".equalsIgnoreCase(scheme) && loopback) {
      return;
    }

    throw new IllegalArgumentException("[" + field + "] must use https (http:// is permitted only for localhost/127.0.0.1/::1): [" + uri + "]");
  }

  /**
   * Strips trailing slashes from a string.
   *
   * @param s The string.
   * @return The string with trailing slashes removed.
   */
  public static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  /**
   * Converts a property in a JsonNode to text if possible. If it isn't possible, null is returned.
   *
   * @param node     The JsonNode.
   * @param property The property in the node to convert.
   * @return The text or null.
   */
  public static String textOrNull(JsonNode node, String property) {
    JsonNode v = node != null ? node.get(property) : null;
    return (v != null && !v.isNull()) ? v.asText() : null;
  }

  /**
   * Converts a userinfo response to a JWT.
   *
   * @param json The userinfo response.
   * @return The JWT.
   */
  public static JWT userinfoToJWT(JsonNode json) {
    JWT.Builder builder = JWT.builder();
    Iterable<Map.Entry<String, JsonNode>> it = json.properties();
    for (var e : it) {
      builder.claim(e.getKey(), unwrap(e.getValue()));
    }
    return builder.build();
  }

  private static void addCommonCookieSettings(Cookie c, HTTPRequest req) {
    c.setPath("/");
    c.setSecure(req.getScheme().equalsIgnoreCase("https") || req.getHeader("X-Forwarded-Proto").equalsIgnoreCase("https"));
    c.setSameSite(Cookie.SameSite.Strict);
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
      for (var e : node.properties()) {
        out.put(e.getKey(), unwrap(e.getValue()));
      }
      return out;
    }

    return node.asText();
  }

  /**
   * Token-endpoint response (status + body).
   */
  public record TokenEndpointResponse(int statusCode, String body) {
    public boolean failed() {
      return statusCode < 200 || statusCode >= 300;
    }
  }
}
