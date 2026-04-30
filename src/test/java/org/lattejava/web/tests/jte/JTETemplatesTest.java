package org.lattejava.web.tests.jte;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.lattejava.web.tests;
import module org.testng;

import static org.testng.Assert.*;

@Test
public class JTETemplatesTest extends BaseWebTest {
  @Test
  public void multipleModels() throws Exception {
    try (var web = new Web()) {
      var templates = new JTETemplates(Paths.get("src/test/jte"));
      var model = new Model("One");
      var model2 = new Model("Two");
      web.get("/", (req, res) -> templates.html(req, res,
              Map.of(
                  "model1", model,
                  "model2", model2
              )
          )
      );
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/");
      assertEquals(response.body(), "Template model=One model2=Two");
    }
  }

  @Test
  public void namedTemplate_multipleModels() throws Exception {
    try (var web = new Web()) {
      var templates = new JTETemplates(Paths.get("src/test/jte"));
      var model = new Model("One");
      var model2 = new Model("Two");
      web.get("/", (req, res) -> templates.html("named-template.jte", req, res,
              Map.of(
                  "model1", model,
                  "model2", model2
              )
          )
      );
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/");
      assertEquals(response.body(), "Template model1=One model2=Two");
    }
  }

  @Test
  public void namedTemplate_singleModel() throws Exception {
    try (var web = new Web()) {
      var templates = new JTETemplates(Paths.get("src/test/jte"));
      var model = new Model("Test");
      web.get("/", (req, res) -> templates.html("named-template.jte", req, res, model));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/");
      assertEquals(response.body(), "Template with model=Test");
    }
  }

  @Test
  public void noModel() throws Exception {
    try (var web = new Web()) {
      var templates = new JTETemplates(Paths.get("src/test/jte"));
      web.get("/", templates::html);
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/");
      assertEquals(response.body(), "Template");
    }
  }

  @Test
  public void render_multipleModels() {
    var templates = new JTETemplates(Paths.get("src/test/jte"));
    var model1 = new Model("One");
    var model2 = new Model("Two");
    String result = templates.render("named-template.jte",
        Map.of(
            "model1", model1,
            "model2", model2
        )
    );
    assertEquals(result, "Template model1=One model2=Two");
  }

  @Test
  public void render_noModel() {
    var templates = new JTETemplates(Paths.get("src/test/jte"));
    String result = templates.render("index.jte");
    assertEquals(result, "Template");
  }

  @Test
  public void render_singleModel() {
    var templates = new JTETemplates(Paths.get("src/test/jte"));
    var model = new Model("Test");
    String result = templates.render("named-template.jte", model);
    assertEquals(result, "Template with model=Test");
  }

  @Test
  public void singleModel() throws Exception {
    try (var web = new Web()) {
      var templates = new JTETemplates(Paths.get("src/test/jte"));
      var model = new Model("Test");
      web.get("/", (req, res) -> templates.html(req, res, model));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/");
      assertEquals(response.body(), "Template with model=Test");
    }
  }

  public record Model(String name) {
  }
}
