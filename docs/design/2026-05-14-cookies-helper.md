# Cookies helper

## Motivation

Applications built on the Latte Java web framework set cookies in almost every flow — sessions, CSRF tokens, return-to URLs, theme preferences, feature flags. Today, every application has to:

1. Recompute the `Secure` flag from `req.getScheme()` and `X-Forwarded-Proto` for each cookie.
2. Decide on `SameSite`, `Path`, and `HttpOnly` defaults and remember to apply them.
3. Hand-roll AES-GCM if any cookie value should be encrypted.
4. Build a custom Base64URL value format and remember whether tampering throws or returns garbage.

The OIDC subsystem already has internal helpers (`org.lattejava.web.oidc.internal.Tools.addCommonCookieSettings`, `addTransientCookie`, `clearCookie`, etc.) that bake in the secure defaults and the scheme heuristic. They are private to OIDC and don't support encryption.

This design promotes those defaults into a public, application-facing helper — `org.lattejava.web.Cookies` — and adds authenticated-encrypted cookie support with key rotation. The OIDC `Tools` cookie methods are refactored to delegate to the new helper so there is a single source of truth for "what does Secure mean here."

## Public API

One new public class, plus its nested fluent builders, plus one new public exception. The helper goes in the top-level `org.lattejava.web` package alongside `Web`, `Configuration`, `Handler`.

### `Cookies` (entry point)

```java
public final class Cookies {
  public static Cookies newInstance();
  public static Cookies encryptionKeys(byte[]... keys);
  public static Cookies encryptionKeys(SecretKey... keys);
  public static Cookies encryptionKeys(List<SecretKey> keys);

  public PlainWriteBuilder write(String name, String value);
  public ReadBuilder        read(String name);
  public ClearBuilder       clear(String name);
}
```

- Constructed exclusively through `Cookies.newInstance()` (no encryption) or `Cookies.encryptionKeys(...)` (with encryption). No public constructor and no nested `Builder`.
- `Cookies.newInstance()` returns a helper with no encryption keys. It can write and read plaintext cookies with the framework defaults; calling `.encrypted()` on any builder it produces throws `IllegalStateException` with the message `Cookies helper was not configured with encryption keys`. Use this for apps that only need the secure defaults (auto-`Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`).
- `Cookies.encryptionKeys(...)` returns a helper configured for AES-256-GCM encrypted cookies via `.encrypted()`. The same helper can also write and read plaintext cookies — the `.encrypted()` step is opt-in per cookie. The variadic form must receive at least one key; calling `Cookies.encryptionKeys()` with zero arguments throws `IllegalArgumentException` (use `Cookies.newInstance()` for the no-encryption case).
- The returned instance is immutable and thread-safe. Build it once at application startup; reuse it across requests and threads.
- The only configuration is the encryption-key list. The framework defaults (`HttpOnly=true`, `SameSite=Strict`, `Path=/`, auto-`Secure`) are hardcoded into the per-cookie builders. Apps override per cookie.
- Each `byte[]` must be exactly 32 bytes (AES-256). Anything else throws `IllegalArgumentException` at factory time with the message `Encryption key must be 32 bytes for AES-256: [<actual length>]`.
- `SecretKey` overloads accept any `SecretKey` whose encoded form is 32 bytes and whose algorithm is `AES`. Other algorithms throw `IllegalArgumentException` with the message `Encryption key algorithm must be [AES]: [<actual>]`.
- The key list order matters: **the first key is the primary** (used for all new encryptions). All keys are tried in order during decrypt; first success wins. This supports key rotation — issue a new primary, keep the previous one in the list until existing cookies have aged out, then drop it.

### `WriteBuilder<T extends WriteBuilder<T>>` (CRTP-generic, abstract)

```java
public abstract static class WriteBuilder<T extends WriteBuilder<T>> {
  protected abstract T self();

  public T path(String p);                  // default "/"
  public T domain(String d);                // default unset
  public T maxAge(Duration d);              // default unset (session cookie)
  public T httpOnly(boolean b);             // default true
  public T secure(boolean b);               // default: auto-detect from request scheme
  public T sameSite(Cookie.SameSite s);     // default Strict

  public EncryptedWriteBuilder encrypted();

  public abstract void to(HTTPRequest req, HTTPResponse res);
}
```

