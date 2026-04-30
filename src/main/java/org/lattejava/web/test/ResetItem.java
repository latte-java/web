/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
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
