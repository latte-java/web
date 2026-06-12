/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc.internal;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.jwt;
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

  private static final Cookies COOKIES = Cookies.newInstance();

  private Tools() {
  }

  /**
   * Sets all the auth cookies using explicit names and max-age. If any token is null, it is not set. Cookie policy
   * mirrors the OIDCConfig overload: id/access use SameSite=Lax; refresh uses SameSite=Strict (default) with the
   * supplied max-age.
   *
   * @param req                The current request.
   * @param res                The response.
   * @param accessTokenName    The name of the access token cookie.
   * @param refreshTokenName   The name of the refresh token cookie.
   * @param idTokenName        The name of the id token cookie.
   * @param refreshTokenMaxAge The max-age for the refresh token cookie.
   * @param idToken            The id token value, or {@code null}.
   * @param accessToken        The access token value, or {@code null}.
   * @param refreshToken       The refresh token value, or {@code null}.
   * @param expirySeconds      The max-age in seconds for the id and access token cookies.
   */
  public static void addAuthCookies(HTTPRequest req, HTTPResponse res, String accessTokenName, String refreshTokenName,
                                    String idTokenName, Duration refreshTokenMaxAge, String idToken, String accessToken,
                                    String refreshToken, long expirySeconds) {
    if (idToken != null) {
      COOKIES.write(idTokenName, idToken)
             .httpOnly(false)
             .maxAge(Duration.ofSeconds(expirySeconds))
             .sameSite(Cookie.SameSite.Lax)
             .to(req, res);
    }

    if (accessToken != null) {
      COOKIES.write(accessTokenName, accessToken)
             .maxAge(Duration.ofSeconds(expirySeconds))
             .sameSite(Cookie.SameSite.Lax)
             .to(req, res);
    }

    if (refreshToken != null) {
      COOKIES.write(refreshTokenName, refreshToken)
             .maxAge(refreshTokenMaxAge)
             .to(req, res);
    }
  }

  /**
   * Sets a transient cookie (no Max-Age, so the browser discards it at the end of the current session).
   */
  public static void addTransientCookie(HTTPRequest req, HTTPResponse res, String name, String value) {
    COOKIES.write(name, value).to(req, res);
  }

  /**
   * Clears a cookie by setting its value to empty with Max-Age=0.
   */
  public static void clearCookie(HTTPRequest req, HTTPResponse res, String name) {
    COOKIES.clear(name).from(req, res);
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

  /**
   * Converts a flat JSON object of claims to a {@link JWT}, mapping each property to a claim. Used to build the bound
   * JWT from an IdP response (RFC 7662 introspection or userinfo) when the access token is opaque and cannot be decoded
   * locally.
   *
   * @param introspect The JSON introspect object.
   * @return The JWT.
   */
  public static JWT jsonToJWT(TokenValidator.Introspect introspect) {
    JWT.Builder builder = JWT.builder();
    Iterable<Map.Entry<String, Object>> it = introspect.claims().entrySet();
    for (var e : it) {
      builder.claim(e.getKey(), e.getValue());
    }
    return builder.build();
  }

  public static TokenEndpointResponse postToken(OIDCConfig config, Map<String, String> form) throws IOException, InterruptedException {
    HttpRequest.Builder builder = HttpRequest.newBuilder(config.tokenEndpoint())
                                             .header("Content-Type", "application/x-www-form-urlencoded");
    Map<String, String> effectiveForm = form;
    if (config.publicClient()) {
      // Public client: no Basic auth; client_id travels in the form body (RFC 6749 §3.2.1). PKCE provides the
      // proof-of-possession on the matching authorize request.
      effectiveForm = new LinkedHashMap<>(form);
      effectiveForm.put("client_id", config.clientId());
    } else {
      // Confidential client: HTTP Basic with clientId:clientSecret.
      String basic = "Basic " + Base64.getEncoder().encodeToString(
          (config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8)
      );
      builder.header("Authorization", basic);
    }

    HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(Tools.formEncode(effectiveForm))).build();
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
    return COOKIES.read(name).from(req);
  }

  /**
   * Exchanges a refresh token for a fresh token set at the configured token endpoint, parsing the response. This is the
   * transport-agnostic core shared by every profile's {@code Authentication} orchestrator; writing the new tokens back
   * is delegated to the profile's {@link TokenWriter}.
   *
   * @param config       The OIDC configuration.
   * @param refreshToken The refresh token to exchange.
   * @return The parsed {@link Tokens} on success, or {@code null} on any thrown exception, non-2xx response,
   *     unparseable body, or a response missing {@code access_token}.
   */
  public static Tokens refresh(OIDCConfig config, String refreshToken) {
    TokenEndpointResponse tokenResponse;
    try {
      tokenResponse = postToken(config, Map.of("grant_type", "refresh_token", "refresh_token", refreshToken));
    } catch (Exception e) {
      return null;
    }

    if (tokenResponse.failed()) {
      return null;
    }

    Tokens tokens;
    try {
      tokens = TokensJSON.fromJSON(tokenResponse.body());
    } catch (Exception e) {
      return null;
    }

    if (tokens.accessToken() == null) {
      return null;
    }

    return tokens;
  }

  /**
   * Enforces that the URI uses HTTPS, except when the host is a loopback address. This makes local development with
   * {@code http://localhost:9012} (etc.) workable without undermining production security posture.
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
   * Writes out a simple HTML page that performs a meta refresh to the given URL.
   *
   * @param res The response to write the HTML to.
   * @param url The URL to refresh to.
   * @throws IOException If the write fails.
   */
  public static void writeMetaRefresh(HTTPResponse res, String url) throws IOException {
    res.setStatus(200);
    res.setContentType("text/html; charset=utf-8");
    res.getWriter().write("""
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8">
          <meta http-equiv="refresh" content="0; url=%1$s">
          <title>Redirecting…</title>
        </head>
        </html>
        """.formatted(url));
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