Setters return `T` so the chain keeps the concrete subtype throughout. `cookies.write(name, value)` returns `PlainWriteBuilder`; after `.encrypted()`, the chain is `EncryptedWriteBuilder`. The terminal verb is `.to(req, res)` — read as "write this cookie to this request/response pair."

`secure(boolean)` is an explicit override. The default behaviour — applied when `secure(...)` is never called — is auto-detection from `req.getScheme().equalsIgnoreCase("https") || "https".equalsIgnoreCase(req.getHeader("X-Forwarded-Proto"))`. Calling `.secure(true)` or `.secure(false)` disables that heuristic for this cookie.

### `PlainWriteBuilder`, `EncryptedWriteBuilder`

```java
public static final class PlainWriteBuilder extends WriteBuilder<PlainWriteBuilder> {
  @Override protected PlainWriteBuilder self() { return this; }
  @Override public void to(HTTPRequest req, HTTPResponse res);
}

public static final class EncryptedWriteBuilder extends WriteBuilder<EncryptedWriteBuilder> {
  @Override protected EncryptedWriteBuilder self() { return this; }
  @Override public EncryptedWriteBuilder encrypted() { return this; }  // idempotent
  @Override public void to(HTTPRequest req, HTTPResponse res);
}
```

`PlainWriteBuilder.to` builds an `org.lattejava.http.Cookie` with the configured attributes and adds it to the response with the raw value. `EncryptedWriteBuilder.to` encrypts the value with the primary key (see "Encryption scheme") and writes the resulting Base64URL string as the cookie value.

Typical use:

```java
cookies.write("session", sessionId)
       .encrypted()
       .maxAge(Duration.ofMinutes(30))
       .to(req, res);
```

### `ReadBuilder`, `EncryptedReadBuilder`

```java
public static class ReadBuilder {
  public EncryptedReadBuilder encrypted();
  public String from(HTTPRequest req);   // returns raw cookie value, or null
}

public static final class EncryptedReadBuilder extends ReadBuilder {
  @Override public EncryptedReadBuilder encrypted() { return this; }
  @Override public String from(HTTPRequest req);    // decrypts, or null/throws
}
```

`ReadBuilder` does not need CRTP — it has no chainable attribute setters. `encrypted()` is the only modifier and the terminal is `from(req)`.

Typical use:

```java
String session = cookies.read("session")
                        .encrypted()
                        .from(req);
```

Return contract for `from(req)`:

| Cookie state           | `PlainReadBuilder.from` | `EncryptedReadBuilder.from` |
|------------------------|-------------------------|-----------------------------|
| Not present in request | `null`                  | `null`                      |
| Present                | the raw cookie value    | (see below)                 |

`EncryptedReadBuilder.from(req)` when the cookie is present runs three checks in order:

1. Base64URL-decode the value. If it does not decode, or the decoded byte array is shorter than 28 bytes (12-byte nonce + 16-byte tag minimum), throw `CookieIntegrityException(name, MALFORMED)`.
2. Walk the key list and attempt AES-GCM decrypt with the cookie name as AAD. If any key succeeds, return the plaintext.
3. If every key fails, throw `CookieIntegrityException(name, DECRYPT_FAILED, cause)`.

A value that was originally written as plaintext is, from the encrypted reader's perspective, just an arbitrary string. It surfaces as `MALFORMED` if it does not decode as Base64URL or is too short, and as `DECRYPT_FAILED` if it happens to decode into a long-enough byte array that fails the GCM tag check. Applications should not mix plaintext and encrypted reads on the same cookie name.

`EncryptedReadBuilder.from(req)` when the helper has no keys configured: `IllegalStateException`. This is a programmer error, not a runtime data error.

### `ClearBuilder`

```java
public static final class ClearBuilder {
  public ClearBuilder path(String p);    // default "/"
  public ClearBuilder domain(String d);  // default unset
  public void from(HTTPRequest req, HTTPResponse res);
}
```

