/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.test;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;

import static java.nio.charset.StandardCharsets.*;

/**
 * A fluent test client for driving a running {@link Web} instance over HTTP.
 * <p>
 * Configure a request by chaining {@code with*} methods (headers, URL parameters, body, cookies), then issue a request
 * with one of the verb methods ({@link #get(String)}, {@link #post(String)}, etc.). The returned
 * {@link WebTestAsserter} exposes assertions over the response and provides {@link WebTestAsserter#reset reset(...)} to
 * return to a fresh tester for the next request.
 * <p>
 * The tester maintains a cookie jar across requests so session cookies set by the server flow into subsequent requests
 * automatically. The jar uses {@link org.lattejava.http.Cookie}, which preserves the {@code SameSite} attribute that
 * Java's {@link java.net.HttpCookie} drops.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class WebTest {
  public final CookieJar cookies = new CookieJar();
  public final List<Map.Entry<String, String>> headers = new ArrayList<>();
  public final int port;
  public final List<Map.Entry<String, String>> urlParameters = new ArrayList<>();
  public byte[] body;
  public HttpClient client;

  /**
   * Creates a new request simulator that can be used to make requests to a Web application.
   *
   * @param port The port to use for non-TLS connections.
   */
  public WebTest(int port) {
    this.port = port;
    this.client = newHttpClient();
  }

  /**
   * Builds a fresh {@link HttpClient} configured for tests: short connect timeout, virtual-thread executor, no
   * automatic redirect following, and no cookie handler (the tester's {@link CookieJar} owns cookie state because the
   * JDK's cookie store drops {@code SameSite}).
   *
   * @return A new HttpClient.
   */
  public static HttpClient newHttpClient() {
    return HttpClient.newBuilder()
                     .connectTimeout(Duration.ofMillis(250))
                     .executor(Executors.newVirtualThreadPerTaskExecutor())
                     .followRedirects(HttpClient.Redirect.NEVER)
                     .build();
  }

  /**
   * Builds a local testing URI using {@code localhost} and the configured port plus the path.
   *
   * @param path The path.
   * @return The URI.
   */
  public URI buildURI(String path) {
    StringBuilder builder = new StringBuilder("http://localhost:").append(port).append(path);
    if (!urlParameters.isEmpty()) {
      builder.append('?')
             .append(
                 urlParameters.stream()
                              .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getKey(), UTF_8))
                              .collect(Collectors.joining("&"))
             );
    }

    return URI.create(builder.toString());
  }

  public void clearCookies() {
    cookies.clear();
  }

  public void clearRequestState() {
    headers.clear();
    urlParameters.clear();
    body = null;
  }

  /**
   * Issues a DELETE request and returns the asserter for the response.
   *
   * @param path The request path.
   * @return The asserter.
   */
  public WebTestAsserter delete(String path) {
    return send("DELETE", path);
  }

  /**
   * Issues a GET request and returns the asserter for the response.
   *
   * @param path The request path.
   * @return The asserter.
   */
  public WebTestAsserter get(String path) {
    return send("GET", path);
  }

  /**
   * Issues a HEAD request and returns the asserter for the response.
   *
   * @param path The request path.
   * @return The asserter.
   */
  public WebTestAsserter head(String path) {
    return send("HEAD", path);
  }

  /**
   * Issues an OPTIONS request and returns the asserter for the response.
   *
   * @param path The request path.
   * @return The asserter.
   */
  public WebTestAsserter options(String path) {
    return send("OPTIONS", path);
  }

  /**
   * Issues a PATCH request and returns the asserter for the response.
   *
   * @param path The request path.
   * @return The asserter.
   */
  public WebTestAsserter patch(String path) {
    return send("PATCH", path);
  }

  /**
   * Issues a POST request and returns the asserter for the response.
   *
   * @param path The request path.
   * @return The asserter.
   */
  public WebTestAsserter post(String path) {
    return send("POST", path);
  }

  /**
   * Issues a PUT request and returns the asserter for the response.
   *
   * @param path The request path.
   * @return The asserter.
   */
  public WebTestAsserter put(String path) {
    return send("PUT", path);
  }

  public void replaceClient() {
    HttpClient old = client;
    client = newHttpClient();
    if (old != null) {
      try {
        old.close();
      } catch (Exception ignored) {
        // The previous client may not yet be idle; nothing actionable here for tests.
      }
    }
  }

  /**
   * Sets the request body. Subsequent verb calls will send this body.
   *
   * @param body The body to send.
   * @return This tester for chaining.
   */
  public WebTest withBody(byte[] body) {
    this.body = body;
    return this;
  }

  /**
   * Sets the request body. Subsequent verb calls will send this body.
   *
   * @param body The body to send.
   * @return This tester for chaining.
   */
  public WebTest withBody(String body) {
    return this.withBody(body.getBytes(UTF_8));
  }

  /**
   * Adds a cookie to the cookie jar so it is sent on subsequent requests.
   *
   * @param cookie The cookie to add.
   * @return This tester for chaining.
   */
  public WebTest withCookie(Cookie cookie) {
    cookies.add(cookie);
    return this;
  }

  /**
   * Adds a name/value cookie to the cookie jar so it is sent on subsequent requests.
   *
   * @param name  The cookie name.
   * @param value The cookie value.
   * @return This tester for chaining.
   */
  public WebTest withCookie(String name, String value) {
    cookies.add(new Cookie(name, value));
    return this;
  }

  /**
   * Adds a request header. Multiple values for the same header name are supported and sent in registration order.
   *
   * @param name  The header name.
   * @param value The header value.
   * @return This tester for chaining.
   */
  public WebTest withHeader(String name, String value) {
    headers.add(Map.entry(name, value));
    return this;
  }

  /**
   * Adds a URL query parameter. Multiple values for the same parameter name are supported and sent in registration
   * order.
   *
   * @param name  The parameter name.
   * @param value The parameter value.
   * @return This tester for chaining.
   */
  public WebTest withURLParameter(String name, String value) {
    urlParameters.add(Map.entry(name, value));
    return this;
  }

  private WebTestAsserter send(String method, String path) {
    var builder = HttpRequest.newBuilder()
                             .uri(buildURI(path));

    // Headers
    for (Map.Entry<String, String> entry : headers) {
      builder.header(entry.getKey(), entry.getValue());
    }

    // Cookies
    String cookieHeader = cookies.toRequestHeader();
    if (cookieHeader != null) {
      builder.header("Cookie", cookieHeader);
    }

    // Body and method
    HttpRequest.BodyPublisher publisher = body != null
        ? HttpRequest.BodyPublishers.ofByteArray(body)
        : HttpRequest.BodyPublishers.noBody();
    builder.method(method, publisher);

    // Full send!
    HttpResponse<byte[]> response;
    try {
      response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Failed to send [" + method + "] request to [" + path + "]", e);
    }

    // Cookie jar (noom noom)
    cookies.update(response);

    return new WebTestAsserter(this, response);
  }
}
