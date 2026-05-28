# OIDCTestFixture: redirect-URI flexibility and public-client support

**Date:** 2026-05-28
**Status:** Design

## Problem

`OIDCTestFixture` was built around one shape of OIDC client: a server-side ("SSR") web app whose redirect URI is a path on the same `WebTest`-hosted server, registered as a confidential client with a `client_secret`. Two assumptions are baked in:

1. **Redirect URI is derived, not specified.** `login(email, password, applicationId)` constructs the redirect URI as `http://localhost:<webTest.port><browser.callbackPath()>` and uses that both as the stop-condition for the authorize-redirect walk and as the `redirect_uri` form param on the token exchange. Tests of CLI tools, native/desktop apps, mobile apps, or any client whose redirect URI is not the WebTest server (e.g. RFC 8252 loopback like `http://127.0.0.1:8765/callback`, or a custom scheme like `myapp://callback`) have no way to express that URI to the fixture.
2. **Confidential-client token auth is hard-coded.** The token-exchange request unconditionally sends an HTTP Basic header built from `clientId:clientSecret`. Public clients (CLI/native/SPA) typically have no `client_secret` — they authenticate at `/token` by including `client_id` in the form body and relying on PKCE. The fixture cannot exercise this shape, and `OIDCConfig` cannot even be *constructed* without a `clientSecret`.

This spec covers both: making the redirect URI a caller-supplied value with a sensible SSR default, and making `OIDCConfig` aware of the public/confidential distinction so it can validate appropriately and so the production token-exchange path picks the right `/token` auth shape.

## Scope

In scope:

- `OIDCConfig`: add a `publicClient` flag, relax `clientSecret` validation when set, reject the combination that has no working semantics (`publicClient=true && validateAccessToken=false`).
- `Tools.postToken` (internal): switch the `/token` auth shape on `config.publicClient()` — Basic for confidential, `client_id` in form body for public. Used by the SSR refresh path.
- `OIDCTestFixture`: add a `redirectURI`-taking overload of `login`; keep the existing three-arg as an SSR convenience; consult `config.publicClient()` to choose token-auth shape.

Out of scope:

- Other OAuth 2 grant types (device authorization, ROPC, client_credentials). The fixture remains an authorization-code + PKCE driver.
- `TokenValidator.introspect`: no change. RFC 7662 introspection requires client authentication at most IdPs and is incompatible with the public-client model, so the new build-time guard in `OIDCConfig` prevents the configuration from existing in the first place — `introspect` is never reached with a null `clientSecret`.
- Decoupling the fixture from `WebTest`. Even non-web tests typically want a `WebTest` as an HTTP client to hit a protected API; the cookie side-effect on `login` is harmless when unused. Can be revisited later.
- Per-call override of the public/confidential mode (one `OIDCConfig` per fixture covers the realistic test scenarios; see "Trade-offs" below).

## Design

### `OIDCConfig`

Add one field and one builder method:

```java
public record OIDCConfig(
    ...,
    boolean publicClient
) { ... }
```

```java
public static class Builder {
  private boolean publicClient = false;

  public Builder publicClient(boolean value) {
    this.publicClient = value;
    return this;
  }
}
```

Replace the unconditional `clientSecret` required-check with a mode-aware one, and add a guard against the introspection combination:

```java
// inside Builder.build(), before the existing checks
if (!publicClient && (clientSecret == null || clientSecret.isBlank())) {
  throw new IllegalArgumentException(
      "clientSecret must not be null or blank for confidential clients — "
          + "set publicClient(true) for public clients (CLI, native, SPA)");
}

if (publicClient && !validateAccessToken) {
  throw new IllegalArgumentException(
      "publicClient=true requires validateAccessToken=true — "
          + "RFC 7662 introspection requires client authentication and is incompatible with public clients");
}
```

The existing `clientId` check is unchanged — `clientId` is required for both modes.

