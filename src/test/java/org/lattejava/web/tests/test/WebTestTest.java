package org.lattejava.web.tests.test;

import module org.lattejava.web;
import module org.lattejava.web.tests;
import module org.testng;

@Test
public class WebTestTest extends BaseWebTest {

  @Test
  public void simple() {
    try (var web = new Web()) {
      web.get("/", (_, res) -> res.getWriter().write("Hello, World!"))
         .get("/json", (_, res) -> res.getWriter().write("{\"foo\": \"bar\"}"))
         .start(PORT);

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
