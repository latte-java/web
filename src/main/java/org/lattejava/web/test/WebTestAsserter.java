/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.test;

import module java.base;
import module java.net.http;
import module org.lattejava.http;

/**
 * The asserter returned from each verb call on {@link WebTest}. Provides assertions over the response and a
 * {@link #reset(ResetItem...)} method that returns the parent {@link WebTest} for issuing the next request.
 * <p>
 * All assertion methods throw {@link AssertionError} with messages formatted to match TestNG's wire format so IDE
 * comparison-failure highlighting works as expected.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class WebTestAsserter {
  private final HttpResponse<byte[]> response;
  private final WebTest tester;

  public WebTestAsserter(WebTest tester, HttpResponse<byte[]> response) {
    this.tester = tester;
    this.response = response;
  }

  /**
   * Asserts the response body using the given asserter. The body is fed into the asserter and the consumer is invoked
   * to perform individual assertions.
   *
   * @param bodyAsserter The asserter to populate.
   * @param consumer     The consumer that performs assertions on the populated asserter.
   * @param <T>          The asserter type.
   * @return This asserter for chaining.
   */
  public <T extends BodyAsserter> WebTestAsserter assertBodyAs(T bodyAsserter, Consumer<T> consumer) {
    bodyAsserter.body(response.body());
    consumer.accept(bodyAsserter);
    return this;
  }

  /**
   * Asserts that the response carries a cookie with the given name and value.
   *
   * @param name  The cookie name.
   * @param value The expected cookie value.
   * @return This asserter for chaining.
   */
  public WebTestAsserter assertCookie(String name, String value) {
    Cookie cookie = tester.cookies.get(name);
    String actual = cookie == null ? null : cookie.value;
    Assertions.assertEquals(actual, value, "Cookie [" + name + "] does not match");
    return this;
  }

  /**
   * Asserts that the named response header equals the given value (using the first value if the header is repeated).
   *
   * @param name     The header name.
   * @param expected The expected value.
   * @return This asserter for chaining.
   */
  public WebTestAsserter assertHeader(String name, String expected) {
    String actual = response.headers().firstValue(name).orElse(null);
    Assertions.assertEquals(actual, expected, "Header [" + name + "] does not match");
    return this;
  }

  /**
   * Asserts the response status code.
   *
   * @param expected The expected status.
   * @return This asserter for chaining.
   */
  public WebTestAsserter assertStatus(int expected) {
    Assertions.assertEquals(response.statusCode(), expected, "Status code does not match");
    return this;
  }

  /**
   * Returns the underlying response for direct access.
   *
   * @return The response.
   */
  public HttpResponse<byte[]> response() {
    return response;
  }

  /**
   * Resets the cookie jar and any pending request state, then returns the parent tester for the next request.
   *
   * @return The parent tester.
   */
  public WebTest reset() {
    return reset(ResetItem.Cookies, ResetItem.Request);
  }

  /**
   * Resets the specified items, then returns the parent tester for the next request.
   *
   * @param items The items to reset.
   * @return The parent tester.
   */
  public WebTest reset(ResetItem... items) {
    for (ResetItem item : items) {
      switch (item) {
        case Cookies -> tester.clearCookies();
        case HttpClient -> tester.replaceClient();
        case Request -> tester.clearRequestState();
      }
    }
    return tester;
  }
}
