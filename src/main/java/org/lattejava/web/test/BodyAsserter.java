/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.test;

/**
 * Base class for body asserters used with
 * {@link WebTestAsserter#assertBodyAs(BodyAsserter, java.util.function.Consumer)}.
 * <p>
 * Subclasses receive the response body via {@link #body(byte[])} and expose a fluent API for assertions over that body.
 * Failures throw {@link AssertionError} with messages formatted to match TestNG's wire format so IntelliJ IDEA's
 * comparison-failure highlighting works as it does for native TestNG assertions.
 *
 * @author Brian Pontarelli
 */
public abstract class BodyAsserter {
  protected byte[] body;

  /**
   * Sets the response body to assert against. Called by {@link WebTestAsserter} before the consumer runs.
   *
   * @param body The response body, may be {@code null}.
   */
  public void body(byte[] body) {
    this.body = body;
  }
}
