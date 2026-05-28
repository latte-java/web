# OIDC Rework — Mode-Based Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single-class `OIDC<U>` + god-record `OIDCConfig` design with mode-first profiles (`OIDC.ssr/spa/api`) composed from two pluggable axes — transport (`TokenReader`/`TokenWriter`) and challenge (`AuthChallenge`) — plus a profile-agnostic `Authorizer` layer.

**Architecture:** One orchestrator middleware runs an identical authenticate→validate→refresh→bind algorithm for every profile. The mode-first factories stitch a transport pair and a challenge from per-client config. `OIDCConfig` shrinks to the IdP relationship; cookie/header specifics move onto the reader/writer; `BrowserSettings`/`APISettings` carry session/transport defaults. Authorization denial and SSR auth failures route through the challenge so SSR renders HTML instead of bare status codes.

**Tech Stack:** Java 25 (module system, `ScopedValue`, sealed types, records), `org.lattejava.http`, `org.lattejava.jwt` (`JWT.decode`, `JWKS.fromJWKS`, `OpenIDConnect.discover`), Jackson, TestNG, FusionAuth (`http://localhost:9012`, kickstart-provisioned).

**Reference spec:** `docs/design/2026-05-26-oidc-rework.md`

---

## Conventions (apply to every file)

- License header (block comment, `Copyright (c) 2026 The Latte Project` / `SPDX-License-Identifier: MIT`) as the very first lines.
- Module imports (`import module java.base;` etc.), alphabetized; blank line between groups.
- Acronyms fully uppercase (`OIDC`, `JWT`, `API`, `URI`).
- Runtime values in error messages wrapped in `[...]`.
- Fields/methods ordered by visibility then alphabetically; no blank lines between fields.
- Tests use TestNG (`@Test`, `org.testng.Assert.*`), not JUnit.
- Run the build with `latte build`; run tests with `latte test`. Start FusionAuth with `docker compose up -d` from `src/test/fusionauth` before FA/Mock tests.

## File structure

**New (public — `org.lattejava.web.oidc`):**
- `TokenReader.java` — `Tokens read(HTTPRequest)`.
- `TokenWriter.java` — `write(...)` + `clear(...)`.
- `AuthChallenge.java` — `unauthenticated(req,res,writer,retryable)`, `forbidden(req,res)`, `unavailable(req,res)`.
- `Authorizer.java` — `boolean authorize(req,jwt)` + `hasAnyRole`/`hasAllRoles` statics.
- `BrowserSettings.java` — record + Builder (session paths, redirect targets, `forbiddenHandler`/`unavailableHandler`, default reader/writer).
- `APISettings.java` — record + Builder (default reader/writer).

**New (internal — `org.lattejava.web.oidc.internal`):**
- `CookieTokenReader.java` / `CookieTokenWriter.java`.
- `HeaderTokenReader.java` / `HeaderTokenWriter.java`.
- `StatusChallenge.java` / `RedirectChallenge.java`.
- `Authentication.java` — orchestrator `Middleware`.
- `Authorization.java` — `Middleware`.
- `SessionEndpoints.java` — `Middleware` dispatching login/callback/logout/logout-return.

**Modified:**
- `OIDCConfig.java` — slim to IdP relationship.
- `OIDC.java` — mode-first factories + profile instance.
- `internal/TokenValidator.java` — keep uniform `validate()`; drop nothing essential (already supports both modes).
- `internal/Tools.java` — keep `refresh`, `postToken`, `requireSecureURI`, `jsonToJWT`, `computeCodeChallenge`, `writeMetaRefresh`, `formEncode`, `textOrNull`, `HTTP`, `MAPPER`, `CURRENT_JWT`; **remove** the cookie helpers that move onto `CookieTokenWriter` (`addAuthCookies`, `addTransientCookie`, `clearAllAuthCookies`, `clearAllCookies`, `clearCookie`, `readCookie`, `COOKIES`) — or keep them package-private for `CookieTokenReader/Writer`/`SessionEndpoints` to call. **Decision: keep them in `Tools`** so the reader/writer/session code shares one cookie chokepoint; only their *callers* change.
- `internal/CallbackHandler.java` / `LoginHandler.java` / `LogoutHandler.java` / `LogoutReturnHandler.java` — re-parameterize from `BrowserSettings` instead of `OIDCConfig` for the cookie/path fields; keep `OIDCConfig` for client/endpoint fields. Wired through `SessionEndpoints`.

**Removed:**
- `Authenticated.java`, `JWTAuthenticated.java`, `APIAuthenticated.java`, `APIAuthorized.java`, `APIAuthorizer.java`, `HasAnyRole.java`, `HasAllRoles.java`, `TokenExtractor.java`, `TokenWriter.java` (old).

**Tests:** migrate `src/test/java/org/lattejava/web/tests/oidc/*` and `BaseOIDCTest`/`OIDCTestFixture` to the new API; add the new-component unit tests below.

---

## Phase 1 — Configuration & transport foundation

### Task 1: Slim `OIDCConfig` to the IdP relationship

**Files:**
- Modify: `src/main/java/org/lattejava/web/oidc/OIDCConfig.java`
- Test: `src/test/java/org/lattejava/web/tests/oidc/OIDCConfigTest.java`

Remove from the record and Builder: `apiAudience`, `apiTokenExtractor`, `apiTokenWriter`, `errorPage`, `postLoginPage`, `postLogout`, `loginPath`, `callbackPath`, `logoutPath`, `logoutReturnPath`, all five cookie-name fields, `returnToCookieName`, `refreshTokenMaxAge`. Keep: `issuer`, `authorizeEndpoint`, `tokenEndpoint`, `userinfoEndpoint`, `jwksEndpoint`, `logoutEndpoint`, `introspectionEndpoint`, `clientId`, `clientSecret`, `scopes`, `roleExtractor`, `validateAccessToken`. Keep `fullRedirectURI(HTTPRequest)` — but it needs `callbackPath`, which now lives on `BrowserSettings`; **move `fullRedirectURI` out of `OIDCConfig`** (it becomes a helper that takes the callback path; the callback path is supplied by `SessionEndpoints`/`LoginHandler` from `BrowserSettings`). Keep discovery (`fillIn`), `requireSecureURI` checks, and the `validateAccessToken=false ⇒ introspectionEndpoint` rule.

- [ ] **Step 1: Update `OIDCConfigTest` for the slim record**

Remove assertions referencing dropped fields (cookie names, paths, `apiAudience`, extractor/writer, `refreshTokenMaxAge`). Keep/confirm:

