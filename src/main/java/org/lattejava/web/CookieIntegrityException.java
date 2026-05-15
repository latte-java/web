/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

/**
 * Thrown when an encrypted cookie cannot be authenticated — either because its wire format is not a valid Base64URL-
 * encoded AES-GCM ciphertext or because the GCM tag fails to verify under every configured key. The latter is
 * indistinguishable from active tampering; treat both as "this cookie is no good, clear it."
 *
 * @author Brian Pontarelli
 */
public class CookieIntegrityException extends RuntimeException {
  private final String name;
  private final Reason reason;

  public CookieIntegrityException(String name, Reason reason) {
    this(name, reason, null);
  }

  public CookieIntegrityException(String name, Reason reason, Throwable cause) {
    super("Cookie [" + name + "] failed integrity check [" + reason + "]", cause);
    this.name = name;
    this.reason = reason;
  }

  public String name() {
    return name;
  }

  public Reason reason() {
    return reason;
  }

  public enum Reason {
    DECRYPT_FAILED,
    MALFORMED
  }
}
