/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.middleware;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.lattejava.web.tests;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Tests the FlashMessages middleware and the Flash wrapper.
 *
 * @author Brian Pontarelli
 */
public class FlashMessagesTest extends BaseWebTest {
  private static final String SPECIAL = "He said \"hi\" \\ ✓ new\nline, \"messages\":[\"x\"]";
  private static final String SPECIAL_TYPE = "type \"quoted\" \\ ✓ {\"info\":[]}";

  private static void assertClearsCookie(HttpResponse<?> response) {
    Cookie cookie = getCookie(response, Flash.COOKIE_NAME);
    assertNotNull(cookie, "Expected a Set-Cookie header that clears the flash cookie");
    assertEquals(cookie.value, "");
    assertEquals(cookie.maxAge, Long.valueOf(0));
  }

  private static void assertNoSetCookie(HttpResponse<?> response) {
    assertTrue(response.headers().allValues("Set-Cookie").isEmpty(),
        "Expected no Set-Cookie header but got " + response.headers().allValues("Set-Cookie"));
  }

  /**
   * Decodes a cookie value the way Flash does, for inspecting the wire format.
   */
  private static String decode(String value) {
    return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
  }

  /**
   * Encodes a JSON document the way Flash does, for hand-crafting cookie values.
   */
  private static String encode(String json) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Builds a Web with the FlashMessages middleware installed globally and a set of routes that add, clear, and render
   * messages. Routes that add a message take it from the {@code X-Message} request header and its type from the
   * {@code X-Type} header, defaulting to {@code info}.
   */
  private static Web flashServer() {
    Web web = new Web();
    web.install(new FlashMessages());
    web.post("/add", (req, res) -> {
      new Flash(req).addMessage(type(req), req.getHeader("X-Message"));
      res.sendRedirect("/page");
    });
    web.post("/add-303", (req, res) -> {
      new Flash(req).addMessage(type(req), req.getHeader("X-Message"));
      res.sendRedirect("/page", 303);
    });
    web.get("/add-and-redirect", (req, res) -> {
      new Flash(req).addMessage(type(req), req.getHeader("X-Message"));
      res.sendRedirect("/page", 307);
    });
    web.get("/add-and-render", (req, res) -> {
      Flash flash = new Flash(req);
      flash.addMessage(type(req), req.getHeader("X-Message"));
      render(flash, res);
    });
    web.get("/add-no-body", (req, res) -> {
      Flash flash = new Flash(req);
      flash.addMessage(type(req), req.getHeader("X-Message"));
      res.setStatus(200);
      res.setHeader("X-Messages", join(flash));
    });
    web.post("/add-special", (req, res) -> {
      new Flash(req).addMessage(SPECIAL_TYPE, SPECIAL);
      res.sendRedirect("/page");
    });
    web.post("/add-stay", (req, res) -> {
      new Flash(req).addMessage(type(req), req.getHeader("X-Message"));
      res.setStatus(200);
    });
    web.post("/add-two", (req, res) -> {
      new Flash(req).addMessage("info", "One").addMessage("info", "Two");
      res.sendRedirect("/page");
    });
    web.post("/clear", (req, res) -> {
      new Flash(req).clear();
      res.sendRedirect("/page");
    });
    web.get("/hop1", (_, res) -> res.sendRedirect("/hop2"));
    web.get("/hop2", (_, res) -> res.sendRedirect("/page"));
    web.get("/page", (req, res) -> render(new Flash(req), res));
    web.get("/page-no-body", (req, res) -> {
      Flash flash = new Flash(req);
      res.setStatus(200);
      res.setHeader("X-Messages", join(flash));
      res.setHeader("X-Has-Messages", String.valueOf(flash.hasMessages()));
    });
    web.get("/redirect", (_, res) -> res.sendRedirect("/page"));
    web.post("/stay", (_, res) -> res.setStatus(200));
    web.start(PORT);
    return web;
  }

  /**
   * Flattens the messages to {@code type:message} pairs joined by {@code |}, types in map order.
   */
  private static String join(Flash flash) {
    return flash.messages()
                .entrySet()
                .stream()
                .flatMap(e -> e.getValue().stream().map(m -> e.getKey() + ":" + m))
                .collect(Collectors.joining("|"));
  }