```java
@Test
public void requiredFields() {
  assertThrows(IllegalArgumentException.class, () -> OIDCConfig.builder().build());
  assertThrows(IllegalArgumentException.class,
      () -> OIDCConfig.builder().clientId("c").clientSecret("s").build()); // no issuer/endpoints
}

@Test
public void validateAccessTokenFalseRequiresIntrospection() {
  assertThrows(IllegalStateException.class, () -> OIDCConfig.builder()
      .authorizeEndpoint(URI.create("https://idp/auth"))
      .tokenEndpoint(URI.create("https://idp/token"))
      .userinfoEndpoint(URI.create("https://idp/userinfo"))
      .jwksEndpoint(URI.create("https://idp/jwks"))
      .clientId("c").clientSecret("s")
      .validateAccessToken(false)
      .build());
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `latte test` — Expected: compilation errors in `OIDCConfig` and its callers (we fix `OIDCConfig` now; callers are replaced in later tasks).

- [ ] **Step 3: Rewrite `OIDCConfig`**

Record components (keep order, alphabetize Builder fields/methods per conventions):

```java
public record OIDCConfig(
    String issuer,
    URI authorizeEndpoint,
    URI tokenEndpoint,
    URI userinfoEndpoint,
    URI jwksEndpoint,
    URI logoutEndpoint,
    URI introspectionEndpoint,
    String clientId,
    String clientSecret,
    List<String> scopes,
    Function<JWT, Set<String>> roleExtractor,
    boolean validateAccessToken
) {
  public static Builder builder() { return new Builder(); }
}
```

Builder: drop the removed fields and their setters; keep `issuer`, the six endpoint setters, `introspectionEndpoint`, `clientId`, `clientSecret`, `scopes` (default `List.of("openid","profile","email","offline_access")`), `roleExtractor` (default `jwt -> new HashSet<>(jwt.getList("roles", String.class))`), `validateAccessToken` (default `true`). `build()` keeps: clientId/clientSecret non-blank; issuer-or-all-four-endpoints; scopes contains `openid`; roleExtractor non-null; `requireSecureURI` on each endpoint; `fillIn()` discovery; post-discovery authorize/token/jwks non-null; `validateAccessToken=false ⇒ introspectionEndpoint != null`. Remove cookie-name and path validation blocks.

- [ ] **Step 4: Run config tests**

Run: `latte test` (config test only will still fail to compile because other OIDC files reference the old API). To check this file in isolation, defer running until Task 16; for now verify the record compiles by reading. Expected after the full phase: `OIDCConfigTest` passes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/OIDCConfig.java src/test/java/org/lattejava/web/tests/oidc/OIDCConfigTest.java
git commit -m "refactor(oidc): slim OIDCConfig to the IdP relationship"
```

---

### Task 2: `TokenReader` and `TokenWriter` interfaces

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/TokenReader.java`
- Create: `src/main/java/org/lattejava/web/oidc/TokenWriter.java`

- [ ] **Step 1: Write `TokenReader`**

```java
package org.lattejava.web.oidc;

import module org.lattejava.http;

/**
 * Reads the OIDC tokens off an incoming request. Pluggable per profile (cookies for browser modes, bearer headers for
 * API by default), so neither transport is hard-wired to a mode.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface TokenReader {
  /**
   * Extracts the tokens from the request. Any field of the returned {@link Tokens} may be {@code null}.
   *
   * @param req The current request.
   * @return The extracted tokens.
   */
  Tokens read(HTTPRequest req);
}
```

- [ ] **Step 2: Write `TokenWriter`**

```java
package org.lattejava.web.oidc;

import module org.lattejava.http;

/**
 * Writes refreshed tokens back to the response and clears them on failure. Symmetric with {@link TokenReader}; the
 * {@code clear} operation is what differs between cookie transports (delete the cookies) and header transports (a
 * no-op), which is why it lives on the writer rather than the challenge.
 *
 * @author Brian Pontarelli
 */
public interface TokenWriter {
  /**
   * Writes the newly issued tokens after a successful refresh.
   *
   * @param req    The current request.
   * @param res    The response.
   * @param tokens The refreshed tokens; the refresh/id tokens may be {@code null} if not rotated.
   */
  void write(HTTPRequest req, HTTPResponse res, Tokens tokens);

