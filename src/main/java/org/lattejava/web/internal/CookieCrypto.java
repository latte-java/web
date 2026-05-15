/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.internal;

import module java.base;
import module org.lattejava.web;

/**
 * AES-256-GCM authenticated encryption for cookie values. The cookie name is bound into the ciphertext as Additional
 * Authenticated Data, so a value encrypted under one cookie name cannot be replayed under another.
 *
 * @author Brian Pontarelli
 */
public final class CookieCrypto {
  private static final int GCM_TAG_BYTES = 16;
  private static final int NONCE_BYTES = 12;
  private static final SecureRandom RNG = new SecureRandom();

  private CookieCrypto() {
  }

  public static String decrypt(List<SecretKey> keys, String name, String wireValue) {
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(wireValue);
    } catch (IllegalArgumentException e) {
      throw new CookieIntegrityException(name, CookieIntegrityException.Reason.MALFORMED, e);
    }

    if (raw.length < NONCE_BYTES + GCM_TAG_BYTES) {
      throw new CookieIntegrityException(name, CookieIntegrityException.Reason.MALFORMED);
    }

    byte[] nonce = Arrays.copyOfRange(raw, 0, NONCE_BYTES);
    byte[] ciphertextWithTag = Arrays.copyOfRange(raw, NONCE_BYTES, raw.length);
    byte[] aad = name.getBytes(StandardCharsets.UTF_8);
    AEADBadTagException last = null;
    for (SecretKey key : keys) {
      try {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return new String(cipher.doFinal(ciphertextWithTag), StandardCharsets.UTF_8);
      } catch (AEADBadTagException e) {
        last = e;
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }

    throw new CookieIntegrityException(name, CookieIntegrityException.Reason.DECRYPT_FAILED, last);
  }

  public static String encrypt(SecretKey primaryKey, String name, String value) {
    byte[] nonce = new byte[NONCE_BYTES];
    RNG.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, primaryKey, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
      byte[] ciphertextWithTag = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      byte[] wire = new byte[NONCE_BYTES + ciphertextWithTag.length];
      System.arraycopy(nonce, 0, wire, 0, NONCE_BYTES);
      System.arraycopy(ciphertextWithTag, 0, wire, NONCE_BYTES, ciphertextWithTag.length);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(wire);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
