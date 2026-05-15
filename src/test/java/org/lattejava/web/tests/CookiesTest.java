/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Tests the Coookies class.
 *
 * @author Brian Pontarelli
 */
public class CookiesTest extends BaseWebTest {
  private static byte[] key(int b) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) b);
    return bytes;
  }

  @Test
  public void autoSecureBasedOnXForwardedProto() throws Exception {
    Cookies cookies = Cookies.newInstance();
    try (var web = new Web()) {
      web.get("/set", (req, res) -> cookies.write("prefs", "dark").to(req, res));
      web.start(PORT);

      try (HttpClient client = HttpClient.newBuilder().build()) {
        HttpRequest insecure = HttpRequest.newBuilder(URI.create(BASE_URL + "/set")).GET().build();
        HttpResponse<String> response1 = client.send(insecure, HttpResponse.BodyHandlers.ofString());
        assertFalse(getCookie(response1, "prefs").secure, "Secure should be false over plain http://localhost");

        HttpRequest forwarded = HttpRequest.newBuilder(URI.create(BASE_URL + "/set"))
                                           .header("X-Forwarded-Proto", "https")
                                           .GET().build();
        HttpResponse<String> response2 = client.send(forwarded, HttpResponse.BodyHandlers.ofString());
        assertTrue(getCookie(response2, "prefs").secure, "Secure should be true with X-Forwarded-Proto: https");
      }
    }
  }

  @Test
  public void clearEmitsMaxAgeZeroAndDefaultPath() throws Exception {
    Cookies cookies = Cookies.newInstance();
    try (var web = new Web()) {
      web.get("/clear", (req, res) -> cookies.clear("prefs").from(req, res));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/clear");
      Cookie cookie = getCookie(response, "prefs");
      assertNotNull(cookie);
      assertEquals(cookie.value, "");
      assertEquals(cookie.maxAge, Long.valueOf(0));
      assertEquals(cookie.path, "/");
    }
  }

  @Test
  public void defaultAttributesAreApplied() throws Exception {
    Cookies cookies = Cookies.newInstance();
    try (var web = new Web()) {
      web.get("/set", (req, res) -> cookies.write("prefs", "dark").to(req, res));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/set");
      Cookie cookie = getCookie(response, "prefs");
      assertTrue(cookie.httpOnly, "HttpOnly should default to true");
      assertEquals(cookie.sameSite, Cookie.SameSite.Strict);
      assertNull(cookie.maxAge, "Max-Age should be unset by default");
    }
  }

  @Test
  public void defaultPathIsSlash() throws Exception {
    Cookies cookies = Cookies.newInstance();
    try (var web = new Web()) {
      web.get("/set", (req, res) -> cookies.write("prefs", "dark").to(req, res));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/set");
      Cookie cookie = getCookie(response, "prefs");
      assertEquals(cookie.path, "/");
    }
  }

  @Test
  public void encryptedAADBoundToCookieName() throws Exception {
    Cookies cookies = Cookies.encryptionKeys(key(0x11));
    try (var web = new Web()) {
      web.get("/set", (req, res) -> cookies.write("prefs", "secret").encrypted().to(req, res));
      web.get("/read-session", (req, res) -> {
        try {
          cookies.read("session").encrypted().from(req);
          res.setStatus(200);
        } catch (CookieIntegrityException e) {
          res.setStatus(418);
          res.getOutputStream().write(e.reason().name().getBytes(StandardCharsets.UTF_8));
        }
      });
      web.start(PORT);

      HttpResponse<String> setResp = send("GET", "/set");
      String wire = getCookie(setResp, "prefs").value;

      HttpResponse<String> echoResp = get("/read-session", "session=" + wire);
      assertEquals(echoResp.statusCode(), 418);
      assertEquals(echoResp.body(), "DECRYPT_FAILED");
    }
  }

  @Test
  public void encryptedKeyRotation() throws Exception {
    byte[] keyA = key(0x11);
    byte[] keyB = key(0x22);
    byte[] keyC = key(0x33);

    Cookies cookiesA = Cookies.encryptionKeys(keyA);
    Cookies cookiesBA = Cookies.encryptionKeys(keyB, keyA);
    Cookies cookiesC = Cookies.encryptionKeys(keyC);

    try (var web = new Web()) {
      web.get("/set", (req, res) -> cookiesA.write("session", "secret").encrypted().to(req, res));
      web.get("/echo-rotated", (req, res) -> {
        String v = cookiesBA.read("session").encrypted().from(req);
        res.setStatus(200);
        res.getOutputStream().write(v.getBytes(StandardCharsets.UTF_8));
      });
      web.get("/echo-dropped", (req, res) -> {
        try {
          cookiesC.read("session").encrypted().from(req);
          res.setStatus(200);
        } catch (CookieIntegrityException e) {
          res.setStatus(418);
          res.getOutputStream().write(e.reason().name().getBytes(StandardCharsets.UTF_8));
        }
      });
      web.start(PORT);

      HttpResponse<String> setResp = send("GET", "/set");
      String wire = getCookie(setResp, "session").value;

      HttpResponse<String> rotatedResp = get("/echo-rotated", "session=" + wire);
      assertEquals(rotatedResp.body(), "secret");

      HttpResponse<String> droppedResp = get("/echo-dropped", "session=" + wire);
      assertEquals(droppedResp.statusCode(), 418);
      assertEquals(droppedResp.body(), "DECRYPT_FAILED");
    }
  }

  @Test
  public void encryptedOnNoKeysHelperThrows() {
    Cookies cookies = Cookies.newInstance();
    IllegalStateException writeEx = expectThrows(IllegalStateException.class,
        () -> cookies.write("session", "v").encrypted());
    assertEquals(writeEx.getMessage(), "Cookies helper was not configured with encryption keys");

    IllegalStateException readEx = expectThrows(IllegalStateException.class,
        () -> cookies.read("session").encrypted());
    assertEquals(readEx.getMessage(), "Cookies helper was not configured with encryption keys");
  }

  @Test
  public void encryptedReadMalformedNonBase64URL() throws Exception {
    Cookies cookies = Cookies.encryptionKeys(key(0x11));
    try (var web = new Web()) {
      web.get("/echo", (req, res) -> {
        try {
          cookies.read("session").encrypted().from(req);
          res.setStatus(200);
        } catch (CookieIntegrityException e) {
          res.setStatus(418);
          res.getOutputStream().write(e.reason().name().getBytes(StandardCharsets.UTF_8));
        }
      });
      web.start(PORT);

      HttpResponse<String> resp = get("/echo", "session=not!base64!!!");
      assertEquals(resp.statusCode(), 418);
      assertEquals(resp.body(), "MALFORMED");
    }
  }

  @Test
  public void encryptedReadMalformedTooShort() throws Exception {
    Cookies cookies = Cookies.encryptionKeys(key(0x11));
    String tooShort = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[27]);
    try (var web = new Web()) {
      web.get("/echo", (req, res) -> {
        try {
          cookies.read("session").encrypted().from(req);
          res.setStatus(200);
        } catch (CookieIntegrityException e) {
          res.setStatus(418);
          res.getOutputStream().write(e.reason().name().getBytes(StandardCharsets.UTF_8));
        }
      });
      web.start(PORT);

      HttpResponse<String> resp = get("/echo", "session=" + tooShort);
      assertEquals(resp.statusCode(), 418);
      assertEquals(resp.body(), "MALFORMED");
    }
  }

  @Test
  public void encryptedReadOfTamperedValueThrows() throws Exception {
    Cookies cookies = Cookies.encryptionKeys(key(0x11));
    try (var web = new Web()) {
      web.get("/set", (req, res) -> cookies.write("session", "secret").encrypted().to(req, res));
      web.get("/echo", (req, res) -> {
        try {
          cookies.read("session").encrypted().from(req);
          res.setStatus(200);
        } catch (CookieIntegrityException e) {
          res.setStatus(418);
          res.getOutputStream().write(e.reason().name().getBytes(StandardCharsets.UTF_8));
        }
      });
      web.start(PORT);

      HttpResponse<String> setResp = send("GET", "/set");
      String wire = getCookie(setResp, "session").value;

      byte[] raw = Base64.getUrlDecoder().decode(wire);
      raw[20] = (byte) (raw[20] ^ 0x01);
      String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

      HttpResponse<String> echoResp = get("/echo", "session=" + tampered);
      assertEquals(echoResp.statusCode(), 418);
      assertEquals(echoResp.body(), "DECRYPT_FAILED");
    }
  }

  @Test
  public void encryptedRoundtrip() throws Exception {
    Cookies cookies = Cookies.encryptionKeys(key(0x11));
    try (var web = new Web()) {
      web.get("/set", (req, res) -> cookies.write("session", "secret-value").encrypted().to(req, res));
      web.get("/echo", (req, res) -> {
        String v = cookies.read("session").encrypted().from(req);
        res.setStatus(200);
        res.getOutputStream().write((v == null ? "<null>" : v).getBytes(StandardCharsets.UTF_8));
      });
      web.start(PORT);

      HttpResponse<String> setResp = send("GET", "/set");
      Cookie cookie = getCookie(setResp, "session");
      assertNotNull(cookie);
      assertNotEquals(cookie.value, "secret-value", "Cookie value should be ciphertext, not plaintext");

      HttpResponse<String> echoResp = get("/echo", "session=" + cookie.value);
      assertEquals(echoResp.body(), "secret-value");
    }
  }

  @Test
  public void encryptionKeysFromSecretKey() throws Exception {
    SecretKeySpec sk = new SecretKeySpec(key(0x33), "AES");
    Cookies cookies = Cookies.encryptionKeys(sk);
    try (var web = new Web()) {
      web.get("/set", (req, res) -> cookies.write("session", "fromSk").encrypted().to(req, res));
      web.get("/echo", (req, res) -> {
        String v = cookies.read("session").encrypted().from(req);
        res.setStatus(200);
        res.getOutputStream().write(v.getBytes(StandardCharsets.UTF_8));
      });
      web.start(PORT);

      HttpResponse<String> setResp = send("GET", "/set");
      String wire = getCookie(setResp, "session").value;
      HttpResponse<String> echoResp = get("/echo", "session=" + wire);
      assertEquals(echoResp.body(), "fromSk");
    }
  }

  @Test
  public void encryptionKeysRejectsNonAESSecretKey() {
    SecretKey hmac = new SecretKeySpec(key(0x44), "HmacSHA256");
    IllegalArgumentException ex = expectThrows(IllegalArgumentException.class,
        () -> Cookies.encryptionKeys(hmac));
    assertEquals(ex.getMessage(), "Encryption key algorithm must be [AES]: [HmacSHA256]");
  }

  @Test
  public void encryptionKeysRejectsWrongLength() {
    IllegalArgumentException ex = expectThrows(IllegalArgumentException.class,
        () -> Cookies.encryptionKeys(new byte[16]));
    assertEquals(ex.getMessage(), "Encryption key must be 32 bytes for AES-256: [16]");
  }

  @Test
  public void encryptionKeysRejectsZeroArgs() {
    IllegalArgumentException ex = expectThrows(IllegalArgumentException.class,
        () -> Cookies.encryptionKeys(new byte[0][]));
    assertTrue(ex.getMessage().contains("Cookies.newInstance"),
        "Should steer caller toward newInstance(); message was [" + ex.getMessage() + "]");
  }

  @Test
  public void perCookieOverridesAreApplied() throws Exception {
    Cookies cookies = Cookies.newInstance();
    try (var web = new Web()) {
      web.get("/set", (req, res) -> cookies.write("prefs", "dark")
                                           .path("/app")
                                           .domain("example.com")
                                           .maxAge(Duration.ofMinutes(15))
                                           .httpOnly(false)
                                           .secure(true)
                                           .sameSite(Cookie.SameSite.Lax)
                                           .to(req, res));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/set");
      Cookie cookie = getCookie(response, "prefs");
      assertEquals(cookie.path, "/app");
      assertEquals(cookie.domain, "example.com");
      assertEquals(cookie.maxAge, Long.valueOf(15 * 60));
      assertFalse(cookie.httpOnly);
      assertTrue(cookie.secure);
      assertEquals(cookie.sameSite, Cookie.SameSite.Lax);
    }
  }

  @Test
  public void readReturnsNullWhenCookieAbsent() throws Exception {
    Cookies cookies = Cookies.newInstance();
    try (var web = new Web()) {
      web.get("/echo", (req, res) -> {
        String v = cookies.read("prefs").from(req);
        res.setStatus(200);
        res.getOutputStream().write((v == null ? "<null>" : v).getBytes(StandardCharsets.UTF_8));
      });
      web.start(PORT);

      HttpResponse<String> response = get("/echo", null);
      assertEquals(response.body(), "<null>");
    }
  }

  @Test
  public void readReturnsRawCookieValue() throws Exception {
    Cookies cookies = Cookies.newInstance();
    try (var web = new Web()) {
      web.get("/echo", (req, res) -> {
        String v = cookies.read("prefs").from(req);
        res.setStatus(200);
        res.getOutputStream().write((v == null ? "<null>" : v).getBytes(StandardCharsets.UTF_8));
      });
      web.start(PORT);

      HttpResponse<String> response = get("/echo", "prefs=dark");
      assertEquals(response.body(), "dark");
    }
  }

  @Test
  public void writePlaintextRoundtrip() throws Exception {
    Cookies cookies = Cookies.newInstance();
    try (var web = new Web()) {
      web.get("/set", (req, res) -> cookies.write("prefs", "dark").to(req, res));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/set");
      Cookie cookie = getCookie(response, "prefs");
      assertNotNull(cookie, "Set-Cookie header missing");
      assertEquals(cookie.value, "dark");
    }
  }
}