  /**
   * Removes any persisted tokens (cookie transports delete their cookies; header transports do nothing).
   *
   * @param req The current request.
   * @param res The response.
   */
  void clear(HTTPRequest req, HTTPResponse res);
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/TokenReader.java src/main/java/org/lattejava/web/oidc/TokenWriter.java
git commit -m "feat(oidc): add TokenReader/TokenWriter transport interfaces"
```

---

### Task 3: `CookieTokenReader` / `CookieTokenWriter`

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/internal/CookieTokenReader.java`
- Create: `src/main/java/org/lattejava/web/oidc/internal/CookieTokenWriter.java`
- Test: `src/test/java/org/lattejava/web/tests/oidc/CookieTransportTest.java`

These carry the token-cookie names; the writer also the refresh max-age and policy. They use `Tools` cookie helpers. Cookie attributes match the prior design: `id`/`access` `SameSite=Lax`, refresh `SameSite=Strict`; `Secure` derived by the `Cookies` helper from request scheme / `X-Forwarded-Proto`.

- [ ] **Step 1: Write the failing test**

```java
package org.lattejava.web.tests.oidc;

import module java.base;
import org.lattejava.web.oidc.*;
import org.lattejava.web.oidc.internal.*;
import org.testng.annotations.*;
import static org.testng.Assert.*;

public class CookieTransportTest {
  @Test
  public void readsThreeTokensFromCookies() {
    // Use the project's HTTPRequest test double / builder used elsewhere in tests (see existing AuthenticatedTest).
    var req = TestRequests.withCookies("access_token", "AT", "refresh_token", "RT", "id_token", "IT");
    var reader = new CookieTokenReader("access_token", "refresh_token", "id_token");
    Tokens t = reader.read(req);
    assertEquals(t.accessToken(), "AT");
    assertEquals(t.refreshToken(), "RT");
    assertEquals(t.idToken(), "IT");
  }

  @Test
  public void clearDeletesThree() {
    var req = TestRequests.empty();
    var res = TestRequests.response();
    var writer = new CookieTokenWriter("access_token", "refresh_token", "id_token", Duration.ofDays(30));
    writer.clear(req, res);
    assertTrue(TestRequests.clearedCookie(res, "access_token"));
    assertTrue(TestRequests.clearedCookie(res, "refresh_token"));
    assertTrue(TestRequests.clearedCookie(res, "id_token"));
  }
}
```

> NOTE FOR EXECUTOR: there is no `TestRequests` helper yet. Before writing this test, inspect how existing tests (`AuthenticatedTest`, `CallbackTest`) construct `HTTPRequest`/`HTTPResponse` and read written cookies (they run a real server via `BaseWebTest`). Prefer the existing pattern: register a route behind the transport and assert on the HTTP response headers, rather than inventing a double. Rewrite the two tests using that pattern. Keep the assertions (three cookies read; three cleared with `Max-Age=0`).

- [ ] **Step 2: Run, expect fail**

Run: `latte test` — Expected: FAIL (classes not defined).

- [ ] **Step 3: Implement `CookieTokenReader`**

```java
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import org.lattejava.web.oidc.TokenReader;
import org.lattejava.web.oidc.Tokens;

public class CookieTokenReader implements TokenReader {
  private final String accessTokenCookieName;
  private final String idTokenCookieName;
  private final String refreshTokenCookieName;

  public CookieTokenReader(String accessTokenCookieName, String refreshTokenCookieName, String idTokenCookieName) {
    this.accessTokenCookieName = accessTokenCookieName;
    this.refreshTokenCookieName = refreshTokenCookieName;
    this.idTokenCookieName = idTokenCookieName;
  }

  @Override
  public Tokens read(HTTPRequest req) {
    return new Tokens(
        Tools.readCookie(req, accessTokenCookieName),
        Tools.readCookie(req, refreshTokenCookieName),
        Tools.readCookie(req, idTokenCookieName),
        null);
  }
}
```

- [ ] **Step 4: Implement `CookieTokenWriter`**

Move the cookie-policy logic from the old `Tools.addAuthCookies`/`clearAllAuthCookies` into this writer (call the existing `Tools.addAuthCookies`/`Tools.clearAllAuthCookies`, but those took `OIDCConfig`; add overloads on `Tools` that take explicit names + maxAge, and have `CookieTokenWriter` call them):

```java
package org.lattejava.web.oidc.internal;

import module java.base;
import module org.lattejava.http;
import org.lattejava.web.oidc.TokenWriter;
import org.lattejava.web.oidc.Tokens;

public class CookieTokenWriter implements TokenWriter {
  private final String accessTokenCookieName;
  private final String idTokenCookieName;
  private final String refreshTokenCookieName;
  private final Duration refreshTokenMaxAge;

  public CookieTokenWriter(String accessTokenCookieName, String refreshTokenCookieName, String idTokenCookieName,
                           Duration refreshTokenMaxAge) {
    this.accessTokenCookieName = accessTokenCookieName;
    this.refreshTokenCookieName = refreshTokenCookieName;
    this.idTokenCookieName = idTokenCookieName;
    this.refreshTokenMaxAge = refreshTokenMaxAge;
  }

  @Override
  public void clear(HTTPRequest req, HTTPResponse res) {
    Tools.clearCookie(req, res, accessTokenCookieName);
    Tools.clearCookie(req, res, idTokenCookieName);
    Tools.clearCookie(req, res, refreshTokenCookieName);
  }

  @Override
  public void write(HTTPRequest req, HTTPResponse res, Tokens tokens) {
    long expiry = tokens.expiresIn() != null ? tokens.expiresIn() : 3600L;
    Tools.addAuthCookies(req, res, accessTokenCookieName, refreshTokenCookieName, idTokenCookieName,
        refreshTokenMaxAge, tokens.idToken(), tokens.accessToken(), tokens.refreshToken(), expiry);
  }
}
```

Add the `Tools.addAuthCookies(req, res, accessName, refreshName, idName, refreshMaxAge, idToken, accessToken, refreshToken, expirySeconds)` overload by generalizing the existing one (drop the `OIDCConfig` parameter, take the names + maxAge explicitly). Keep the SameSite/HttpOnly/maxAge logic identical to the current implementation.

- [ ] **Step 5: Run, expect pass**

Run: `latte test` — Expected: PASS (after the rest of the module compiles; if isolated compilation isn't possible, this passes at the end of Phase 4).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/internal/CookieTokenReader.java src/main/java/org/lattejava/web/oidc/internal/CookieTokenWriter.java src/test/java/org/lattejava/web/tests/oidc/CookieTransportTest.java src/main/java/org/lattejava/web/oidc/internal/Tools.java
git commit -m "feat(oidc): cookie token reader/writer with explicit names"
```

---

### Task 4: `HeaderTokenReader` / `HeaderTokenWriter`

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/internal/HeaderTokenReader.java`
- Create: `src/main/java/org/lattejava/web/oidc/internal/HeaderTokenWriter.java`
- Test: `src/test/java/org/lattejava/web/tests/oidc/HeaderTransportTest.java`

Behavior identical to the current `TokenExtractor.Default` / `TokenWriter.Default`.

- [ ] **Step 1: Write the failing test** (use the existing server-based test pattern)

```java
// Assert: a route behind HeaderTokenReader sees access token from "Authorization: Bearer X" and refresh from
// "X-Refresh-Token". HeaderTokenWriter.write sets "X-Access-Token"/"X-Refresh-Token"; clear sets no headers.
```

- [ ] **Step 2: Run, expect fail.** Run: `latte test`.

- [ ] **Step 3: Implement `HeaderTokenReader`**

```java
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import org.lattejava.web.oidc.TokenReader;
import org.lattejava.web.oidc.Tokens;

public class HeaderTokenReader implements TokenReader {
  private final String accessTokenHeader;
  private final String refreshTokenHeader;

  public HeaderTokenReader(String accessTokenHeader, String refreshTokenHeader) {
    this.accessTokenHeader = accessTokenHeader;
    this.refreshTokenHeader = refreshTokenHeader;
  }

  @Override
  public Tokens read(HTTPRequest req) {
    String access = null;
    if ("Authorization".equalsIgnoreCase(accessTokenHeader)) {
      String authorization = req.getHeader(accessTokenHeader);
      if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
        access = authorization.substring(7).trim();
      }
    } else {
      access = req.getHeader(accessTokenHeader);
    }
    return new Tokens(access, req.getHeader(refreshTokenHeader), null, null);
  }
}
```

- [ ] **Step 4: Implement `HeaderTokenWriter`**

```java
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import org.lattejava.web.oidc.TokenWriter;
import org.lattejava.web.oidc.Tokens;

public class HeaderTokenWriter implements TokenWriter {
  private final String accessTokenHeader;
  private final String refreshTokenHeader;

  public HeaderTokenWriter(String accessTokenHeader, String refreshTokenHeader) {
    this.accessTokenHeader = accessTokenHeader;
    this.refreshTokenHeader = refreshTokenHeader;
  }

  @Override
  public void clear(HTTPRequest req, HTTPResponse res) {
    // Nothing persisted on the client for header transports.
  }

  @Override
  public void write(HTTPRequest req, HTTPResponse res, Tokens tokens) {
    if (tokens.accessToken() != null) {
      res.setHeader(accessTokenHeader, tokens.accessToken());
    }
    if (tokens.refreshToken() != null) {
      res.setHeader(refreshTokenHeader, tokens.refreshToken());
    }
  }
}
```

- [ ] **Step 5: Run, expect pass.** Run: `latte test`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/internal/HeaderTokenReader.java src/main/java/org/lattejava/web/oidc/internal/HeaderTokenWriter.java src/test/java/org/lattejava/web/tests/oidc/HeaderTransportTest.java
git commit -m "feat(oidc): header token reader/writer"
```

---

### Task 5: `BrowserSettings` record + Builder

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/BrowserSettings.java`

Fields: `tokenReader`, `tokenWriter`, `stateCookieName`, `returnToCookieName`, `loginPath`, `callbackPath`, `logoutPath`, `logoutReturnPath`, `postLoginPage`, `postLogoutPage`, `errorPage`, `forbiddenHandler`, `unavailableHandler`. Builder defaults: cookie names `access_token`/`refresh_token`/`id_token` feed the default reader/writer; `oidc_state`; `oidc_return_to`; `/login`; `/oidc/return`; `/logout`; `/oidc/logout-return`; `/`; `/`; `/`; default forbidden/unavailable Handlers writing minimal pages; `refreshTokenMaxAge` default 30 days (Builder field used only to build the default writer).

- [ ] **Step 1: Write `BrowserSettings`**

```java
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.http;
import org.lattejava.web.Handler;
import org.lattejava.web.oidc.internal.CookieTokenReader;
import org.lattejava.web.oidc.internal.CookieTokenWriter;

public record BrowserSettings(
    TokenReader tokenReader,
    TokenWriter tokenWriter,
    String stateCookieName,
    String returnToCookieName,
    String loginPath,
    String callbackPath,
    String logoutPath,
    String logoutReturnPath,
    String postLoginPage,
    String postLogoutPage,
    String errorPage,
    Handler forbiddenHandler,
    Handler unavailableHandler
) {
  public static Builder builder() { return new Builder(); }

  public static class Builder {
    private String accessTokenCookieName = "access_token";
    private String callbackPath = "/oidc/return";
    private String errorPage = "/";
    private Handler forbiddenHandler = Defaults::forbidden;
    private String idTokenCookieName = "id_token";
    private String loginPath = "/login";
    private String logoutPath = "/logout";
    private String logoutReturnPath = "/oidc/logout-return";
    private String postLoginPage = "/";
    private String postLogoutPage = "/";
    private String refreshTokenCookieName = "refresh_token";
    private Duration refreshTokenMaxAge = Duration.ofDays(30);
    private String returnToCookieName = "oidc_return_to";
    private String stateCookieName = "oidc_state";
    private TokenReader tokenReader;
    private TokenWriter tokenWriter;
    private Handler unavailableHandler = Defaults::unavailable;

    public BrowserSettings build() {
      TokenReader reader = tokenReader != null ? tokenReader
          : new CookieTokenReader(accessTokenCookieName, refreshTokenCookieName, idTokenCookieName);
      TokenWriter writer = tokenWriter != null ? tokenWriter
          : new CookieTokenWriter(accessTokenCookieName, refreshTokenCookieName, idTokenCookieName, refreshTokenMaxAge);
      return new BrowserSettings(reader, writer, stateCookieName, returnToCookieName, loginPath, callbackPath,
          logoutPath, logoutReturnPath, postLoginPage, postLogoutPage, errorPage, forbiddenHandler, unavailableHandler);
    }

    // ... one setter per field, alphabetized, each returning `this` ...
  }

  /** Minimal default pages for the redirect challenge. */
  final class Defaults {
    private Defaults() {}

    static void forbidden(HTTPRequest req, HTTPResponse res) throws Exception {
      res.setStatus(403);
      res.setContentType("text/html; charset=utf-8");
      res.getWriter().write("<!DOCTYPE html><html lang=\"en\"><head><title>Forbidden</title></head>"
          + "<body><h1>403 Forbidden</h1></body></html>");
    }

    static void unavailable(HTTPRequest req, HTTPResponse res) throws Exception {
      res.setStatus(503);
      res.setContentType("text/html; charset=utf-8");
      res.getWriter().write("<!DOCTYPE html><html lang=\"en\"><head><title>Service Unavailable</title></head>"
          + "<body><h1>503 Service Unavailable</h1></body></html>");
    }
  }
}
```

> EXECUTOR: write out every alphabetized setter explicitly (`accessTokenCookieName`, `callbackPath`, `errorPage`, `forbiddenHandler`, `idTokenCookieName`, `loginPath`, `logoutPath`, `logoutReturnPath`, `postLoginPage`, `postLogoutPage`, `refreshTokenCookieName`, `refreshTokenMaxAge`, `returnToCookieName`, `stateCookieName`, `tokenReader`, `tokenWriter`, `unavailableHandler`). Verify whether `HTTPResponse` exposes `getWriter()` and `setContentType(...)` (see `Tools.writeMetaRefresh`, which uses both) — it does.

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/BrowserSettings.java
git commit -m "feat(oidc): BrowserSettings with default cookie transport and challenge pages"
```

---

### Task 6: `APISettings` record + Builder

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/APISettings.java`

- [ ] **Step 1: Write `APISettings`**

```java
package org.lattejava.web.oidc;

import org.lattejava.web.oidc.internal.HeaderTokenReader;
import org.lattejava.web.oidc.internal.HeaderTokenWriter;

public record APISettings(TokenReader tokenReader, TokenWriter tokenWriter) {
  public static Builder builder() { return new Builder(); }

  public static class Builder {
    private String accessTokenHeader = "Authorization";
    private String accessTokenWriteHeader = "X-Access-Token";
    private String refreshTokenReadHeader = "X-Refresh-Token";
    private String refreshTokenWriteHeader = "X-Refresh-Token";
    private TokenReader tokenReader;
    private TokenWriter tokenWriter;

    public APISettings build() {
      TokenReader reader = tokenReader != null ? tokenReader
          : new HeaderTokenReader(accessTokenHeader, refreshTokenReadHeader);
      TokenWriter writer = tokenWriter != null ? tokenWriter
          : new HeaderTokenWriter(accessTokenWriteHeader, refreshTokenWriteHeader);
      return new APISettings(reader, writer);
    }

    // setters: accessTokenHeader, accessTokenWriteHeader, refreshTokenReadHeader, refreshTokenWriteHeader,
    // tokenReader, tokenWriter — alphabetized, each returns this.
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/APISettings.java
git commit -m "feat(oidc): APISettings with default header transport"
```

---

## Phase 2 — Challenge & authorization

### Task 7: `AuthChallenge` interface

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/AuthChallenge.java`

- [ ] **Step 1: Write `AuthChallenge`**

```java
package org.lattejava.web.oidc;

import module org.lattejava.http;

/**
 * Communicates authentication/authorization outcomes to the client in the profile's dialect — HTML/redirects for SSR,
 * status codes for SPA and API. Receives the {@link TokenWriter} so it can clear credentials when appropriate, and a
 * {@code retryable} flag used only by the SSR meta-refresh interstitial.
 *
 * @author Brian Pontarelli
 */
public interface AuthChallenge {
  void forbidden(HTTPRequest req, HTTPResponse res) throws Exception;

  void unauthenticated(HTTPRequest req, HTTPResponse res, TokenWriter writer, boolean retryable) throws Exception;

  void unavailable(HTTPRequest req, HTTPResponse res) throws Exception;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/AuthChallenge.java
git commit -m "feat(oidc): AuthChallenge interface (multi-event)"
```

---

### Task 8: `StatusChallenge`

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/internal/StatusChallenge.java`
- Test: covered by the orchestrator tests in Task 13 (SPA/API behaviors).

- [ ] **Step 1: Implement**

```java
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import org.lattejava.web.oidc.AuthChallenge;
import org.lattejava.web.oidc.TokenWriter;

/**
 * Status-code challenge for SPA and API profiles. Clears credentials and returns 401 on an authentication failure
 * (the {@code retryable} flag is ignored — a fetch client cannot follow a meta-refresh); 403 on authorization failure;
 * 503 when the IdP is unreachable.
 *
 * @author Brian Pontarelli
 */
public class StatusChallenge implements AuthChallenge {
  @Override
  public void forbidden(HTTPRequest req, HTTPResponse res) {
    res.setStatus(403);
  }

  @Override
  public void unauthenticated(HTTPRequest req, HTTPResponse res, TokenWriter writer, boolean retryable) {
    writer.clear(req, res);
    res.setStatus(401);
  }

  @Override
  public void unavailable(HTTPRequest req, HTTPResponse res) {
    res.setStatus(503);
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/internal/StatusChallenge.java
git commit -m "feat(oidc): StatusChallenge for SPA/API"
```

---

### Task 9: `RedirectChallenge`

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/internal/RedirectChallenge.java`
- Test: `src/test/java/org/lattejava/web/tests/oidc/RedirectChallengeTest.java` (interstitial + forbidden handler), plus orchestrator tests in Task 13.

Holds `BrowserSettings` and a retry-marker param constant (reuse the existing `csroidcredirect` value to preserve behavior). `unauthenticated`: interstitial when `retryable` and marker absent; otherwise clear + return-to + 302 to `loginPath`. `forbidden`/`unavailable` invoke the configured Handlers.

- [ ] **Step 1: Write the failing test**

```java
package org.lattejava.web.tests.oidc;

import org.lattejava.web.oidc.*;
import org.lattejava.web.oidc.internal.*;
import org.testng.annotations.*;
import static org.testng.Assert.*;

public class RedirectChallengeTest {
  // Using the project's server-based test harness (BaseWebTest), install a route whose middleware calls
  // challenge.unauthenticated(req,res,writer,true) and assert:
  //  - first hit (no marker): 200 text/html containing meta http-equiv="refresh" and "csroidcredirect=1"
  //  - second hit (marker present): 302 to /login and the return-to cookie set
  // And a route calling challenge.forbidden(req,res) with a custom forbiddenHandler asserts the custom body/status.
}
```

- [ ] **Step 2: Run, expect fail.** Run: `latte test`.

- [ ] **Step 3: Implement**

```java
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import org.lattejava.web.oidc.AuthChallenge;
import org.lattejava.web.oidc.BrowserSettings;
import org.lattejava.web.oidc.TokenWriter;

/**
 * HTML/redirect challenge for the SSR profile. On an authentication failure it either writes a one-shot meta-refresh
 * interstitial (when {@code retryable} and the retry marker is absent — the SameSite cross-site case) or clears
 * credentials, records the return-to URL, and redirects to the login path. Authorization failure and IdP-unavailable
 * delegate to the configured {@link BrowserSettings#forbiddenHandler()} / {@link BrowserSettings#unavailableHandler()}.
 *
 * @author Brian Pontarelli
 */
public class RedirectChallenge implements AuthChallenge {
  public static final String CSR_REDIRECT_PARAM = "csroidcredirect";
  private final BrowserSettings settings;

  public RedirectChallenge(BrowserSettings settings) {
    this.settings = settings;
  }

  @Override
  public void forbidden(HTTPRequest req, HTTPResponse res) throws Exception {
    settings.forbiddenHandler().handle(req, res);
  }

  @Override
  public void unauthenticated(HTTPRequest req, HTTPResponse res, TokenWriter writer, boolean retryable) throws Exception {
    if (retryable && req.getURLParameter(CSR_REDIRECT_PARAM) == null) {
      String url = req.getReconstructedURL();
      url += (url.contains("?") ? "&" : "?") + CSR_REDIRECT_PARAM + "=1";
      Tools.writeMetaRefresh(res, url);
      return;
    }

    writer.clear(req, res);
    Tools.addTransientCookie(req, res, settings.returnToCookieName(), req.getBaseURL() + req.getPath());
    res.sendRedirect(settings.loginPath());
  }

  @Override
  public void unavailable(HTTPRequest req, HTTPResponse res) throws Exception {
    settings.unavailableHandler().handle(req, res);
  }
}
```

- [ ] **Step 4: Run, expect pass.** Run: `latte test`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/internal/RedirectChallenge.java src/test/java/org/lattejava/web/tests/oidc/RedirectChallengeTest.java
git commit -m "feat(oidc): RedirectChallenge with interstitial and pluggable pages"
```

---

### Task 10: `Authorizer`

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/Authorizer.java`
- Test: `src/test/java/org/lattejava/web/tests/oidc/AuthorizerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.lattejava.web.tests.oidc;

import module java.base;
import org.lattejava.jwt.*;
import org.lattejava.web.oidc.*;
import org.testng.annotations.*;
import static org.testng.Assert.*;

public class AuthorizerTest {
  private static final Function<JWT, Set<String>> ROLES =
      jwt -> new HashSet<>(jwt.getList("roles", String.class));

  @Test
  public void hasAnyRolePasses() {
    JWT jwt = JWT.builder().claim("roles", List.of("user", "moderator")).build();
    assertTrue(Authorizer.hasAnyRole(ROLES, "admin", "moderator").authorize(null, jwt));
    assertFalse(Authorizer.hasAnyRole(ROLES, "admin").authorize(null, jwt));
  }

  @Test
  public void hasAllRoles() {
    JWT jwt = JWT.builder().claim("roles", List.of("user", "moderator")).build();
    assertTrue(Authorizer.hasAllRoles(ROLES, "user", "moderator").authorize(null, jwt));
    assertFalse(Authorizer.hasAllRoles(ROLES, "user", "admin").authorize(null, jwt));
  }

  @Test
  public void emptyRolesRejected() {
    assertThrows(IllegalArgumentException.class, () -> Authorizer.hasAnyRole(ROLES));
    assertThrows(IllegalArgumentException.class, () -> Authorizer.hasAllRoles(ROLES));
  }
}
```

- [ ] **Step 2: Run, expect fail.** Run: `latte test`.

- [ ] **Step 3: Implement**

```java
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.jwt;

/**
 * Application-supplied access decision over a bound {@link JWT}. Returns a boolean and never emits a status code — the
 * {@code Authorization} middleware routes the outcome through the profile's {@link AuthChallenge}. The built-in
 * {@link #hasAnyRole}/{@link #hasAllRoles} factories cover role checks; arbitrary checks (path, method, scope) are
 * supplied as lambdas.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface Authorizer {
  static Authorizer hasAllRoles(Function<JWT, Set<String>> roleExtractor, String... roles) {
    if (roles == null || roles.length == 0) {
      throw new IllegalArgumentException("At least one role must be provided");
    }
    Set<String> required = Set.of(roles);
    return (req, jwt) -> roleExtractor.apply(jwt).containsAll(required);
  }

  static Authorizer hasAnyRole(Function<JWT, Set<String>> roleExtractor, String... roles) {
    if (roles == null || roles.length == 0) {
      throw new IllegalArgumentException("At least one role must be provided");
    }
    Set<String> required = Set.of(roles);
    return (req, jwt) -> roleExtractor.apply(jwt).stream().anyMatch(required::contains);
  }

  boolean authorize(HTTPRequest req, JWT jwt);
}
```

- [ ] **Step 4: Run, expect pass.** Run: `latte test`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/Authorizer.java src/test/java/org/lattejava/web/tests/oidc/AuthorizerTest.java
git commit -m "feat(oidc): Authorizer with role factories"
```

---

### Task 11: `Authorization` middleware

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/internal/Authorization.java`
- Test: covered by orchestrator/role tests (Task 13, RolesTest migration).

- [ ] **Step 1: Implement**

```java
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import org.lattejava.web.Middleware;
import org.lattejava.web.MiddlewareChain;
import org.lattejava.web.oidc.AuthChallenge;
import org.lattejava.web.oidc.Authorizer;
import org.lattejava.web.oidc.TokenWriter;

/**
 * Authorization middleware. Reads the bound JWT and asks an {@link Authorizer}; denial routes through the profile's
 * {@link AuthChallenge#forbidden}. A missing bound JWT means authentication did not run upstream — fail closed via
 * {@link AuthChallenge#unauthenticated}.
 *
 * @author Brian Pontarelli
 */
public class Authorization implements Middleware {
  private final Authorizer authorizer;
  private final AuthChallenge challenge;
  private final TokenWriter writer;

  public Authorization(Authorizer authorizer, AuthChallenge challenge, TokenWriter writer) {
    this.authorizer = authorizer;
    this.challenge = challenge;
    this.writer = writer;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    if (!Tools.CURRENT_JWT.isBound()) {
      challenge.unauthenticated(req, res, writer, false);
      return;
    }

    if (authorizer.authorize(req, Tools.CURRENT_JWT.get())) {
      chain.next(req, res);
      return;
    }

    challenge.forbidden(req, res);
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/internal/Authorization.java
git commit -m "feat(oidc): Authorization middleware routing denial through the challenge"
```

---

## Phase 3 — Validation & orchestrator

### Task 12: Confirm `TokenValidator` is uniform

**Files:**
- Modify (if needed): `src/main/java/org/lattejava/web/oidc/internal/TokenValidator.java`

The current `validate(token, accessToken)` already does local JWKS decode (with the `clientId` audience check) or introspection based on `validateAccessToken`. Simplify the signature to `validate(String token)` (drop the `accessToken` boolean — every caller now validates an access token). Keep `introspect` (used by the introspection branch) and the sealed `Result`/`IntrospectionResult`. The audience check stays `aud` contains `config.clientId()`.

- [ ] **Step 1: Change signature & callers**

Replace `validate(token, true)` call sites (none remain after old middlewares are removed) and inline the `accessToken=true` assumption: the method body becomes "if `validateAccessToken` → JWKS decode; else → introspect".

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/internal/TokenValidator.java
git commit -m "refactor(oidc): TokenValidator.validate(token) uniform entry point"
```

---

### Task 13: `Authentication` orchestrator

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/internal/Authentication.java`
- Test: `src/test/java/org/lattejava/web/tests/oidc/AuthenticationTest.java` (per-challenge behaviors; FA for the happy/refresh paths, Mock for network/refresh-fail).

- [ ] **Step 1: Write the failing tests** (server-based, one per branch)

```java
// SSR: missing token → 302 to /login + return-to cookie.
// SPA: missing token → 401 + cleared cookies.
// API: missing token → 401, no Set-Cookie.
// FA happy path: valid access token → handler runs, OIDC.jwt() bound.
// FA refresh: expired access + valid refresh (fast-expiry app) → tokens written (cookie or header), handler runs.
// Mock: validator NetworkError → SSR unavailableHandler (503 HTML) / SPA+API 503.
// SSR retryable: access present+invalid, no refresh → meta-refresh; with marker → 302.
```

- [ ] **Step 2: Run, expect fail.** Run: `latte test`.

- [ ] **Step 3: Implement**

```java
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import module org.lattejava.jwt;
import org.lattejava.web.Middleware;
import org.lattejava.web.MiddlewareChain;
import org.lattejava.web.oidc.AuthChallenge;
import org.lattejava.web.oidc.OIDCConfig;
import org.lattejava.web.oidc.TokenReader;
import org.lattejava.web.oidc.TokenWriter;
import org.lattejava.web.oidc.Tokens;

/**
 * The single authentication orchestrator shared by every profile. Reads tokens via the profile's {@link TokenReader},
 * validates the access token, reactively refreshes on an invalid result (writing the new tokens via the profile's
 * {@link TokenWriter}), binds the decoded {@link JWT} to {@link Tools#CURRENT_JWT}, and routes failures through the
 * profile's {@link AuthChallenge}.
 *
 * @author Brian Pontarelli
 */
public class Authentication implements Middleware {
  private final AuthChallenge challenge;
  private final OIDCConfig config;
  private final TokenReader reader;
  private final TokenValidator validator;
  private final TokenWriter writer;

  public Authentication(OIDCConfig config, TokenReader reader, TokenWriter writer, AuthChallenge challenge,
                        TokenValidator validator) {
    this.config = config;
    this.reader = reader;
    this.writer = writer;
    this.challenge = challenge;
    this.validator = validator;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    Tokens tokens = reader.read(req);
    if (tokens.accessToken() == null) {
      challenge.unauthenticated(req, res, writer, false);
      return;
    }

    TokenValidator.Result result = validator.validate(tokens.accessToken());
    JWT bound;
    if (result instanceof TokenValidator.Result.Valid(JWT jwt)) {
      bound = jwt;
    } else if (result instanceof TokenValidator.Result.NetworkError) {
      challenge.unavailable(req, res);
      return;
    } else {
      if (tokens.refreshToken() == null) {
        challenge.unauthenticated(req, res, writer, true);
        return;
      }

      Tokens refreshed = Tools.refresh(config, tokens.refreshToken());
      if (refreshed == null
          || !(validator.validate(refreshed.accessToken()) instanceof TokenValidator.Result.Valid(JWT jwt))) {
        challenge.unauthenticated(req, res, writer, false);
        return;
      }

      writer.write(req, res, refreshed);
      bound = jwt;
    }

    ScopedValue.where(Tools.CURRENT_JWT, bound).call(() -> {
      chain.next(req, res);
      return null;
    });
  }
}
```

- [ ] **Step 4: Run, expect pass.** Run: `latte test` (with FusionAuth up).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/internal/Authentication.java src/test/java/org/lattejava/web/tests/oidc/AuthenticationTest.java
git commit -m "feat(oidc): single Authentication orchestrator for all profiles"
```

---

## Phase 4 — Session endpoints & entry point

### Task 14: `SessionEndpoints` middleware

**Files:**
- Create: `src/main/java/org/lattejava/web/oidc/internal/SessionEndpoints.java`
- Modify: `internal/LoginHandler.java`, `internal/CallbackHandler.java`, `internal/LogoutHandler.java`, `internal/LogoutReturnHandler.java`

Re-parameterize the four handlers to take `OIDCConfig` (client/endpoints) **and** `BrowserSettings` (paths, cookie names, `errorPage`, `postLoginPage`, `postLogoutPage`, return-to/state cookie names) and the `TokenWriter` (for the callback's cookie write). Preserve existing behaviors not in the spec: `LoginHandler` honors `return_to` (safe-path check) and `idp_hint`; `LogoutHandler` does a meta-refresh on POST. `CallbackHandler` writes tokens via `TokenWriter.write` (build a `Tokens` from the exchange) instead of `Tools.addAuthCookies` directly. `fullRedirectURI` becomes `URI.create(req.getBaseURL() + browser.callbackPath())`.

- [ ] **Step 1: Implement `SessionEndpoints`**

```java
package org.lattejava.web.oidc.internal;

import module org.lattejava.http;
import org.lattejava.web.Middleware;
import org.lattejava.web.MiddlewareChain;
import org.lattejava.web.oidc.BrowserSettings;
import org.lattejava.web.oidc.OIDCConfig;

/**
 * System middleware that handles the browser login flow paths — login, callback, logout, and logout-return — from
 * {@link BrowserSettings}. Any other path passes through. Install once per browser client; API-only clients never
 * install it.
 *
 * @author Brian Pontarelli
 */
public class SessionEndpoints implements Middleware {
  private final BrowserSettings browser;
  private final CallbackHandler callbackHandler;
  private final LoginHandler loginHandler;
  private final LogoutHandler logoutHandler;
  private final LogoutReturnHandler logoutReturnHandler;

  public SessionEndpoints(OIDCConfig config, BrowserSettings browser, JWKS jwks) {
    this.browser = browser;
    this.callbackHandler = new CallbackHandler(config, browser, jwks);
    this.loginHandler = new LoginHandler(config, browser);
    this.logoutHandler = new LogoutHandler(config, browser);
    this.logoutReturnHandler = new LogoutReturnHandler(browser);
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    String path = req.getPath();
    if (path.equals(browser.callbackPath())) { callbackHandler.handle(req, res); return; }
    if (path.equals(browser.loginPath())) { loginHandler.handle(req, res); return; }
    if (path.equals(browser.logoutPath())) { logoutHandler.handle(req, res); return; }
    if (path.equals(browser.logoutReturnPath())) { logoutReturnHandler.handle(req, res); return; }
    chain.next(req, res);
  }
}
```

> EXECUTOR: `import module org.lattejava.jwt;` for `JWKS`. Update each handler's constructor and field references: replace `config.loginPath()`/`config.callbackPath()`/`config.stateCookieName()`/`config.returnToCookieName()`/`config.errorPage()`/`config.postLoginPage()`/`config.postLogout()`/`config.idTokenCookieName()`/`config.fullRedirectURI(req)` with the `BrowserSettings` equivalents (`browser.loginPath()`, `URI.create(req.getBaseURL()+browser.callbackPath())`, etc.). In `CallbackHandler`, replace `Tools.addAuthCookies(...)` with `browser.tokenWriter().write(req, res, new Tokens(accessToken, refreshToken, idToken, expiresIn))` and replace the state/return-to cookie clears with `Tools.clearCookie(req, res, browser.stateCookieName())` / `browser.returnToCookieName()`. Keep the redirect-error flow but source `errorPage` from `browser`.

- [ ] **Step 2: Run the migrated callback/login/logout tests, expect pass.** Run: `latte test` (FusionAuth up).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/internal/SessionEndpoints.java src/main/java/org/lattejava/web/oidc/internal/LoginHandler.java src/main/java/org/lattejava/web/oidc/internal/CallbackHandler.java src/main/java/org/lattejava/web/oidc/internal/LogoutHandler.java src/main/java/org/lattejava/web/oidc/internal/LogoutReturnHandler.java
git commit -m "feat(oidc): SessionEndpoints middleware from BrowserSettings"
```

---

### Task 15: Rewrite `OIDC<U>` — mode-first factories + profile instance

**Files:**
- Modify: `src/main/java/org/lattejava/web/oidc/OIDC.java`

The instance holds `config`, `jwks`, `validator`, `reader`, `writer`, `challenge`, `translator`. Static factories build the three modes (defaults via the no-settings overloads). `sessionEndpoints(...)` is static and returns a `Middleware`. A process-wide JWKS cache (keyed by jwks URI string) dedups fetches.

- [ ] **Step 1: Implement**

```java
package org.lattejava.web.oidc;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.jwt;
import module org.lattejava.web;

import org.lattejava.web.oidc.internal.*;

public class OIDC<U> {
  private static final ConcurrentMap<String, JWKS> JWKS_CACHE = new ConcurrentHashMap<>();

  private final AuthChallenge challenge;
  private final OIDCConfig config;
  private final JWKS jwks;
  private final TokenReader reader;
  private final Function<JWT, U> translator;
  private final TokenValidator validator;
  private final TokenWriter writer;

  private OIDC(OIDCConfig config, TokenReader reader, TokenWriter writer, AuthChallenge challenge,
               Function<JWT, U> translator) {
    this.config = config;
    this.reader = reader;
    this.writer = writer;
    this.challenge = challenge;
    this.translator = translator;
    this.jwks = jwks(config);
    this.validator = new TokenValidator(config, jwks);
  }

  public static OIDC<JWT> api(OIDCConfig config) { return api(config, APISettings.builder().build(), Function.identity()); }
  public static <U> OIDC<U> api(OIDCConfig config, Function<JWT, U> translator) { return api(config, APISettings.builder().build(), translator); }
  public static <U> OIDC<U> api(OIDCConfig config, APISettings api, Function<JWT, U> translator) {
    return new OIDC<>(config, api.tokenReader(), api.tokenWriter(), new StatusChallenge(), translator);
  }

  public static <U> OIDC<U> custom(OIDCConfig config, TokenReader reader, TokenWriter writer, AuthChallenge challenge,
                                   Function<JWT, U> translator) {
    return new OIDC<>(config, reader, writer, challenge, translator);
  }

  public static JWT jwt() {
    if (!Tools.CURRENT_JWT.isBound()) { throw new UnauthenticatedException(); }
    return Tools.CURRENT_JWT.get();
  }

  public static Optional<JWT> optionalJWT() {
    return Tools.CURRENT_JWT.isBound() ? Optional.of(Tools.CURRENT_JWT.get()) : Optional.empty();
  }

  public static Middleware sessionEndpoints(OIDCConfig config) { return sessionEndpoints(config, BrowserSettings.builder().build()); }
  public static Middleware sessionEndpoints(OIDCConfig config, BrowserSettings browser) {
    return new SessionEndpoints(config, browser, jwks(config));
  }

  public static OIDC<JWT> spa(OIDCConfig config) { return spa(config, BrowserSettings.builder().build(), Function.identity()); }
  public static <U> OIDC<U> spa(OIDCConfig config, Function<JWT, U> translator) { return spa(config, BrowserSettings.builder().build(), translator); }
  public static <U> OIDC<U> spa(OIDCConfig config, BrowserSettings browser, Function<JWT, U> translator) {
    return new OIDC<>(config, browser.tokenReader(), browser.tokenWriter(), new StatusChallenge(), translator);
  }

  public static OIDC<JWT> ssr(OIDCConfig config) { return ssr(config, BrowserSettings.builder().build(), Function.identity()); }
  public static <U> OIDC<U> ssr(OIDCConfig config, Function<JWT, U> translator) { return ssr(config, BrowserSettings.builder().build(), translator); }
  public static <U> OIDC<U> ssr(OIDCConfig config, BrowserSettings browser, Function<JWT, U> translator) {
    return new OIDC<>(config, browser.tokenReader(), browser.tokenWriter(), new RedirectChallenge(browser), translator);
  }

  private static JWKS jwks(OIDCConfig config) {
    return JWKS_CACHE.computeIfAbsent(config.jwksEndpoint().toString(), uri -> JWKS.fromJWKS(uri).build());
  }

  public Middleware authenticated() {
    return new Authentication(config, reader, writer, challenge, validator);
  }

  public Middleware authorized(Authorizer authorizer) {
    return new Authorization(authorizer, challenge, writer);
  }

  public Middleware hasAllRoles(String... roles) {
    return new Authorization(Authorizer.hasAllRoles(config.roleExtractor(), roles), challenge, writer);
  }

  public Middleware hasAnyRole(String... roles) {
    return new Authorization(Authorizer.hasAnyRole(config.roleExtractor(), roles), challenge, writer);
  }

  public Optional<U> optionalUser() {
    return Tools.CURRENT_JWT.isBound() ? Optional.of(translator.apply(Tools.CURRENT_JWT.get())) : Optional.empty();
  }

  public U user() {
    if (!Tools.CURRENT_JWT.isBound()) { throw new UnauthenticatedException(); }
    return translator.apply(Tools.CURRENT_JWT.get());
  }
}
```

> EXECUTOR: `StatusChallenge`, `RedirectChallenge`, `Authentication`, `Authorization`, `SessionEndpoints`, `TokenValidator`, and `Tools` are in `org.lattejava.web.oidc.internal` (wildcard-imported). Confirm `TokenValidator` has a public constructor `(OIDCConfig, JWKS)` — it does.

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/lattejava/web/oidc/OIDC.java
git commit -m "feat(oidc): mode-first OIDC.ssr/spa/api factories + sessionEndpoints"
```

---

### Task 16: Remove the old types and build clean

**Files:**
- Delete: `Authenticated.java`, `JWTAuthenticated.java`, `APIAuthenticated.java`, `APIAuthorized.java`, `APIAuthorizer.java`, `HasAnyRole.java`, `HasAllRoles.java`, `TokenExtractor.java`, `TokenWriter.java` (the old `org.lattejava.web.oidc.TokenWriter` — note the new `TokenWriter` from Task 2 replaces it at the same path; ensure the new file is the one kept).

- [ ] **Step 1: Delete and grep for references**

```bash
git rm src/main/java/org/lattejava/web/oidc/Authenticated.java \
       src/main/java/org/lattejava/web/oidc/JWTAuthenticated.java \
       src/main/java/org/lattejava/web/oidc/APIAuthenticated.java \
       src/main/java/org/lattejava/web/oidc/APIAuthorized.java \
       src/main/java/org/lattejava/web/oidc/APIAuthorizer.java \
       src/main/java/org/lattejava/web/oidc/HasAnyRole.java \
       src/main/java/org/lattejava/web/oidc/HasAllRoles.java \
       src/main/java/org/lattejava/web/oidc/TokenExtractor.java
grep -rn "Authenticated\|APIAuth\|HasAnyRole\|HasAllRoles\|TokenExtractor\|apiAuthenticated\|apiAuthorized\|OIDC.create" src/main
```

- [ ] **Step 2: Build, expect clean compile**

Run: `latte build` — Expected: BUILD SUCCESS. Fix any straggler references the grep surfaced.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor(oidc): remove superseded middlewares and extractor types"
```

---

## Phase 5 — Test migration & new coverage

### Task 17: Migrate `BaseOIDCTest` and the test fixture

**Files:**
- Modify: `src/test/java/org/lattejava/web/tests/oidc/BaseOIDCTest.java`
- Modify: `src/main/java/org/lattejava/web/test/OIDCTestFixture.java` (if it references removed APIs)

- [ ] **Step 1: Update `BaseOIDCTest`**

```java
@BeforeSuite
public static void setupOIDC() {
  try {
    var config = OIDCConfig.builder()
                           .issuer(STANDARD_ISSUER)
                           .clientId(STANDARD_APP_ID)
                           .clientSecret(STANDARD_APP_SECRET)
                           .build();
    ssr = OIDC.ssr(config, JWT::subject);
    spa = OIDC.spa(config, JWT::subject);
    api = OIDC.api(config, JWT::subject);
    sessionEndpoints = OIDC.sessionEndpoints(config);
  } catch (Exception e) {
    fail("Unable to construct the OIDC configuration. FusionAuth is likely not running.", e);
  }
}
```

Add the static fields `ssr`, `spa`, `api` (typed `OIDC<String>`) and `sessionEndpoints` (`Middleware`). Update `OIDCTestFixture` to install `sessionEndpoints` where it previously installed the `OIDC` instance, and to use the mode profiles for protection.

- [ ] **Step 2: Commit**

```bash
git add src/test/java/org/lattejava/web/tests/oidc/BaseOIDCTest.java src/main/java/org/lattejava/web/test/OIDCTestFixture.java
git commit -m "test(oidc): migrate base test + fixture to mode-first API"
```

---

### Task 18: Migrate the existing OIDC tests

**Files:** all of `src/test/java/org/lattejava/web/tests/oidc/*Test.java`.

Mechanical mapping (apply per file; run `latte test` after each file compiles):
- `oidc.authenticated()` (browser) → `ssr.authenticated()` for redirect-expecting tests; `spa.authenticated()` for 401-expecting tests (the old `JWTAuthenticatedTest`).
- `oidc.apiAuthenticated()` → `api.authenticated()`.
- `oidc.apiAuthorized(a)` → `api.authorized(a)`; `APIAuthorizer` lambda type → `Authorizer`.
- `oidc.hasAnyRole/hasAllRoles` → `ssr.hasAnyRole/...` (or `spa.`/`api.` depending on the failure mode the test asserts).
- `web.install(oidc)` (system dispatch) → `web.install(sessionEndpoints)`.
- `RolesTest`: the 401/403 assertions stay for `spa`/`api`; add an SSR variant asserting the `forbiddenHandler` (default 403 HTML, or a custom handler) instead of a bare 403.
- Tests of removed `apiAudience` behavior: delete (audience is now `clientId`; covered by the multi-client test in Task 19).

- [ ] **Step 1: Migrate file-by-file, run `latte test` after each.**
- [ ] **Step 2: Commit** after the suite is green.

```bash
git add src/test/java/org/lattejava/web/tests/oidc
git commit -m "test(oidc): migrate suite to mode-first profiles"
```

---

### Task 19: New coverage from the spec's testing plan

**Files:** new/expanded tests in `src/test/java/org/lattejava/web/tests/oidc/`.

Add the cases from the spec not already covered:
- **Transport:** `CookieTransportTest`, `HeaderTransportTest` (Tasks 3–4); custom reader/writer supersedes name fields (API profile with a `CookieTokenReader` authenticates from cookies).
- **Challenge:** `RedirectChallengeTest` interstitial one-shot + custom `forbiddenHandler`/`unavailableHandler` honored; SPA clears cookies on 401; API leaves cookies untouched.
- **Orchestrator:** the per-branch `AuthenticationTest` (Task 13).
- **Multi-client integration:** two clients with distinct `clientId`s — a token minted for `webapp` is rejected at the `api` profile (audience), and vice versa. SSR+SPA from the same client share one JWKS fetch (assert `JWKS_CACHE` has one entry for the shared URI, or that only one HTTP fetch occurs via a counting Mock).

- [ ] **Step 1: Write the tests, run `latte test` (FusionAuth up).**
- [ ] **Step 2: Commit.**

```bash
git add src/test/java/org/lattejava/web/tests/oidc
git commit -m "test(oidc): transport/challenge/multi-client coverage"
```

---

### Task 20: Final verification

- [ ] **Step 1: Full clean build + test**

Run: `latte clean && latte build && latte test` (FusionAuth up). Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Grep for stale references**

Run: `grep -rn "OIDC.create\|apiAuthenticated\|TokenExtractor\|HasAnyRole\|validateAccessToken=false" src docs` — confirm only intended doc references remain.

- [ ] **Step 3: Final commit (if any cleanup).**

```bash
git add -A
git commit -m "chore(oidc): finalize mode-based rework"
```

---

## Self-review notes

- **Spec coverage:** two axes (Tasks 2–4 transport, 7–9 challenge), uniform validation (12), orchestrator (13), authorization-via-challenge (10–11), mode-first factories + JWKS dedup (15), session endpoints (14), config split (1, 5, 6), removals + migration table (16, 18). Interstitial (9, 13). `forbiddenHandler`/`unavailableHandler` (5, 9). Multi-client audience (19).
- **Preserved-but-unspecified behavior:** `return_to`/`idp_hint` in login, POST meta-refresh in logout (Task 14) — called out so they aren't lost.
- **Open decision baked in:** `Tools` keeps the cookie helpers (now name-parameterized) as the single cookie chokepoint, called by `CookieTokenReader/Writer` and `SessionEndpoints` — avoids duplicating cookie policy.
- **Type consistency:** `TokenValidator.Result.Valid(JWT)` / `NetworkError` / `Invalid` used consistently in Task 13; `Authorizer.hasAnyRole/hasAllRoles(Function<JWT,Set<String>>, String...)` signature matches its use in `OIDC.hasAnyRole/hasAllRoles` (Task 15) and the role test (Task 10).
