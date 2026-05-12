/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.middleware;

import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

public class CSPTest {
  public static final String DEFAULT = "default-src 'self'; style-src 'self' https://fonts.googleapis.com; " +
      "font-src 'self' https://fonts.gstatic.com; object-src 'none'; base-uri 'self'; " +
      "frame-ancestors 'none'; form-action 'self'; upgrade-insecure-requests";

  @Test
  public void addDirective_appendsToExisting() {
    String out = CSP.empty()
                    .directive("style-src", "'self'")
                    .addDirective("style-src", "https://cdn.example.com")
                    .build();
    assertEquals(out, "style-src 'self' https://cdn.example.com");
  }

  @Test
  public void addDirective_createsDirectiveIfAbsent() {
    assertEquals(CSP.empty().addDirective("script-src", "'self'").build(), "script-src 'self'");
  }

  @Test
  public void addDirective_isIdempotent() {
    String out = CSP.empty()
                    .directive("style-src", "'self'")
                    .addDirective("style-src", "'self'")
                    .addDirective("style-src", "https://x", "https://x")
                    .build();
    assertEquals(out, "style-src 'self' https://x");
  }

  @Test
  public void addDirective_promotesFlagToSourceList() {
    String out = CSP.empty()
                    .directive("script-src")
                    .addDirective("script-src", "'self'")
                    .build();
    assertEquals(out, "script-src 'self'");
  }

  @Test
  public void constants_areQuoted() {
    assertEquals(CSP.NONE, "'none'");
    assertEquals(CSP.SELF, "'self'");
    assertEquals(CSP.STRICT_DYNAMIC, "'strict-dynamic'");
    assertEquals(CSP.UNSAFE_EVAL, "'unsafe-eval'");
    assertEquals(CSP.UNSAFE_HASHES, "'unsafe-hashes'");
    assertEquals(CSP.UNSAFE_INLINE, "'unsafe-inline'");
    assertEquals(CSP.WASM_UNSAFE_EVAL, "'wasm-unsafe-eval'");
    assertEquals(CSP.REPORT_SAMPLE, "'report-sample'");
    assertEquals(CSP.INLINE_SPECULATION_RULES, "'inline-speculation-rules'");
  }

  @Test
  public void defaults_matchesCurrentSecurityHeadersDefaultString() {
    assertEquals(CSP.defaults().build(), DEFAULT);
  }

  @Test
  public void defaults_returnsFreshInstance() {
    CSP a = CSP.defaults();
    CSP b = CSP.defaults();
    a.addStyleSrc("https://x");
    assertEquals(b.build(), DEFAULT);
  }

  @Test
  public void directive_multipleDirectivesJoinedWithSemicolonSpace() {
    String out = CSP.empty()
                    .directive("default-src", "'self'")
                    .directive("img-src", "'self'", "data:")
                    .build();
    assertEquals(out, "default-src 'self'; img-src 'self' data:");
  }

  @Test
  public void directive_noValuesRendersFlagDirective() {
    assertEquals(CSP.empty().directive("upgrade-insecure-requests").build(),
        "upgrade-insecure-requests");
  }

  @Test
  public void directive_replaceKeepsInsertionSlot() {
    String out = CSP.empty()
                    .directive("default-src", "'self'")
                    .directive("img-src", "'self'")
                    .directive("default-src", "'none'")
                    .build();
    assertEquals(out, "default-src 'none'; img-src 'self'");
  }

  @Test
  public void directive_setsSingleSourceListDirective() {
    assertEquals(CSP.empty().directive("script-src", "'self'", "https://x").build(),
        "script-src 'self' https://x");
  }