`from(req, res)` writes a cookie with empty value, `Max-Age=0`, and the same defaults as a write (including auto-`Secure`). The `path` and `domain` must match the original cookie or the browser will not clear it; the defaults mirror the write defaults.

Typical use:

```java
cookies.clear("session").from(req, res);
```

### `CookieIntegrityException`

```java
public class CookieIntegrityException extends RuntimeException {
  public enum Reason { DECRYPT_FAILED, MALFORMED }
  public String name();
  public Reason reason();
}
```

Public, top-level in `org.lattejava.web`. `RuntimeException` so handlers do not need `throws` clauses. Carries the cookie name and reason for logging.

## Encryption scheme

### Algorithm

AES-256-GCM. Exactly one supported algorithm. No algorithm tag is embedded in the wire format.

### Wire format

Per encrypted cookie value:

```
Base64URL( nonce (12 bytes) || ciphertext || gcmTag (16 bytes) )
```

- Nonce: 12 random bytes from `SecureRandom`, generated fresh for every write. No nonce reuse.
- Ciphertext length matches plaintext length.
- Tag: 16 bytes (128-bit), appended by `Cipher` as part of the GCM output.
- Base64URL encoding without padding (`Base64.getUrlEncoder().withoutPadding()`). URL-safe so the value is cookie-safe without further escaping.
- No key identifier byte. The rotation list is expected to be tiny (typically 1 or 2 keys). Trying each key on decrypt is cheap and avoids leaking which key issued any given cookie.
- No format version byte. If the format ever changes, the new code reissues cookies; existing encrypted cookies become invalid and are cleared by the application's normal "cookie not present or invalid → re-issue" logic.

### AAD binding (security-critical)

The cookie's **name** is passed as the GCM Additional Authenticated Data (AAD). This binds the ciphertext to the cookie name: an attacker who copies the encrypted value of cookie `prefs` into a cookie called `session` will fail decryption — the GCM tag check uses the read-time cookie name as AAD and will not match the encrypt-time AAD.

Consequence: renaming a cookie invalidates all outstanding encrypted instances of that cookie. This is intended. A rename is rare and the failure mode is the same as any other invalid cookie (clear and re-issue).

### Decrypt failure handling

`EncryptedReadBuilder.from(req)`:

1. If the cookie is absent → return `null`.
2. If present but Base64URL decode fails or the byte array is shorter than `12 + 16` bytes → throw `CookieIntegrityException(name, MALFORMED)`.
3. Otherwise, walk the key list in order. For each key, attempt GCM decrypt with the cookie name as AAD. The first successful decrypt returns the plaintext.
4. If every key fails → throw `CookieIntegrityException(name, DECRYPT_FAILED, cause)` where `cause` is the last underlying `javax.crypto.AEADBadTagException` (or equivalent).

Tampering of the nonce, ciphertext, or tag all surface as `DECRYPT_FAILED`. Wrong key and active tampering are not distinguishable from the application's perspective — and should not need to be: the response in both cases is "clear the cookie, treat as unauthenticated, re-issue if needed."

### Encryption process

`EncryptedWriteBuilder.to(req, res)`:

1. Generate 12 random nonce bytes.
2. Initialize `Cipher.getInstance("AES/GCM/NoPadding")` with the primary key and a `GCMParameterSpec(128, nonce)`.
3. `updateAAD(name.getBytes(UTF_8))`.
4. `doFinal(value.getBytes(UTF_8))` to produce `ciphertext || tag`.
5. Concatenate `nonce || ciphertext || tag`, Base64URL-encode without padding.
6. Build an `org.lattejava.http.Cookie` with the encoded string and the configured attributes; add it to the response.

### Internal layout

The crypto is implemented in `org.lattejava.web.internal.CookieCrypto`:

```java
final class CookieCrypto {
  static String encrypt(SecretKey primaryKey, String name, String value);
  static String decrypt(List<SecretKey> keys, String name, String wireValue) throws CookieIntegrityException;
}
```