  private static void render(Flash flash, HTTPResponse res) throws IOException {
    res.setStatus(200);
    res.setContentType("text/plain; charset=utf-8");
    res.getWriter().write(join(flash));
    res.getWriter().flush();
  }

  private static String type(HTTPRequest req) {
    String type = req.getHeader("X-Type");
    return type != null ? type : "info";
  }

  @Test
  public void addMessage_accumulatesAcrossRedirects() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "One")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);
      tester.withHeader("X-Message", "Two")
            .withHeader("X-Type", "error")
            .get("/add-and-redirect")
            .assertRedirect(307, "/page")
            .reset(ResetItem.Request);
      tester.withHeader("X-Message", "Three")
            .post("/add-stay")
            .assertStatus(200)
            .reset(ResetItem.Request);

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:One|info:Three|error:Two"));
      assertNull(tester.cookies.get("flash"));
    }
  }

  @Test
  public void addMessage_duringRenderingGET_visibleButNotCarriedForward() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "Now")
            .get("/add-and-render")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:Now"))
            .assertResponse(FlashMessagesTest::assertNoSetCookie)
            .reset(ResetItem.Request);
      assertNull(tester.cookies.get("flash"));

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty);
    }
  }

  @Test
  public void addMessage_groupsByType() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "One")
            .withHeader("X-Type", "error")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);
      tester.withHeader("X-Message", "Two")
            .withHeader("X-Type", "success")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);
      tester.withHeader("X-Message", "Three")
            .withHeader("X-Type", "error")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);
      assertEquals(decode(tester.cookies.get("flash").value), "{\"messages\":{\"error\":[\"One\",\"Three\"],\"success\":[\"Two\"]}}");

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("error:One|error:Three|success:Two"));
      assertNull(tester.cookies.get("flash"));
    }
  }

  @Test
  public void addMessage_orderPreservedWithinRequest() {
    try (var _ = flashServer()) {
      new WebTest(PORT).post("/add-two")
                       .assertRedirect(302, "/page")
                       .reset(ResetItem.Request)
                       .get("/page")
                       .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:One|info:Two"));
    }
  }

  @Test
  public void addMessage_specialCharactersRoundtrip() {
    try (var _ = flashServer()) {
      new WebTest(PORT).post("/add-special")
                       .assertRedirect(302, "/page")
                       .reset(ResetItem.Request)
                       .get("/page")
                       .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo(SPECIAL_TYPE + ":" + SPECIAL));
    }
  }

  @Test
  public void addMessage_survivesMultipleRedirects() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "Saved!")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);
      String wire = tester.cookies.get("flash").value;

      tester.get("/hop1")
            .assertRedirect(302, "/hop2")
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
      tester.get("/hop2")
            .assertRedirect(302, "/page")
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
      assertEquals(tester.cookies.get("flash").value, wire, "Unchanged redirects must not rewrite the cookie");

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:Saved!"));
      assertNull(tester.cookies.get("flash"));
    }
  }

  @Test
  public void addMessage_survivesRedirect_consumedByGET() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "Saved!")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);
      assertNotNull(tester.cookies.get("flash"), "The redirect response should set the flash cookie");

      tester.get("/page")
            .assertStatus(200)
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:Saved!"))
            .assertResponse(FlashMessagesTest::assertClearsCookie);
      assertNull(tester.cookies.get("flash"), "The rendering GET should clear the flash cookie");

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty)
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
    }
  }

  @Test
  public void clear_discardsPendingMessages() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "Saved!")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);
      tester.withHeader("X-Message", "Failed!")
            .withHeader("X-Type", "error")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);
      tester.post("/clear")
            .assertRedirect(302, "/page")
            .assertResponse(FlashMessagesTest::assertClearsCookie);
      assertNull(tester.cookies.get("flash"));

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty)
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
    }
  }

  @Test
  public void cookie_attributes() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "Saved!")
            .post("/add")
            .assertRedirect(302, "/page");

      Cookie cookie = tester.cookies.get("flash");
      assertNotNull(cookie);
      assertTrue(cookie.httpOnly, "HttpOnly should be set");
      assertEquals(cookie.sameSite, Cookie.SameSite.Lax);
      assertEquals(cookie.path, "/");
      assertNull(cookie.maxAge, "The flash cookie should be a session cookie");
      assertTrue(cookie.value.matches("[A-Za-z0-9_-]+"), "The cookie value should be Base64URL: [" + cookie.value + "]");
      assertEquals(decode(cookie.value), "{\"messages\":{\"info\":[\"Saved!\"]}}");
    }
  }

  @Test
  public void cookie_handCrafted_isReadable() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withCookie("flash", encode("{\"messages\":{\"info\":[\"Hi\",\"There\"],\"error\":[\"Oops\"]}}"))
            .get("/page")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:Hi|info:There|error:Oops"))
            .assertResponse(FlashMessagesTest::assertClearsCookie);
      assertNull(tester.cookies.get("flash"));
    }
  }

  @Test
  public void cookie_handCrafted_nullsDropped() {
    try (var _ = flashServer()) {
      new WebTest(PORT).withCookie("flash", encode("{\"messages\":{\"info\":[null,\"ok\",null],\"error\":null,\"warn\":[null]}}"))
                       .get("/page")
                       .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:ok"));
    }
  }

  @Test
  public void cookie_handCrafted_wrongShapes_treatedAsEmpty() {
    try (var _ = flashServer()) {
      for (String json : List.of("{\"messages\":{\"info\":[1,\"ok\"]}}", "{\"messages\":{\"info\":[true]}}",
          "{\"messages\":{\"info\":[\"ok\",{\"a\":1}]}}", "{\"messages\":{\"info\":[\"ok\",[\"nested\"]]}}",
          "{\"messages\":{\"info\":\"ok\"}}", "{\"messages\":{\"info\":1}}", "{\"messages\":{\"info\":{\"a\":[\"ok\"]}}}",
          "{\"messages\":[\"ok\"]}", "{\"messages\":\"ok\"}", "{\"messages\":null}", "[\"ok\"]")) {
        var tester = new WebTest(PORT);
        tester.withCookie("flash", encode(json))
              .get("/page")
              .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty)
              .assertResponse(FlashMessagesTest::assertClearsCookie);
        assertNull(tester.cookies.get("flash"), "Cookie should be cleared for [" + json + "]");
      }
    }
  }

  @Test
  public void cookie_invalidJSON_treatedAsEmpty() {
    try (var _ = flashServer()) {
      for (String value : List.of(encode("not json"), encode("{\"messages\":{\"info\":[\"unterminated\"]}"))) {
        var tester = new WebTest(PORT);
        tester.withCookie("flash", value)
              .get("/page")
              .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty)
              .assertResponse(FlashMessagesTest::assertClearsCookie);
        assertNull(tester.cookies.get("flash"), "Cookie should be cleared for [" + value + "]");
      }
    }
  }

  @Test
  public void cookie_unreadable_clearedByGET() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withCookie("flash", "not!valid!base64")
            .get("/page")
            .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty)
            .assertResponse(FlashMessagesTest::assertClearsCookie);
      assertNull(tester.cookies.get("flash"));
    }
  }

  @Test
  public void cookie_unreadable_leftAloneByNonGET() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withCookie("flash", "not!valid!base64")
            .post("/stay")
            .assertStatus(200)
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
      assertEquals(tester.cookies.get("flash").value, "not!valid!base64", "The middleware must not touch an unchanged cookie");

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty)
            .assertResponse(FlashMessagesTest::assertClearsCookie);
      assertNull(tester.cookies.get("flash"));
    }
  }

  @Test
  public void cookie_unreadable_replacedWhenMessageAdded() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withCookie("flash", "not!valid!base64")
            .withHeader("X-Message", "Fresh")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);
      Cookie cookie = tester.cookies.get("flash");
      assertNotNull(cookie, "A fresh cookie should replace the unreadable one");
      assertNotEquals(cookie.value, "not!valid!base64");

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:Fresh"));
    }
  }

  @Test
  public void flash_messagesAreReadOnly() {
    try (var web = new Web()) {
      web.install(new FlashMessages());
      web.get("/page", (req, res) -> {
        Flash flash = new Flash(req).addMessage("info", "one");
        int blocked = 0;
        try {
          flash.messages().put("error", List.of("nope"));
        } catch (UnsupportedOperationException e) {
          blocked++;
        }
        try {
          flash.messages().get("info").add("nope");
        } catch (UnsupportedOperationException e) {
          blocked++;
        }
        try {
          flash.messages("info").add("nope");
        } catch (UnsupportedOperationException e) {
          blocked++;
        }
        res.setStatus(blocked == 3 ? 418 : 200);
      });
      web.start(PORT);

      new WebTest(PORT).get("/page").assertStatus(418);
    }
  }

  @Test
  public void flash_perTypeAccessors() {
    try (var web = new Web()) {
      web.install(new FlashMessages());
      web.get("/page", (req, res) -> {
        Flash flash = new Flash(req);
        res.setStatus(200);
        res.setHeader("X-Types", String.join("|", flash.messages().keySet()));
        res.setHeader("X-Error", String.join("|", flash.messages("error")));
        res.setHeader("X-Has-Error", String.valueOf(flash.hasMessages("error")));
        res.setHeader("X-Has-Warn", String.valueOf(flash.hasMessages("warn")));
        res.setHeader("X-Warn-Size", String.valueOf(flash.messages("warn").size()));
        res.setHeader("X-Has-Any", String.valueOf(flash.hasMessages()));
      });
      web.start(PORT);

      var tester = new WebTest(PORT);
      tester.withCookie("flash", encode("{\"messages\":{\"info\":[\"One\"],\"error\":[\"Two\",\"Three\"],\"warn\":[null]}}"))
            .get("/page")
            .assertHeader("X-Types", "info|error")
            .assertHeader("X-Error", "Two|Three")
            .assertHeader("X-Has-Error", "true")
            .assertHeader("X-Has-Warn", "false")
            .assertHeader("X-Warn-Size", "0")
            .assertHeader("X-Has-Any", "true");

      new WebTest(PORT).get("/page")
                       .assertHeader("X-Types", "")
                       .assertHeader("X-Error", "")
                       .assertHeader("X-Has-Error", "false")
                       .assertHeader("X-Has-Any", "false");
    }
  }

  @Test
  public void flash_rejectsNulls() {
    try (var web = new Web()) {
      web.install(new FlashMessages());
      web.get("/page", (req, res) -> {
        Flash flash = new Flash(req);
        int rejected = 0;
        try {
          flash.addMessage(null, "message");
        } catch (NullPointerException e) {
          rejected++;
        }
        try {
          flash.addMessage("info", null);
        } catch (NullPointerException e) {
          rejected++;
        }
        try {
          flash.messages(null);
        } catch (NullPointerException e) {
          rejected++;
        }
        try {
          flash.hasMessages(null);
        } catch (NullPointerException e) {
          rejected++;
        }
        res.setStatus(rejected == 4 ? 418 : 200);
      });
      web.start(PORT);

      new WebTest(PORT).get("/page").assertStatus(418);
    }
    assertThrows(NullPointerException.class, () -> new Flash(null));
  }

  @Test
  public void flash_sharedWithinRequest() {
    try (var web = new Web()) {
      web.install(new FlashMessages());
      web.get("/page", (req, res) -> {
        new Flash(req).addMessage("info", "First");
        render(new Flash(req), res);
      });
      web.start(PORT);

      new WebTest(PORT).get("/page")
                       .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:First"));
    }
  }

  @Test
  public void flash_withoutMiddleware_readsButDoesNotPersist() {
    try (var web = new Web()) {
      web.get("/add-and-render", (req, res) -> {
        Flash flash = new Flash(req);
        flash.addMessage("info", "Now");
        render(flash, res);
      });
      web.get("/add-and-redirect", (req, res) -> {
        new Flash(req).addMessage("info", "Lost");
        res.sendRedirect("/page");
      });
      web.get("/page", (req, res) -> render(new Flash(req), res));
      web.start(PORT);

      var tester = new WebTest(PORT);
      tester.withCookie("flash", encode("{\"messages\":{\"info\":[\"Carried\"]}}"))
            .get("/add-and-render")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:Carried|info:Now"))
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
      tester.get("/add-and-redirect")
            .assertRedirect(302, "/page")
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:Carried"))
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
    }
  }

  @Test
  public void get_addsWithoutRendering_stillConsumes() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "Dropped")
            .get("/add-no-body")
            .assertStatus(200)
            .assertHeader("X-Messages", "info:Dropped")
            .assertResponse(FlashMessagesTest::assertNoSetCookie)
            .reset(ResetItem.Request);
      assertNull(tester.cookies.get("flash"));

      tester.withHeader("X-Message", "Saved!")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);
      tester.withHeader("X-Message", "Dropped")
            .get("/add-no-body")
            .assertStatus(200)
            .assertHeader("X-Messages", "info:Saved!|info:Dropped")
            .assertResponse(FlashMessagesTest::assertClearsCookie)
            .reset(ResetItem.Request);
      assertNull(tester.cookies.get("flash"));

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty);
    }
  }

  @Test
  public void get_noCookie_emitsNoHeaders() {
    try (var _ = flashServer()) {
      new WebTest(PORT).get("/page")
                       .assertStatus(200)
                       .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty)
                       .assertResponse(FlashMessagesTest::assertNoSetCookie);
    }
  }

  @Test
  public void get_uncommittedResponse_stillConsumes() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "Saved!")
            .post("/add")
            .assertRedirect(302, "/page")
            .reset(ResetItem.Request);

      tester.get("/page-no-body")
            .assertStatus(200)
            .assertHeader("X-Messages", "info:Saved!")
            .assertHeader("X-Has-Messages", "true")
            .assertResponse(FlashMessagesTest::assertClearsCookie);
      assertNull(tester.cookies.get("flash"));
    }
  }

  @Test
  public void handlerException_leavesCookieUntouched() {
    try (var web = new Web()) {
      web.install(new ExceptionHandler(Map.of(RuntimeException.class, (_, res, _) -> res.setStatus(500))));
      web.install(new FlashMessages());
      web.post("/add", (req, res) -> {
        new Flash(req).addMessage("info", "Saved!");
        res.sendRedirect("/page");
      });
      web.get("/boom", (_, _) -> {
        throw new RuntimeException("boom");
      });
      web.post("/boom-add", (req, _) -> {
        new Flash(req).addMessage("error", "Lost");
        throw new RuntimeException("boom");
      });
      web.get("/page", (req, res) -> render(new Flash(req), res));
      web.start(PORT);

      var tester = new WebTest(PORT);
      tester.post("/add").assertRedirect(302, "/page");
      String wire = tester.cookies.get("flash").value;

      tester.get("/boom")
            .assertStatus(500)
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
      tester.post("/boom-add")
            .assertStatus(500)
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
      assertEquals(tester.cookies.get("flash").value, wire);

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:Saved!"));
    }
  }

  @Test
  public void jte_rendersMessages() {
    try (var web = new Web()) {
      var templates = new JTETemplates(Paths.get("src/test/jte"));
      web.install(new FlashMessages());
      web.post("/add", (req, res) -> {
        new Flash(req).addMessage("success", "Saved <b>bold</b>").addMessage("error", "Second");
        res.sendRedirect("/flash");
      });
      web.get("/flash", (req, res) -> templates.html("flash.jte", req, res, Map.of()));
      web.start(PORT);

      var tester = new WebTest(PORT);
      tester.post("/add").assertRedirect(302, "/flash");
      tester.get("/flash")
            .assertStatus(200)
            .assertBodyAs(new StringBodyAsserter(),
                s -> s.equalTo("<li class=\"success\">Saved &lt;b&gt;bold&lt;/b&gt;</li><li class=\"error\">Second</li>"));
      assertNull(tester.cookies.get("flash"));

      tester.get("/flash")
            .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty);
    }
  }

  @Test
  public void nonGET_doesNotConsume() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "Saved!")
            .post("/add-stay")
            .assertStatus(200)
            .reset(ResetItem.Request);
      assertNotNull(tester.cookies.get("flash"), "A non-redirecting POST should still write the cookie");
      String wire = tester.cookies.get("flash").value;

      tester.post("/stay")
            .assertStatus(200)
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
      assertEquals(tester.cookies.get("flash").value, wire);

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:Saved!"));
      assertNull(tester.cookies.get("flash"));
    }
  }

  @Test
  public void redirect_otherStatusesCarryForward() {
    try (var _ = flashServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Message", "Saved!")
            .post("/add-303")
            .assertRedirect(303, "/page")
            .reset(ResetItem.Request);
      assertNotNull(tester.cookies.get("flash"));

      tester.get("/redirect")
            .assertRedirect(302, "/page")
            .assertResponse(FlashMessagesTest::assertNoSetCookie);
      assertNotNull(tester.cookies.get("flash"), "A redirecting GET must not consume the messages");

      tester.get("/page")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("info:Saved!"));
    }
  }
}