Javadoc on the record and on `publicClient(boolean)` documents the trade-off: public clients omit the secret, must validate tokens locally (no introspection), and authenticate at `/token` by including `client_id` in the form body.

### `Tools.postToken`

Replace the unconditional Basic header with a branch on `config.publicClient()`:

```java
public static TokenEndpointResponse postToken(OIDCConfig config, Map<String, String> form) throws IOException, InterruptedException {
  HttpRequest.Builder builder = HttpRequest.newBuilder(config.tokenEndpoint())
                                           .header("Content-Type", "application/x-www-form-urlencoded");
  Map<String, String> body = form;
  if (config.publicClient()) {
    body = new LinkedHashMap<>(form);
    body.put("client_id", config.clientId());
  } else {
    String basic = "Basic " + Base64.getEncoder().encodeToString(
        (config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8));
    builder.header("Authorization", basic);
  }

  HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(Tools.formEncode(body))).build();
  HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
  return new TokenEndpointResponse(res.statusCode(), res.body());
}
```

This affects the SSR refresh path uniformly. Confidential-client behavior is unchanged; public-client refresh now works.

`TokenValidator.introspect` is unchanged — the `OIDCConfig` build-time guard ensures it never runs against a config without a `clientSecret`.

### `OIDCTestFixture`

Two changes, both additive:

**Add a four-arg `login` overload and delegate from the three-arg:**

```java
public Tokens login(String email, String password, String applicationId) throws Exception {
  return login(email, password, applicationId,
      "http://localhost:" + webTest.port + browser.callbackPath());
}

public Tokens login(String email, String password, String applicationId, String redirectURI) throws Exception {
  AuthorizationCode auth = fetchAuthorizationCode(email, password, applicationId, redirectURI);

  Map<String, String> form = new LinkedHashMap<>();
  form.put("grant_type", "authorization_code");
  form.put("code", auth.code());
  form.put("redirect_uri", redirectURI);
  form.put("code_verifier", auth.state());

  HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(config.tokenEndpoint())
                                              .header("Content-Type", "application/x-www-form-urlencoded");
  if (config.publicClient()) {
    form.put("client_id", applicationId);
  } else {
    String basic = "Basic " + Base64.getEncoder().encodeToString(
        (applicationId + ":" + clientSecretFor(applicationId)).getBytes(StandardCharsets.UTF_8));
    reqBuilder.header("Authorization", basic);
  }

  HttpRequest req = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(formEncode(form))).build();
  // ... (existing response parsing, cookie writing, Tokens return — unchanged)
}
```

The four-arg form is the new primary; the three-arg form keeps every existing call site working by deriving the SSR redirect URI.

**No new method for the public-client case.** The OIDC-level choice (Basic vs form-body) is read off `config.publicClient()`, so call sites differ only in which redirect URI they pass:

```java
// SSR (existing)
fixture.login(EMAIL, PW, STANDARD_APP_ID);

// CLI / native loopback (RFC 8252)
fixture.login(EMAIL, PW, CLI_APP_ID, "http://127.0.0.1:8765/callback");

// desktop / mobile custom scheme
fixture.login(EMAIL, PW, NATIVE_APP_ID, "myapp://callback");
```

The CLI/native call sites use a fixture whose `OIDCConfig` was built with `publicClient(true)` and no secret.

`fetchAuthorizationCode` is unchanged — it already takes `redirectURI` as a parameter, and the authorize-redirect walk plus PKCE machinery is identical for both client types.

`clientSecretFor(applicationId)` stays. It's still how `FusionAuthFixture` resolves per-app secrets for multi-app confidential-client testing. When `config.publicClient()` is true, `login` never calls it.

## Data flow

Authorization-code login, public client:

