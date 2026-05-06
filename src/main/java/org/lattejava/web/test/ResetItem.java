/*
 * Copyright (c) 2025-2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.test;

/**
 * Items that {@link WebTestAsserter#reset(ResetItem...)} can clear between requests.
 *
 * @author Brian Pontarelli
 */
public enum ResetItem {
  Cookies,
  HttpClient,
  Request
}
