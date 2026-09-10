/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;
import module org.testng;

import java.util.Objects;

import static org.testng.Assert.*;

/**
 * Tests the Messages lookup helper against the properties files under {@code src/test/projects/message-bundles/web}.
 *
 * @author Brian Pontarelli
 */
public class MessagesTest extends BaseWebTest {
  private static final Path PROJECT_DIR = Paths.get("src/test/projects/message-bundles");

  private static Object argument(String value) {
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      return value;
    }
  }

  private static void respond(HTTPResponse res, String body) throws IOException {
    res.setStatus(200);
    res.setContentType("text/plain; charset=utf-8");
    res.getWriter().write(body);
    res.getWriter().flush();
  }

  @Test
  public void constructor_explicitLocaleOverridesRequest() {
    try (var web = new Web()) {
      web.baseDir(PROJECT_DIR)
         .get("/admin/users/edit", (req, res) -> respond(res, new Messages(req, Locale.GERMAN).get("save")))
         .start(PORT);

      new WebTest(PORT).withHeader("Accept-Language", "en")
                       .get("/admin/users/edit")
                       .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Speichern"));
    }
  }

  @Test
  public void constructor_rejectsNulls() {
    try (var web = new Web()) {
      web.baseDir(PROJECT_DIR)
         .get("/", (req, res) -> {
           assertThrows(NullPointerException.class, () -> new Messages(null));
           assertThrows(NullPointerException.class, () -> new Messages(req, null));
           res.setStatus(200);
         })
         .start(PORT);

      new WebTest(PORT).get("/")
                       .assertStatus(200);
    }
  }

  @Test
  public void find_missingReturnsNull() {
    try (var _ = messagesServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Key", "nope")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("null"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Home"));
    }
  }

  @Test
  public void get_argumentsFormatWithMessageFormat() {
    try (var _ = messagesServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("Accept-Language", "en-US")
            .withHeader("X-Key", "greeting")
            .withHeader("X-Args", "Brian")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Hello Brian"))
            .reset(ResetItem.Request);
      tester.withHeader("Accept-Language", "en-US")
            .withHeader("X-Key", "count")
            .withHeader("X-Args", "1234.5")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Total 1,234.5"))
            .reset(ResetItem.Request);
      tester.withHeader("Accept-Language", "de-DE")
            .withHeader("X-Key", "greeting")
            .withHeader("X-Args", "Brian")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Hallo Brian"))
            .reset(ResetItem.Request);
      tester.withHeader("Accept-Language", "de-DE")
            .withHeader("X-Key", "count")
            .withHeader("X-Args", "1234.5")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Total 1.234,5"));
    }
  }

  @Test
  public void get_emptyValueIsDefined() {
    try (var _ = messagesServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Key", "empty")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), StringBodyAsserter::isEmpty)
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "empty")
            .withHeader("X-Mode", "has")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("true"));
    }
  }

  @Test
  public void get_localeChainWithinEachLevel() {
    try (var _ = messagesServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("Accept-Language", "en")
            .withHeader("X-Key", "save")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Save"))
            .reset(ResetItem.Request);
      tester.withHeader("Accept-Language", "de")
            .withHeader("X-Key", "save")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Speichern"))
            .reset(ResetItem.Request);
      tester.withHeader("Accept-Language", "de-DE")
            .withHeader("X-Key", "save")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Speichern (DE)"))
            .reset(ResetItem.Request);
      tester.withHeader("Accept-Language", "de-AT")
            .withHeader("X-Key", "save")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Speichern"))
            .reset(ResetItem.Request);
      tester.withHeader("Accept-Language", "fr, de;q=0.5")
            .withHeader("X-Key", "save")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Save"))
            .reset(ResetItem.Request);

      // The leaf's root file beats the site's German file
      tester.withHeader("Accept-Language", "de-DE")
            .withHeader("X-Key", "title")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Edit User"))
            .reset(ResetItem.Request);

      // A German parent beats that parent's root file
      tester.withHeader("Accept-Language", "de-DE")
            .withHeader("X-Key", "shared")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("admin-de"))
            .reset(ResetItem.Request);
      tester.withHeader("Accept-Language", "en")
            .withHeader("X-Key", "shared")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("admin"));
    }
  }

  @Test
  public void get_missingThrows() {
    try (var _ = messagesServer()) {
      new WebTest(PORT).withHeader("Accept-Language", "en-US")
                       .withHeader("X-Key", "nope")
                       .withHeader("X-Mode", "get")
                       .get("/admin/users/edit")
                       .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo(
                           "missing:nope:No message for key [nope] under path [/admin/users/edit] for locale [en_US]"));
    }
  }

  @Test
  public void get_noArgumentsReturnsVerbatim() {
    try (var _ = messagesServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Key", "apostrophe")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Don't panic"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "braces")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Use {curly} braces"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "utf8")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Grüße ✓"));
    }
  }

  @Test
  public void get_pathHierarchy() {
    try (var _ = messagesServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Key", "title")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Home"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/other")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Home"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/admin")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Home"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/admin/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Admin"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/admin/other")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Admin"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/admin/users")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Admin"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/admin/users/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Users"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/admin/users/new")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Users"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Edit User"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/admin/users/edit/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Users"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "title")
            .get("/admin/users/index")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Users"))
            .reset(ResetItem.Request);

      // Keys fall through to the parents
      tester.withHeader("X-Key", "shared")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("admin"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "greeting")
            .withHeader("X-Args", "Brian")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Hello Brian"));
    }
  }

  @Test
  public void get_pathTraversalStaysInsideDirectory() {
    // web/secret.properties sits outside web/messages and must not be reachable
    try (var _ = messagesServer()) {
      new WebTest(PORT).withHeader("X-Key", "title")
                       .get("/%2e%2e/secret")
                       .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("Home"));
    }
  }

  @Test
  public void has() {
    try (var _ = messagesServer()) {
      var tester = new WebTest(PORT);
      tester.withHeader("X-Key", "save")
            .withHeader("X-Mode", "has")
            .get("/admin/users/edit")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("true"))
            .reset(ResetItem.Request);
      tester.withHeader("X-Key", "save")
            .withHeader("X-Mode", "has")
            .get("/")
            .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("false"));
    }
  }

  @Test
  public void jte() {
    try (var web = new Web()) {
      var templates = new JTETemplates(PROJECT_DIR.resolve("web/templates"));
      web.baseDir(PROJECT_DIR)
         .get("/", (req, res) -> templates.html("messages.jte", req, res, Map.of()))
         .start(PORT);

      new WebTest(PORT).withHeader("Accept-Language", "de")
                       .get("/")
                       .assertBodyAs(new StringBodyAsserter(),
                           s -> s.equalTo("<h1>Startseite</h1><p>Hallo &lt;Brian&gt;</p>\n"));
    }
  }

  @Test
  public void middleware() {
    try (var _ = messagesServer()) {
      new WebTest(PORT).withHeader("Accept-Language", "de")
                       .withHeader("X-Key", "title")
                       .get("/admin/users/edit")
                       .assertStatus(200)
                       .assertHeader("X-Title", "Edit User")
                       .assertHeader("X-Locale", "de");
    }
  }

  @Test
  public void missingDirectory() {
    try (var web = new Web()) {
      web.baseDir(PROJECT_DIR.resolve("nowhere"))
         .get("/", (req, res) -> {
           Messages messages = new Messages(req);
           respond(res, messages.has("title") + ":" + messages.find("title"));
         })
         .start(PORT);

      new WebTest(PORT).get("/")
                       .assertBodyAs(new StringBodyAsserter(), s -> s.equalTo("false:null"));
    }
  }

  /**
   * Builds a Web whose base directory is the message-bundles project. A global middleware proves middleware access by
   * echoing the page title and locale as headers. A missing handler answers any path by looking up the key in the
   * {@code X-Key} header and writing the result as the body. {@code X-Args} supplies comma-separated MessageFormat
   * arguments (numeric values are passed as numbers) and {@code X-Mode} selects {@code find} (the default),
   * {@code get}, or {@code has}.
   */
  private Web messagesServer() {
    return new Web().baseDir(PROJECT_DIR)
                    .install((req, res, chain) -> {
                      Messages messages = new Messages(req);
                      res.setHeader("X-Title", messages.get("title"));
                      res.setHeader("X-Locale", messages.locale().toString());
                      chain.next(req, res);
                    })
                    .missingHandler((req, res) -> {
                      Messages messages = new Messages(req);
                      String key = req.getHeader("X-Key");
                      String argsHeader = req.getHeader("X-Args");
                      Object[] args = argsHeader == null
                          ? new Object[0]
                          : Arrays.stream(argsHeader.split(",")).map(MessagesTest::argument).toArray();
                      String mode = Objects.requireNonNullElse(req.getHeader("X-Mode"), "find");
                      String result;
                      try {
                        result = switch (mode) {
                          case "get" -> messages.get(key, args);
                          case "has" -> String.valueOf(messages.has(key));
                          default -> String.valueOf(messages.find(key, args));
                        };
                      } catch (MissingMessageException e) {
                        result = "missing:" + e.key() + ":" + e.getMessage();
                      }
                      respond(res, result);
                    })
                    .start(PORT);
  }
}