This keeps `Cookies.java` focused on the fluent surface. The two methods are the only places `javax.crypto` types are touched.

## Defaults and per-cookie overrides

| Attribute  | Default                                                                             | Override                           |
|------------|-------------------------------------------------------------------------------------|------------------------------------|
| `Path`     | `/`                                                                                 | `.path(String)`                    |
| `Domain`   | unset                                                                               | `.domain(String)`                  |
| `Max-Age`  | unset (session cookie)                                                              | `.maxAge(Duration)`                |
| `HttpOnly` | `true`                                                                              | `.httpOnly(boolean)`               |
| `Secure`   | auto: `req.getScheme()=="https"` OR `X-Forwarded-Proto=="https"` (case-insensitive) | `.secure(boolean)` (disables auto) |
| `SameSite` | `Strict`                                                                            | `.sameSite(Cookie.SameSite)`       |

Defaults are hardcoded — there is no helper-level "set default SameSite to Lax for the whole app" knob. Apps that need a different baseline configure it per cookie. This keeps the helper's surface small and removes a class of "did my override survive merging with the global default?" bugs.

Scheme auto-detection is encapsulated in one place: `Cookies` (or `CookieCrypto`-adjacent helper) exposes a package-private `isSecureScheme(HTTPRequest)` that both the plain and encrypted write builders call. The OIDC `Tools.addCommonCookieSettings` calls the same method after the refactor, so there is exactly one place where the heuristic is defined.

## OIDC refactor

`OIDCConfig` gains no new fields. `OIDC` (the top-level class) constructs a `Cookies cookies = Cookies.newInstance()` — no encryption keys, since OIDC cookies are either signed JWTs or short-lived state values that the JWT-signature mechanism already protects.

The eight methods in `org.lattejava.web.oidc.internal.Tools` that touch cookies are rewritten to delegate:

| Current Tools method                 | After refactor                                                 |
|--------------------------------------|----------------------------------------------------------------|
| `addCommonCookieSettings(c, req)`    | removed — defaults come from `Cookies` automatically           |
| `addTransientCookie(req, res, n, v)` | `cookies.write(n, v).to(req, res)` (HttpOnly defaults to true) |
| `addAuthCookies(...)`                | three explicit `.write(...).maxAge(...).to(...)` calls         |
| `readCookie(req, name)`              | `cookies.read(name).from(req)`                                 |
| `clearCookie(req, res, name)`        | `cookies.clear(name).from(req, res)`                           |
| `clearAllAuthCookies(req, res, cfg)` | three explicit clears                                          |
| `clearAllCookies(req, res, cfg)`     | five explicit clears                                           |

Behaviour preservation:

- The OIDC `idTokenCookieName` cookie is currently emitted *without* `HttpOnly` (so JS can read it). The refactor preserves this with an explicit `.httpOnly(false)` on that one cookie.
- The OIDC access-token, refresh-token, and transient cookies are currently emitted *with* `HttpOnly=true`. The new helper defaults `HttpOnly=true`, so no explicit override is required.
- All OIDC cookies currently use `SameSite=Strict` and `Path=/`. Matches the new helper's defaults.

The OIDC `Cookies` instance is constructed once when `OIDC` is built, held as a field, and reused across requests.

## Error model

| Condition                                                  | Result                                                                            |
|------------------------------------------------------------|-----------------------------------------------------------------------------------|
| `Cookies.encryptionKeys(badLength)`                        | `IllegalArgumentException`: `Encryption key must be 32 bytes for AES-256: [<n>]`  |
| `Cookies.encryptionKeys(SecretKey not-AES)`                | `IllegalArgumentException`: `Encryption key algorithm must be [AES]: [<actual>]`  |
| `cookies.write(null, ...)` or `cookies.write(..., null)`   | `NullPointerException` with explanatory message                                   |
| `.encrypted()` on a helper with no keys                    | `IllegalStateException`: `Cookies helper was not configured with encryption keys` |
| `.to(req, res)` after `.maxAge(null)`                      | written without `Max-Age` (treated as "no override")                              |
| `.encrypted().from(req)` cookie absent                     | `null`                                                                            |
| `.encrypted().from(req)` cookie not Base64URL or too short | `CookieIntegrityException(name, MALFORMED)`                                       |
| `.encrypted().from(req)` cookie tampered or wrong key      | `CookieIntegrityException(name, DECRYPT_FAILED, cause)`                           |
| `.encrypted().from(req)` helper has no keys                | `IllegalStateException`: `Cookies helper was not configured with encryption keys` |