1. Caller invokes `fixture.login(email, password, appId, redirectURI)`.
2. `fetchAuthorizationCode` walks the IdP's hosted login: GET `/authorize` with `client_id`, `redirect_uri`, `state`, `code_challenge`, `code_challenge_method=S256`; if not already a redirect to `redirectURI`, POST `/authorize` with `loginId` + `password`; capture the `code` from the redirect to `redirectURI`.
3. POST `/token` with `grant_type=authorization_code`, `code`, `redirect_uri`, `code_verifier`, `client_id` — **no Authorization header**.
4. Parse `access_token`, `id_token`, `refresh_token`, `expires_in`; write tokens to `webTest.cookies`; return `Tokens`.

Authorization-code login, confidential client: identical except step 3 sends `Authorization: Basic <clientId:clientSecretFor(applicationId)>` and omits `client_id` from the form.

## Error handling

- `OIDCConfig.Builder.build()` throws `IllegalArgumentException` on the two new invalid combinations (confidential client with no secret; public client with introspection). Existing validation messages keep their `[value]` formatting; new messages follow the same convention.
- `Tools.postToken` and the fixture's token-exchange path keep their existing exception behavior — only the request shape changes.
- `OIDCTestFixture.login` is unchanged in its error model: any non-200 from `/token` raises `IllegalStateException` with `[status]` and `[body]`, and the authorize-walk continues to throw `IllegalStateException` if it doesn't terminate at the redirect URI.

## Testing

- **`OIDCConfigTest`** (add cases):
  - `publicClient=true` with no secret → builds successfully.
  - `publicClient=false` with no secret → `IllegalArgumentException`.
  - `publicClient=true` with `validateAccessToken=false` → `IllegalArgumentException`.
  - `publicClient=true && validateAccessToken=true` with no secret → builds successfully.
- **`OIDCTestFixtureTest`** (add cases):
  - Existing three-arg `login` still passes (regression).
  - Four-arg `login(..., redirectURI)` against the SSR app works when redirectURI matches the default (proves delegation is correct).
- **New `PublicClientLoginTest`** under `src/test/java/org/lattejava/web/tests/oidc/`:
  - Build a public-client `OIDCConfig` for a FusionAuth app provisioned as a public client (kickstart change required — see "Open items"), call `fixture.login(EMAIL, PW, PUBLIC_APP_ID, redirectURI)` with a loopback redirect URI, assert the tokens come back and the access token is JWT-validatable.
- **`Tools.postToken`** is exercised indirectly through `RefreshTest`. Add a public-client refresh case if a public-client app is provisioned in kickstart.

All tests run against the kickstart-provisioned FusionAuth at `http://localhost:9012` per the existing convention.

## Trade-offs

- **One mode per `OIDCConfig`.** Putting `publicClient` on `OIDCConfig` rather than as a per-`login` parameter means one fixture instance can drive logins for many apps as long as they share the same client type. `FusionAuthFixture` works because its four kickstart apps are all confidential. A test that needs to mix public + confidential logins must construct separate `OIDCConfig` + `OIDCTestFixture` instances. This matches the realistic test landscape and keeps the call sites flat (no per-call mode argument); the alternative — a `loginPublicClient` method, or a `ClientType` parameter — adds API surface for a case that doesn't exist yet.
- **`WebTest` still required.** Even CLI/native tests typically want an HTTP client to hit a protected API with the returned access token, so `WebTest` stays as the transport. The cookie side-effect of `login` is harmless when unused.
- **Public clients can't introspect.** The build-time guard (`publicClient=true && validateAccessToken=false` rejected) trades flexibility for correctness — there is no widely supported way for a public client to authenticate against an RFC 7662 introspection endpoint, so allowing the combination would only produce runtime failures.

## Open items

- Provisioning a public-client app in `src/test/fusionauth/kickstart/kickstart.json` to back `PublicClientLoginTest`. Pick a fixed UUID, add it to `FusionAuthFixture` as `PUBLIC_APP_ID`, leave `clientSecret` out (or null) in the kickstart config so FusionAuth treats it as public. This is implementation work, not a design choice.
