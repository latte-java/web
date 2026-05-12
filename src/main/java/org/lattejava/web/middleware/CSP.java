/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.middleware;

import module java.base;

/**
 * A fluent builder for {@code Content-Security-Policy} header values. Pair with
 * {@link SecurityHeaders.Builder#contentSecurityPolicy(CSP)} to plug the result into the security-headers middleware.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class CSP {
  public static final String INLINE_SPECULATION_RULES = "'inline-speculation-rules'";
  public static final String NONE = "'none'";
  public static final String REPORT_SAMPLE = "'report-sample'";
  public static final String SELF = "'self'";
  public static final String STRICT_DYNAMIC = "'strict-dynamic'";
  public static final String UNSAFE_EVAL = "'unsafe-eval'";
  public static final String UNSAFE_HASHES = "'unsafe-hashes'";
  public static final String UNSAFE_INLINE = "'unsafe-inline'";
  public static final String WASM_UNSAFE_EVAL = "'wasm-unsafe-eval'";
  private static final Pattern DIRECTIVE_NAME = Pattern.compile("[a-z][a-z0-9-]*");
  private final LinkedHashMap<String, List<String>> directives = new LinkedHashMap<>();

  private CSP() {
  }

  /**
   * @return A new CSP builder pre-populated to match the default policy that {@link SecurityHeaders} emits. Each call
   *     returns a fresh instance; mutating it does not affect other callers.
   */
  public static CSP defaults() {
    return new CSP()
        .defaultSrc(SELF)
        .styleSrc(SELF, "https://fonts.googleapis.com")
        .fontSrc(SELF, "https://fonts.gstatic.com")
        .objectSrc(NONE)
        .baseUri(SELF)
        .frameAncestors(NONE)
        .formAction(SELF)
        .upgradeInsecureRequests();
  }

  /**
   * @return A new empty CSP builder. Building it returns the empty string.
   */
  public static CSP empty() {
    return new CSP();
  }

  /**
   * @param value The nonce value (the random per-response token). Must be non-null and non-empty.
   * @return The source expression for the nonce, e.g. {@code 'nonce-abc123'}.
   */
  public static String nonce(String value) {
    requireNonEmpty(value, "nonce value");
    return "'nonce-" + value + "'";
  }

  /**
   * @param base64Digest The base64-encoded SHA-256 digest of the inline block's bytes.
   * @return The source expression for the hash, e.g. {@code 'sha256-aZkLp='}.
   */
  public static String sha256(String base64Digest) {
    requireNonEmpty(base64Digest, "sha256 digest");
    return "'sha256-" + base64Digest + "'";
  }

  /**
   * @param base64Digest The base64-encoded SHA-384 digest of the inline block's bytes.
   * @return The source expression for the hash, e.g. {@code 'sha384-aZkLp='}.
   */
  public static String sha384(String base64Digest) {
    requireNonEmpty(base64Digest, "sha384 digest");
    return "'sha384-" + base64Digest + "'";
  }

  /**
   * @param base64Digest The base64-encoded SHA-512 digest of the inline block's bytes.
   * @return The source expression for the hash, e.g. {@code 'sha512-aZkLp='}.
   */
  public static String sha512(String base64Digest) {
    requireNonEmpty(base64Digest, "sha512 digest");
    return "'sha512-" + base64Digest + "'";
  }

  private static void requireNonEmpty(String value, String label) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("CSP " + label + " must be non-null and non-empty: [" + value + "]");
    }
  }

  private static void validateDirectiveName(String name) {
    if (name == null || !DIRECTIVE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("CSP directive name is invalid: [" + name + "]");
    }
  }

  private static void validateValue(String value) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("CSP source value must be non-empty: [" + value + "]");
    }

    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == ';' || Character.isWhitespace(c)) {
        throw new IllegalArgumentException("CSP source value must not contain [;] or whitespace: [" + value + "]");
      }
    }
  }

  public CSP addBaseUri(String... sources) {
    return addDirective("base-uri", sources);
  }

  public CSP addChildSrc(String... sources) {
    return addDirective("child-src", sources);
  }

  public CSP addConnectSrc(String... sources) {
    return addDirective("connect-src", sources);
  }

  public CSP addDefaultSrc(String... sources) {
    return addDirective("default-src", sources);
  }

  /**
   * Appends source values to the named directive. Creates the directive if absent. Values already present in the
   * directive are not added again (case-sensitive).
   *
   * @param name   The directive name.
   * @param values The source values to append.
   * @return This builder.
   */
  public CSP addDirective(String name, String... values) {
    validateDirectiveName(name);
    for (String v : values) {
      validateValue(v);
    }

    List<String> list = directives.computeIfAbsent(name, _ -> new ArrayList<>());
    for (String v : values) {
      if (!list.contains(v)) {
        list.add(v);
      }
    }

    return this;
  }

  public CSP addFontSrc(String... sources) {
    return addDirective("font-src", sources);
  }

  public CSP addFormAction(String... sources) {
    return addDirective("form-action", sources);
  }

  public CSP addFrameAncestors(String... sources) {
    return addDirective("frame-ancestors", sources);
  }

  public CSP addFrameSrc(String... sources) {
    return addDirective("frame-src", sources);
  }

  public CSP addImgSrc(String... sources) {
    return addDirective("img-src", sources);
  }

  public CSP addManifestSrc(String... sources) {
    return addDirective("manifest-src", sources);
  }

  public CSP addMediaSrc(String... sources) {
    return addDirective("media-src", sources);
  }

  public CSP addObjectSrc(String... sources) {
    return addDirective("object-src", sources);
  }

  public CSP addSandbox(String... tokens) {
    return addDirective("sandbox", tokens);
  }

  public CSP addScriptSrc(String... sources) {
    return addDirective("script-src", sources);
  }

  public CSP addStyleSrc(String... sources) {
    return addDirective("style-src", sources);
  }

  public CSP addWorkerSrc(String... sources) {
    return addDirective("worker-src", sources);
  }

  public CSP baseUri(String... sources) {
    return directive("base-uri", sources);
  }

  /**
   * @return The CSP header value rendered from the current directives, in insertion order. Returns the empty string
   *     when no directives are present.
   */
  public String build() {
    if (directives.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, List<String>> e : directives.entrySet()) {
      if (!first) {
        sb.append("; ");
      }
      sb.append(e.getKey());
      for (String v : e.getValue()) {
        sb.append(' ').append(v);
      }
      first = false;
    }
    return sb.toString();
  }

  public CSP childSrc(String... sources) {
    return directive("child-src", sources);
  }

  public CSP connectSrc(String... sources) {
    return directive("connect-src", sources);
  }

  public CSP defaultSrc(String... sources) {
    return directive("default-src", sources);
  }

  /**
   * Replaces the named directive with the given source values. If {@code values} is empty, the directive becomes a flag
   * directive (rendered as just its name, e.g. {@code upgrade-insecure-requests}).
   *
   * @param name   The directive name (e.g. {@code script-src}).
   * @param values The source values to set (e.g. {@code 'self'}, {@code https://example.com}). Pass none for a flag
   *               directive.
   * @return This builder.
   */
  public CSP directive(String name, String... values) {
    validateDirectiveName(name);
    for (String v : values) {
      validateValue(v);
    }

    List<String> list = new ArrayList<>(Arrays.asList(values));
    directives.put(name, list);
    return this;
  }

  public CSP fontSrc(String... sources) {
    return directive("font-src", sources);
  }

  public CSP formAction(String... sources) {
    return directive("form-action", sources);
  }

  public CSP frameAncestors(String... sources) {
    return directive("frame-ancestors", sources);
  }

  public CSP frameSrc(String... sources) {
    return directive("frame-src", sources);
  }

  public CSP imgSrc(String... sources) {
    return directive("img-src", sources);
  }

  public CSP manifestSrc(String... sources) {
    return directive("manifest-src", sources);
  }

  public CSP mediaSrc(String... sources) {
    return directive("media-src", sources);
  }

  public CSP objectSrc(String... sources) {
    return directive("object-src", sources);
  }

  /**
   * Removes the named directive from the policy. No-op if the directive is not present.
   *
   * @param name The directive name.
   * @return This builder.
   */
  public CSP remove(String name) {
    directives.remove(name);
    return this;
  }

  public CSP removeBaseUri(String... sources) {
    return removeDirective("base-uri", sources);
  }

  public CSP removeChildSrc(String... sources) {
    return removeDirective("child-src", sources);
  }

  public CSP removeConnectSrc(String... sources) {
    return removeDirective("connect-src", sources);
  }

  public CSP removeDefaultSrc(String... sources) {
    return removeDirective("default-src", sources);
  }

  /**
   * Removes the given source values from the named directive. No-op if the directive is absent or is a flag directive.
   * Values not present in the directive are silently skipped. If the directive was non-empty before this call and is
   * empty after, it is dropped from the policy (empty source-list directives are meaningless). Flag directives (empty
   * value lists) are immune to this method; use {@link #remove(String)} to drop them.
   *
   * @param name   The directive name.
   * @param values The source values to remove.
   * @return This builder.
   */
  public CSP removeDirective(String name, String... values) {
    validateDirectiveName(name);
    for (String v : values) {
      validateValue(v);
    }

    List<String> list = directives.get(name);
    if (list == null || list.isEmpty()) {
      return this;
    }

    for (String v : values) {
      list.remove(v);
    }

    if (list.isEmpty()) {
      directives.remove(name);
    }

    return this;
  }

  public CSP removeFontSrc(String... sources) {
    return removeDirective("font-src", sources);
  }

  public CSP removeFormAction(String... sources) {
    return removeDirective("form-action", sources);
  }

  public CSP removeFrameAncestors(String... sources) {
    return removeDirective("frame-ancestors", sources);
  }

  public CSP removeFrameSrc(String... sources) {
    return removeDirective("frame-src", sources);
  }

  public CSP removeImgSrc(String... sources) {
    return removeDirective("img-src", sources);
  }

  public CSP removeManifestSrc(String... sources) {
    return removeDirective("manifest-src", sources);
  }

  public CSP removeMediaSrc(String... sources) {
    return removeDirective("media-src", sources);
  }

  public CSP removeObjectSrc(String... sources) {
    return removeDirective("object-src", sources);
  }

  public CSP removeSandbox(String... tokens) {
    return removeDirective("sandbox", tokens);
  }

  public CSP removeScriptSrc(String... sources) {
    return removeDirective("script-src", sources);
  }

  public CSP removeStyleSrc(String... sources) {
    return removeDirective("style-src", sources);
  }

  public CSP removeWorkerSrc(String... sources) {
    return removeDirective("worker-src", sources);
  }

  public CSP reportTo(String groupName) {
    return directive("report-to", groupName);
  }

  public CSP reportUri(String... uris) {
    return directive("report-uri", uris);
  }

  public CSP requireTrustedTypesFor(String... sinks) {
    return directive("require-trusted-types-for", sinks);
  }

  public CSP sandbox(String... tokens) {
    return directive("sandbox", tokens);
  }

  public CSP scriptSrc(String... sources) {
    return directive("script-src", sources);
  }

  public CSP styleSrc(String... sources) {
    return directive("style-src", sources);
  }

  public CSP trustedTypes(String... policies) {
    return directive("trusted-types", policies);
  }

  public CSP upgradeInsecureRequests() {
    return directive("upgrade-insecure-requests");
  }

  public CSP upgradeInsecureRequests(boolean on) {
    if (on) {
      return directive("upgrade-insecure-requests");
    }
    return remove("upgrade-insecure-requests");
  }

  public CSP workerSrc(String... sources) {
    return directive("worker-src", sources);
  }
}
