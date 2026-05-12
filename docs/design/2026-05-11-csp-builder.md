# CSP builder

## Purpose

A fluent builder for `Content-Security-Policy` header values. It starts from the same default set that `SecurityHeaders` currently emits, and lets callers add to, remove from, or fully replace any directive. The result is a plain CSP string that plugs into `SecurityHeaders.Builder.contentSecurityPolicy(...)` so existing middleware behavior (including the localhost `upgrade-insecure-requests` stripping) is unchanged.

## Placement and naming

- Package: `org.lattejava.web.middleware`
- Class: `CSP` (uppercase acronym per the project's naming rule)
- Sibling to `SecurityHeaders`. The two are co-located because `SecurityHeaders` is the primary consumer; `CSP` itself has no dependency on `SecurityHeaders` and could be used to produce a header string independently.

## Integration with `SecurityHeaders`

One small change to `SecurityHeaders.Builder`: a new overload

```java
public Builder contentSecurityPolicy(CSP csp) {
  this.contentSecurityPolicy = csp == null ? null : csp.build();
  return this;
}
```

The existing `contentSecurityPolicy(String)` setter, the field, the `null`-suppression behavior, and the request-time localhost stripping of `upgrade-insecure-requests` all stay exactly as they are. `CSP` produces a static string; per-request behavior remains a `SecurityHeaders` concern.

Canonical call site:

```java
web.install(SecurityHeaders.builder()
    .contentSecurityPolicy(CSP.defaults()
                              .addStyleSrc("https://cdn.example.com")
                              .removeFontSrc("https://fonts.gstatic.com")
                              .scriptSrc(CSP.SELF, CSP.nonce("abc123")))
    .build());
```

## API

### Entry points

```java
public static CSP empty();    // empty builder, build() returns ""
public static CSP defaults(); // pre-populated to match today's SecurityHeaders default
```

Each call returns a fresh instance. `CSP.defaults().build()` is byte-for-byte identical to the current default string in `SecurityHeaders.Builder.contentSecurityPolicy` — that's the regression contract.

### Source-keyword constants

Public `static final String` fields on `CSP`. Each constant is the spec-correct quoted form so callers can't forget the single quotes:

```java
public static final String INLINE_SPECULATION_RULES = "'inline-speculation-rules'";
public static final String NONE                     = "'none'";
public static final String REPORT_SAMPLE            = "'report-sample'";
public static final String SELF                     = "'self'";
public static final String STRICT_DYNAMIC           = "'strict-dynamic'";
public static final String UNSAFE_EVAL              = "'unsafe-eval'";
public static final String UNSAFE_HASHES            = "'unsafe-hashes'";
public static final String UNSAFE_INLINE            = "'unsafe-inline'";
public static final String WASM_UNSAFE_EVAL         = "'wasm-unsafe-eval'";
```

Hosts and schemes (`https://example.com`, `https:`, `data:`, `wss:`) are passed as raw strings.

### Static helpers for nonces and hashes

```java
public static String nonce(String value);   // "'nonce-<value>'"
public static String sha256(String b64);    // "'sha256-<b64>'"
public static String sha384(String b64);    // "'sha384-<b64>'"
public static String sha512(String b64);    // "'sha512-<b64>'"
```

These produce the quoted source-expression form ready to drop into a directive call.

### Typed directive methods — source-list family

For each of the 15 source-list directives — `default-src`, `script-src`, `style-src`, `img-src`, `font-src`, `connect-src`, `media-src`, `object-src`, `frame-src`, `child-src`, `worker-src`, `manifest-src`, `base-uri`, `form-action`, `frame-ancestors` — three methods:

```java
public CSP defaultSrc(String... sources);     // replace the entire directive's value list
public CSP addDefaultSrc(String... sources);  // append; de-dup within the directive
public CSP removeDefaultSrc(String... sources); // remove specific values
```

Naming pattern: kebab-case directive becomes camelCase method (`base-uri` → `baseUri`, `form-action` → `formAction`).

Behavior:

- The replace setter creates the directive if absent.
- The add setter creates the directive if absent.
- The remove setter is a no-op if the directive is absent or any specific value is absent.
- If `remove<Name>` empties a directive (no values left), the directive is dropped from the output. An empty source-list directive is meaningless in CSP and would render incorrectly.

### Typed directive methods — non-source-list family

```java
public CSP upgradeInsecureRequests();           // enable (flag directive)
public CSP upgradeInsecureRequests(boolean on); // false drops it
public CSP sandbox();                           // enable with empty token list
public CSP sandbox(String... tokens);           // replace token list
public CSP addSandbox(String... tokens);
public CSP removeSandbox(String... tokens);
public CSP reportUri(String... uris);
public CSP reportTo(String groupName);
public CSP requireTrustedTypesFor(String... sinks);
public CSP trustedTypes(String... policies);
```

`reportTo` takes a single group name because that's the spec shape. The rest behave like the source-list family.

### Directive-level remove and generic escape hatch

```java
public CSP remove(String directive);                              // drop a directive entirely

public CSP directive(String name, String... values);              // replace
public CSP addDirective(String name, String... values);
public CSP removeDirective(String name, String... values);        // remove specific values
```

The typed methods are thin wrappers over the generic ones. There is one underlying `LinkedHashMap<String, List<String>>` and one set of mutation primitives.

### `build()`

```java
public String build();
```

Renders directives in insertion order, separated by `"; "`. Within a directive, the name is followed by space-separated values; flag directives emit just their name. Empty source-list directives are skipped (see above). `CSP.empty().build()` returns `""`.

`build()` does not freeze the instance; subsequent mutations and `build()` calls work and reflect the current state. This matches the existing `SecurityHeaders.Builder` pattern.

## Internals

- `LinkedHashMap<String, List<String>>` keyed by lowercase directive name. Insertion order = output order.
- A flag directive (e.g. `upgrade-insecure-requests`) is stored as a key with an empty `List`. `build()` emits just the name with no trailing space.
- Adding a value to a directive that doesn't exist creates it; adding to an existing directive appends without duplicating an existing value (case-sensitive comparison — CSP source values are case-sensitive for hashes and nonces).
- The class is not thread-safe. Construct it on one thread, hand the built string to `SecurityHeaders`.

## Validation

Lightweight structural validation, eager at the setter (not deferred to `build()`):

- Directive name (for the generic API) must match `[a-z][a-z0-9-]*` — reject empty, uppercase, leading hyphen, or any character outside that set. Typed methods bypass this since they hard-code valid names.
- Each source value must be non-empty.
- Each source value must not contain `;` or any ASCII whitespace (those are CSP separators and would corrupt the rendered header).
- `nonce()`, `sha256()`, `sha384()`, `sha512()` reject `null` and empty input.
- No semantic validation of CSP source grammar — hosts, schemes, hash payloads are caller's responsibility.
- All errors thrown as `IllegalArgumentException` with messages following the project's `[value]` bracket convention.

## Tests

New `CSPTest` in `org.lattejava.web.tests.middleware`:

- `defaults_matchesCurrentDefaultString` — `CSP.defaults().build()` is byte-for-byte equal to the existing default literal in `SecurityHeaders.Builder`.
- `empty_buildsEmptyString` — `CSP.empty().build()` returns `""`.
- `replace_setsDirective` — `scriptSrc(SELF, "https://x")` builds to `"script-src 'self' https://x"`.
- `add_appendsToDirective` — starting from `defaults()`, `addStyleSrc("https://cdn.example.com")` appends only inside `style-src` and leaves other directives unchanged.
- `add_isIdempotent` — adding the same value twice stores it once.
- `add_createsDirectiveIfAbsent` — `addScriptSrc(SELF)` on an `empty()` builder produces `"script-src 'self'"`.
- `remove_value_removesOnlyThatValue` — remove `https://fonts.googleapis.com` from default `style-src`; the rest of the directive and the rest of the policy survive.
- `remove_emptyingDirectiveDropsIt` — removing the last value of a source-list directive removes the directive from output.
- `remove_directive_dropsEntireDirective` — `.remove("upgrade-insecure-requests")` removes it.
- `upgradeInsecureRequests_toggle` — `upgradeInsecureRequests(false)` on a builder that has it removes it from output; `upgradeInsecureRequests(true)` re-adds it.
- `insertionOrderPreserved` — adding a new directive appends it at the end; mutating an existing directive does not change its slot.
- `nonceHelper_wrapsInQuotesAndPrefix` — `CSP.nonce("abc")` returns `"'nonce-abc'"`; same for `sha256`/`sha384`/`sha512` with the appropriate prefix.
- `constants_areQuoted` — `CSP.SELF` equals `"'self'"`; spot-check a couple of others.
- `validation_rejectsSemicolonInValue` — `addScriptSrc("a;b")` throws `IllegalArgumentException`.
- `validation_rejectsWhitespaceInValue` — `addScriptSrc("a b")` throws.
- `validation_rejectsEmptyValue` — `addScriptSrc("")` throws.
- `validation_rejectsBadDirectiveName` — generic `.directive("Script-Src", ...)` and `.directive("", ...)` and `.directive("a;b", ...)` all throw.
- `validation_helpersRejectNullAndEmpty` — `CSP.nonce(null)`, `CSP.sha256("")` throw.
- `integration_withSecurityHeaders` — wire `SecurityHeaders.builder().contentSecurityPolicy(CSP.defaults().addStyleSrc("https://cdn.example.com")).build()` into a `Web`, hit it, assert the response `Content-Security-Policy` header carries the addition and that the existing localhost `upgrade-insecure-requests` stripping still works.

The existing `SecurityHeadersTest.defaults_emitsAllHeadersWithExpectedValues`, `csp_stripsUpgradeInsecureRequestsForLocalhost`, `csp_stripsUpgradeInsecureRequestsForLoopbackIP`, and `headersPresentOn404` cases are the regression backstop — they must keep passing untouched.

## Non-goals

- No semantic validation of CSP source grammar (hosts, schemes, hash structure).
- No request-aware behavior in `CSP` itself. The localhost `upgrade-insecure-requests` strip stays in `SecurityHeaders` where it already lives.
- No CSP report-only header (`Content-Security-Policy-Report-Only`). Out of scope; if needed later, add a parallel `SecurityHeaders.Builder.contentSecurityPolicyReportOnly(CSP)` setter.
- No thread safety. Construct on one thread.
- No nonce generation. The app supplies the nonce value; `CSP.nonce(value)` only formats it.