  @Test
  public void empty_buildsEmptyString() {
    assertEquals(CSP.empty().build(), "");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void nonce_rejectsEmpty() {
    CSP.nonce("");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void nonce_rejectsNull() {
    CSP.nonce(null);
  }

  @Test
  public void nonce_wrapsInQuotesAndPrefix() {
    assertEquals(CSP.nonce("abc123"), "'nonce-abc123'");
  }

  @Test
  public void removeDirective_emptyingDropsDirective() {
    String out = CSP.empty()
                    .directive("default-src", "'self'")
                    .directive("script-src", "'self'")
                    .removeDirective("script-src", "'self'")
                    .build();
    assertEquals(out, "default-src 'self'");
  }

  @Test
  public void removeDirective_immuneToFlagDirectives() {
    String out = CSP.empty()
                    .directive("upgrade-insecure-requests")
                    .removeDirective("upgrade-insecure-requests", "anything")
                    .build();
    assertEquals(out, "upgrade-insecure-requests");
  }

  @Test
  public void removeDirective_noOpIfDirectiveAbsent() {
    String out = CSP.empty()
                    .directive("default-src", "'self'")
                    .removeDirective("script-src", "'self'")
                    .build();
    assertEquals(out, "default-src 'self'");
  }

  @Test
  public void removeDirective_removesOnlyNamedValues() {
    String out = CSP.empty()
                    .directive("style-src", "'self'", "https://a", "https://b")
                    .removeDirective("style-src", "https://a")
                    .build();
    assertEquals(out, "style-src 'self' https://b");
  }

  @Test
  public void remove_dropsEntireDirective() {
    String out = CSP.empty()
                    .directive("default-src", "'self'")
                    .directive("upgrade-insecure-requests")
                    .remove("upgrade-insecure-requests")
                    .build();
    assertEquals(out, "default-src 'self'");
  }

  @Test
  public void remove_noOpIfAbsent() {
    String out = CSP.empty()
                    .directive("default-src", "'self'")
                    .remove("script-src")
                    .build();
    assertEquals(out, "default-src 'self'");
  }

  @Test
  public void reportTo_singleGroupName() {
    assertEquals(CSP.empty().reportTo("csp-endpoint").build(), "report-to csp-endpoint");
  }

  @Test
  public void reportUri_replaceWithMultipleURIs() {
    assertEquals(
        CSP.empty().reportUri("https://a.example/r", "https://b.example/r").build(),
        "report-uri https://a.example/r https://b.example/r");
  }

  @Test
  public void requireTrustedTypesFor_emitsTokens() {
    assertEquals(
        CSP.empty().requireTrustedTypesFor("'script'").build(),
        "require-trusted-types-for 'script'");
  }

  @Test
  public void sandbox_noArgEnablesEmptyTokenList() {
    assertEquals(CSP.empty().sandbox().build(), "sandbox");
  }

  @Test
  public void sandbox_replaceAddRemove() {
    String out = CSP.empty()
                    .sandbox("allow-forms", "allow-scripts")
                    .addSandbox("allow-same-origin")
                    .removeSandbox("allow-forms")
                    .build();
    assertEquals(out, "sandbox allow-scripts allow-same-origin");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void sha256_rejectsEmpty() {
    CSP.sha256("");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void sha256_rejectsNull() {
    CSP.sha256(null);
  }

  @Test
  public void sha256_wrapsInQuotesAndPrefix() {
    assertEquals(CSP.sha256("aZkLp="), "'sha256-aZkLp='");
  }

  @Test
  public void sha384_wrapsInQuotesAndPrefix() {
    assertEquals(CSP.sha384("abc="), "'sha384-abc='");
  }

  @Test
  public void sha512_wrapsInQuotesAndPrefix() {
    assertEquals(CSP.sha512("xyz="), "'sha512-xyz='");
  }

  @Test
  public void trustedTypes_emitsPolicyNames() {
    assertEquals(
        CSP.empty().trustedTypes("default", "myPolicy").build(),
        "trusted-types default myPolicy");
  }

  @Test
  public void typedAdd_appendsToDirective() {
    assertEquals(
        CSP.empty().styleSrc(CSP.SELF).addStyleSrc("https://cdn.example.com").build(),
        "style-src 'self' https://cdn.example.com");
    assertEquals(
        CSP.empty().addScriptSrc(CSP.SELF, CSP.nonce("abc")).build(),
        "script-src 'self' 'nonce-abc'");
  }

  @Test
  public void typedRemove_removesFromDirective() {
    assertEquals(
        CSP.empty()
           .styleSrc(CSP.SELF, "https://a", "https://b")
           .removeStyleSrc("https://a")
           .build(),
        "style-src 'self' https://b");
  }

  @Test
  public void typedReplace_mapsToDirectiveName() {
    assertEquals(CSP.empty().baseUri(CSP.SELF).build(), "base-uri 'self'");
    assertEquals(CSP.empty().childSrc(CSP.SELF).build(), "child-src 'self'");
    assertEquals(CSP.empty().connectSrc(CSP.SELF).build(), "connect-src 'self'");
    assertEquals(CSP.empty().defaultSrc(CSP.SELF).build(), "default-src 'self'");
    assertEquals(CSP.empty().fontSrc(CSP.SELF).build(), "font-src 'self'");
    assertEquals(CSP.empty().formAction(CSP.SELF).build(), "form-action 'self'");
    assertEquals(CSP.empty().frameAncestors(CSP.NONE).build(), "frame-ancestors 'none'");
    assertEquals(CSP.empty().frameSrc(CSP.SELF).build(), "frame-src 'self'");
    assertEquals(CSP.empty().imgSrc(CSP.SELF).build(), "img-src 'self'");
    assertEquals(CSP.empty().manifestSrc(CSP.SELF).build(), "manifest-src 'self'");
    assertEquals(CSP.empty().mediaSrc(CSP.SELF).build(), "media-src 'self'");
    assertEquals(CSP.empty().objectSrc(CSP.NONE).build(), "object-src 'none'");
    assertEquals(CSP.empty().scriptSrc(CSP.SELF).build(), "script-src 'self'");
    assertEquals(CSP.empty().styleSrc(CSP.SELF).build(), "style-src 'self'");
    assertEquals(CSP.empty().workerSrc(CSP.SELF).build(), "worker-src 'self'");
  }

  @Test
  public void upgradeInsecureRequests_enables() {
    assertEquals(CSP.empty().upgradeInsecureRequests().build(), "upgrade-insecure-requests");
  }

  @Test
  public void upgradeInsecureRequests_trueAddsFalseRemoves() {
    CSP csp = CSP.empty().upgradeInsecureRequests(true);
    assertEquals(csp.build(), "upgrade-insecure-requests");
    csp.upgradeInsecureRequests(false);
    assertEquals(csp.build(), "");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void validation_addDirectiveValidates() {
    CSP.empty().addDirective("script-src", "a b");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void validation_rejectsEmptyDirectiveName() {
    CSP.empty().directive("", "'self'");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void validation_rejectsEmptyValue() {
    CSP.empty().directive("script-src", "");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void validation_rejectsLeadingHyphenDirectiveName() {
    CSP.empty().directive("-foo", "'self'");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void validation_rejectsSemicolonInDirectiveName() {
    CSP.empty().directive("a;b", "'self'");
  }

  @Test(expectedExceptions = IllegalArgumentException.class,
      expectedExceptionsMessageRegExp = ".*\\[a;b\\].*")
  public void validation_rejectsSemicolonInValue() {
    CSP.empty().directive("script-src", "a;b");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void validation_rejectsTabInValue() {
    CSP.empty().directive("script-src", "a\tb");
  }

  @Test(expectedExceptions = IllegalArgumentException.class,
      expectedExceptionsMessageRegExp = ".*\\[Script-Src\\].*")
  public void validation_rejectsUppercaseDirectiveName() {
    CSP.empty().directive("Script-Src", "'self'");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void validation_rejectsWhitespaceInDirectiveName() {
    CSP.empty().directive("a b", "'self'");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void validation_rejectsWhitespaceInValue() {
    CSP.empty().directive("script-src", "a b");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void validation_removeDirectiveValidatesValues() {
    CSP.empty().directive("script-src", "'self'").removeDirective("script-src", "a b");
  }
}
