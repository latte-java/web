/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.test;

import module java.base;
import module org.lattejava.web;
import module org.lattejava.web.tests;
import module org.testng;

@Test
public class WebTestTest extends BaseWebTest {
  private static Web echoServer() {
    Web web = new Web();
    web.post("/echo", (req, res) -> {
      String contentType = req.getHeader("Content-Type");
      if (contentType != null) {
        res.setHeader("X-Content-Type", contentType);
      }
      byte[] body = req.getBodyBytes();
      res.setStatus(200);
      if (body != null && body.length > 0) {
        res.getOutputStream().write(body);
      }
    });
    web.start(PORT);
    return web;
  }

  @Test
  public void form_clearRequestStateResetsForm() {
    try (var _ = echoServer()) {
      var tester = new WebTest(PORT);
      tester.withFormField("a", "1");
      tester.clearRequestState();

      var string = new StringBodyAsserter();
      tester.post("/echo")
            .assertBodyAs(string, StringBodyAsserter::isEmpty);
    }
  }

  @Test
  public void form_repeatedKeysPreserved() {
    try (var _ = echoServer()) {
      var string = new StringBodyAsserter();
      new WebTest(PORT).withFormField("k", "a")
                       .withFormField("k", "b")
                       .post("/echo")
                       .assertHeader("X-Content-Type", "application/x-www-form-urlencoded")
                       .assertBodyAs(string, s -> s.equalTo("k=a&k=b"));
    }
  }

  @Test
  public void form_specialCharactersAreEncoded() {
    try (var _ = echoServer()) {
      var string = new StringBodyAsserter();
      new WebTest(PORT).withFormField("k 1", "a&b=c")
                       .withFormField("u", "é")
                       .post("/echo")
                       .assertBodyAs(string, s -> s.equalTo("k+1=a%26b%3Dc&u=%C3%A9"));
    }
  }

  @Test
  public void form_userContentTypeRespected() {
    try (var _ = echoServer()) {
      var string = new StringBodyAsserter();
      new WebTest(PORT).withHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                       .withFormField("name", "Brian")
                       .post("/echo")
                       .assertHeader("X-Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                       .assertBodyAs(string, s -> s.equalTo("name=Brian"));
    }
  }

  @Test
  public void form_withBodyAfterFormWins() {
    try (var _ = echoServer()) {
      var string = new StringBodyAsserter();
      new WebTest(PORT).withFormField("ignored", "true")
                       .withBody("raw=payload")
                       .withHeader("Content-Type", "text/plain")
                       .post("/echo")
                       .assertHeader("X-Content-Type", "text/plain")
                       .assertBodyAs(string, s -> s.equalTo("raw=payload"));
    }
  }

  @Test
  public void form_withFormAfterBodyWins() {
    try (var _ = echoServer()) {
      var string = new StringBodyAsserter();
      new WebTest(PORT).withBody("not-this")
                       .withFormField("yes", "this")
                       .post("/echo")
                       .assertHeader("X-Content-Type", "application/x-www-form-urlencoded")
                       .assertBodyAs(string, s -> s.equalTo("yes=this"));
    }
  }

  @Test
  public void form_withFormEncodesMultipleFields() {
    try (var _ = echoServer()) {
      var string = new StringBodyAsserter();
      var form = new LinkedHashMap<String, String>(); // Required to maintain order
      form.put("a", "1");
      form.put("b", "2");
      new WebTest(PORT).withForm(form)
                       .post("/echo")
                       .assertHeader("X-Content-Type", "application/x-www-form-urlencoded")
                       .assertBodyAs(string, s -> s.equalTo("a=1&b=2"));
    }
  }

  @Test
  public void form_withFormFieldEncodesAndSetsContentType() {
    try (var _ = echoServer()) {
      var string = new StringBodyAsserter();
      new WebTest(PORT).withFormField("name", "Brian")
                       .post("/echo")
                       .assertHeader("X-Content-Type", "application/x-www-form-urlencoded")
                       .assertBodyAs(string, s -> s.equalTo("name=Brian"));
    }
  }

  @Test
  public void form_withFormReplacesPriorFields() {
    try (var _ = echoServer()) {
      var string = new StringBodyAsserter();
      new WebTest(PORT).withFormField("gone", "yes")
                       .withForm(Map.of("only", "this"))
                       .post("/echo")
                       .assertBodyAs(string, s -> s.equalTo("only=this"));
    }
  }

  @Test
  public void simple() {
    try (var _ = new Web().get("/", (_, res) -> res.getWriter().write("Hello, World!"))
                          .get("/json", (_, res) -> res.getWriter().write("{\"foo\": \"bar\"}"))
                          .start(PORT)) {
      var string = new StringBodyAsserter();
      var json = new JSONBodyAsserter();
      var tester = new WebTest(PORT);
      tester.withURLParameter("foo", "bar")
            .withHeader("Header", "Value")
            .get("/")
            .assertBodyAs(string, s -> s.equalTo("Hello, World!"))

            // Second request
            .reset(ResetItem.Cookies, ResetItem.HttpClient)
            .withURLParameter("one", "two")
            .get("/")
            .assertBodyAs(string, s -> s.equalTo("Hello, World!"))

            // Complex body assertion
            .reset(ResetItem.Cookies, ResetItem.HttpClient)
            .withURLParameter("one", "two")
            .get("/")
            .assertBodyAs(string, s -> s.contains("Hello")
                                        .contains("World!"))

            // Custom body assertion
            .reset(ResetItem.Cookies, ResetItem.HttpClient)
            .withURLParameter("one", "two")
            .get("/json")
            .assertBodyAs(json, j -> j.hasElement("/foo")
                                      .equalTo("{\"foo\": \"bar\"}"));
    }
  }
}