All runtime-value-bearing exception messages use square-bracket framing per `.claude/rules/error-messages.md`.

## Testing

Two new test classes:

### `CookiesTest` (in `src/test/java/org/lattejava/web/tests/CookiesTest.java`)

Exercises the fluent surface end-to-end through `WebTest`:

- Plain write/read roundtrip preserves the value.
- A fresh cookie has `HttpOnly`, `SameSite=Strict`, `Path=/`, and no `Max-Age` (session cookie).
- `secure(true)` forces Secure; `secure(false)` forces it off; with neither call, Secure is set when the request scheme is `https` or `X-Forwarded-Proto: https`.
- `.encrypted().to(req, res)` produces a value that is *not* the plaintext and is Base64URL-shaped.
- `.encrypted()` roundtrip recovers the original plaintext.
- Tampering one byte of the encrypted value's nonce, ciphertext, or tag region causes `.encrypted().from(req)` to throw `CookieIntegrityException(DECRYPT_FAILED)`.
- Reading a non-Base64URL value (or one shorter than 28 bytes) as encrypted throws `CookieIntegrityException(MALFORMED)`.
- Key rotation: encrypt with key A, then build a new helper with `(B, A)` and decrypt successfully; rotate to `(C, B)` (dropping A) and that same cookie now throws `DECRYPT_FAILED`.
- `.encrypted()` on a helper built with no keys throws `IllegalStateException`.
- `clear(name).from(req, res)` emits `Max-Age=0` and the same `Path`/`Domain` as configured.
- AAD binding: writing encrypted `prefs=secret`, then attempting to read the same wire value under cookie name `session`, throws `DECRYPT_FAILED`. This is exercised by tampering the request to deliver the value under a different name.

### `CookieCryptoTest` (in `src/test/java/org/lattejava/web/tests/internal/CookieCryptoTest.java`)

Direct unit tests on the internal crypto, without `WebTest`:

- `encrypt` produces a distinct ciphertext for the same plaintext on 1000 calls (nonce uniqueness).
- Ciphertext + tag has the expected byte length for a given plaintext length.
- `decrypt` succeeds with the encrypt-time key and AAD.
- `decrypt` fails when the AAD differs (cookie-name binding).
- `decrypt` fails when the key differs.
- `decrypt` fails when any single byte of the wire value is flipped.
- Key-length validation rejects 16-byte and 24-byte keys (AES-128/192 disallowed in this design).

### Existing OIDC tests

After the OIDC refactor, all existing OIDC tests must continue to pass with no changes. This is the integration check that the refactor preserves behaviour. If any OIDC test does need to change, that's a signal that behaviour drifted and we need to justify the drift in this design before merging.

## Out of scope

- Signed (HMAC, not encrypted) cookies. Considered and rejected for v1 — the encrypted path already covers the use cases the OIDC code has, and adding `.signed()` doubles the surface area. Can be added later as a third sibling builder.
- Custom value types (`<T>` with a codec). The helper is `String`-typed for value in and out. Apps that want to store structured data should serialize to JSON themselves and pass the JSON string in. Adding a `<T>` codec is a future addition that won't break this API.
- `Configuration`-aware factory (`Cookies.fromConfiguration(config)`). Apps load keys themselves and pass bytes into `Cookies.encryptionKeys(...)`. Keeps `Cookies` independent of `Configuration` and avoids prescribing a setting-name convention.
- Helper-level default overrides (e.g. a `Cookies.encryptionKeys(...).defaultSameSite(...)` knob). All overrides happen per cookie. If many apps end up overriding the same default the same way, we revisit.
