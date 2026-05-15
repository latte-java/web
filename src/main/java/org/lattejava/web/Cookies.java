/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module java.base;
import module org.lattejava.http;

import org.lattejava.web.internal.*;

/**
 * Application-facing helper for reading, writing, and clearing cookies with secure-by-default attributes and optional
 * AES-256-GCM authenticated encryption. Build once at application startup via {@link #newInstance()} or
 * {@link #encryptionKeys(byte[]...)} and reuse across requests and threads.
 *
 * @author Brian Pontarelli
 */
public final class Cookies {
  private final List<SecretKey> encryptionKeys;

  private Cookies(List<SecretKey> encryptionKeys) {
    this.encryptionKeys = encryptionKeys;
  }

  public static Cookies encryptionKeys(byte[]... keys) {
    if (keys.length == 0) {
      throw new IllegalArgumentException("At least one encryption key is required; use Cookies.newInstance() for the no-encryption case");
    }
    List<SecretKey> list = new ArrayList<>(keys.length);
    for (byte[] k : keys) {
      if (k.length != 32) {
        throw new IllegalArgumentException("Encryption key must be 32 bytes for AES-256: [" + k.length + "]");
      }
      list.add(new SecretKeySpec(k, "AES"));
    }
    return new Cookies(List.copyOf(list));
  }

  public static Cookies encryptionKeys(SecretKey... keys) {
    return encryptionKeys(Arrays.asList(keys));
  }

  public static Cookies encryptionKeys(List<SecretKey> keys) {
    if (keys.isEmpty()) {
      throw new IllegalArgumentException("At least one encryption key is required; use Cookies.newInstance() for the no-encryption case");
    }
    for (SecretKey k : keys) {
      if (!"AES".equals(k.getAlgorithm())) {
        throw new IllegalArgumentException("Encryption key algorithm must be [AES]: [" + k.getAlgorithm() + "]");
      }
      byte[] encoded = k.getEncoded();
      if (encoded == null || encoded.length != 32) {
        throw new IllegalArgumentException("Encryption key must be 32 bytes for AES-256: [" + (encoded == null ? "null" : encoded.length) + "]");
      }
    }
    return new Cookies(List.copyOf(keys));
  }

  public static Cookies newInstance() {
    return new Cookies(List.of());
  }

  static boolean isSecureScheme(HTTPRequest req) {
    return "https".equalsIgnoreCase(req.getScheme())
        || "https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto"));
  }

  public ClearBuilder clear(String name) {
    return new ClearBuilder(name);
  }

  public ReadBuilder read(String name) {
    return new ReadBuilder(this, name);
  }

  public PlainWriteBuilder write(String name, String value) {
    return new PlainWriteBuilder(this, name, value);
  }

  public static final class ClearBuilder {
    private final String name;
    private String domain;
    private String path = "/";

    ClearBuilder(String name) {
      this.name = name;
    }

    public ClearBuilder domain(String d) {
      this.domain = d;
      return this;
    }

    public void from(HTTPRequest req, HTTPResponse res) {
      Cookie c = new Cookie(name, "");
      c.setPath(path);
      if (domain != null) {
        c.setDomain(domain);
      }
      c.setHttpOnly(true);
      c.setSameSite(Cookie.SameSite.Strict);
      c.setSecure(isSecureScheme(req));
      c.setMaxAge(0L);
      res.addCookie(c);
    }

    public ClearBuilder path(String p) {
      this.path = p;
      return this;
    }
  }

  public static final class EncryptedReadBuilder extends ReadBuilder {
    EncryptedReadBuilder(Cookies cookies, String name) {
      super(cookies, name);
    }

    @Override
    public EncryptedReadBuilder encrypted() {
      return this;
    }

    @Override
    public String from(HTTPRequest req) {
      Cookie c = req.getCookie(name);
      if (c == null) {
        return null;
      }
      return CookieCrypto.decrypt(cookies.encryptionKeys, name, c.value);
    }
  }

  public static final class EncryptedWriteBuilder extends WriteBuilder<EncryptedWriteBuilder> {
    EncryptedWriteBuilder(WriteBuilder<?> src) {
      super(src);
    }

    @Override
    public EncryptedWriteBuilder encrypted() {
      return this;
    }

    @Override
    public void to(HTTPRequest req, HTTPResponse res) {
      String wire = CookieCrypto.encrypt(cookies.encryptionKeys.getFirst(), name, value);
      res.addCookie(buildCookie(req, wire));
    }

    @Override
    protected EncryptedWriteBuilder self() {
      return this;
    }
  }

  public static final class PlainWriteBuilder extends WriteBuilder<PlainWriteBuilder> {
    PlainWriteBuilder(Cookies cookies, String name, String value) {
      super(cookies, name, value);
    }

    @Override
    public void to(HTTPRequest req, HTTPResponse res) {
      res.addCookie(buildCookie(req, value));
    }

    @Override
    protected PlainWriteBuilder self() {
      return this;
    }
  }

  public static class ReadBuilder {
    final Cookies cookies;
    final String name;

    ReadBuilder(Cookies cookies, String name) {
      this.cookies = cookies;
      this.name = name;
    }

    public EncryptedReadBuilder encrypted() {
      if (cookies.encryptionKeys.isEmpty()) {
        throw new IllegalStateException("Cookies helper was not configured with encryption keys");
      }
      return new EncryptedReadBuilder(cookies, name);
    }

    public String from(HTTPRequest req) {
      Cookie c = req.getCookie(name);
      return c != null ? c.value : null;
    }
  }

  public static abstract class WriteBuilder<T extends WriteBuilder<T>> {
    final Cookies cookies;
    final String name;
    final String value;
    String domain;
    boolean httpOnly = true;
    Duration maxAge;
    String path = "/";
    Cookie.SameSite sameSite = Cookie.SameSite.Strict;
    Boolean secure;

    WriteBuilder(Cookies cookies, String name, String value) {
      this.cookies = cookies;
      this.name = name;
      this.value = value;
    }

    WriteBuilder(WriteBuilder<?> src) {
      this.cookies = src.cookies;
      this.name = src.name;
      this.value = src.value;
      this.domain = src.domain;
      this.httpOnly = src.httpOnly;
      this.maxAge = src.maxAge;
      this.path = src.path;
      this.sameSite = src.sameSite;
      this.secure = src.secure;
    }

    public T domain(String d) {
      this.domain = d;
      return self();
    }

    public EncryptedWriteBuilder encrypted() {
      if (cookies.encryptionKeys.isEmpty()) {
        throw new IllegalStateException("Cookies helper was not configured with encryption keys");
      }
      return new EncryptedWriteBuilder(this);
    }

    public T httpOnly(boolean b) {
      this.httpOnly = b;
      return self();
    }

    public T maxAge(Duration d) {
      this.maxAge = d;
      return self();
    }

    public T path(String p) {
      this.path = p;
      return self();
    }

    public T sameSite(Cookie.SameSite s) {
      this.sameSite = s;
      return self();
    }

    public T secure(boolean b) {
      this.secure = b;
      return self();
    }

    public abstract void to(HTTPRequest req, HTTPResponse res);

    Cookie buildCookie(HTTPRequest req, String wireValue) {
      Cookie c = new Cookie(name, wireValue);
      c.setPath(path);
      if (domain != null) {
        c.setDomain(domain);
      }
      c.setHttpOnly(httpOnly);
      c.setSameSite(sameSite);
      c.setSecure(secure != null ? secure : isSecureScheme(req));
      if (maxAge != null) {
        c.setMaxAge(maxAge.toSeconds());
      }
      return c;
    }

    protected abstract T self();
  }
}
