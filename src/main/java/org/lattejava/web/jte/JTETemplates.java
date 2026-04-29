/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.jte;

import module gg.jte;
import module gg.jte.runtime;
import module java.base;
import module org.lattejava.http;

/**
 * Renders <a href="https://jte.gg/">JTE</a> templates and writes the result to the HTTP response.
 * <p>
 * Each {@code html} method resolves a template, evaluates it with the supplied parameters, and writes the rendered HTML
 * to the response. The template name can be supplied explicitly or derived from the request path:
 * <ul>
 *   <li>{@code /} resolves to {@code index.jte}</li>
 *   <li>{@code /foo} resolves to {@code foo.jte}</li>
 *   <li>{@code /foo/} resolves to {@code foo/index.jte}</li>
 *   <li>{@code /foo/bar} resolves to {@code foo/bar.jte}</li>
 * </ul>
 * When a single non-{@link Map} {@code model} is supplied, it is bound to the template parameter named {@code model}.
 * When a {@link Map} is supplied, its entries are bound to template parameters named using the map key.
 * <p>
 * The default constructor configures a {@link TemplateEngine} that reads {@code .jte} sources from {@code web/templates}
 * relative to the working directory. For deployed applications, supply a custom {@link TemplateEngine} (for example, a
 * precompiled engine) via {@link #JTETemplates(TemplateEngine)}.
 *
 * @author Brian Pontarelli
 */
public class JTETemplates {
  private static final String DEFAULT_MODEL_NAME = "model";
  private static final Path DEFAULT_TEMPLATE_DIR = Paths.get("web/templates");
  private static final Path DEFAULT_CLASSES_DIR = Paths.get("build/jte-classes");
  private final TemplateEngine engine;

  /**
   * Constructs a {@code JTETemplates} that loads templates from {@code web/templates} relative to the working directory
   * and renders them as HTML.
   */
  public JTETemplates() {
    this(TemplateEngine.create(new DirectoryCodeResolver(DEFAULT_TEMPLATE_DIR), ContentType.Html));
  }

  /**
   * Constructs a {@code JTETemplates} that loads templates from the given directory and renders them as HTML.
   *
   * @param templateDir The directory containing {@code .jte} template sources.
   */
  public JTETemplates(Path templateDir) {
    this(templateDir, DEFAULT_CLASSES_DIR);
  }

  /**
   * Constructs a {@code JTETemplates} that loads templates from the given directory and renders them as HTML. The
   * compiled JTE templates are stored in the given classes directory.
   *
   * @param templateDir The directory containing {@code .jte} template sources.
   * @param classesDir  The directory that the compiled JTE templates will be stored in.
   */
  public JTETemplates(Path templateDir, Path classesDir) {
    Objects.requireNonNull(templateDir, "templateDir must not be null");
    Objects.requireNonNull(classesDir, "classesDir must not be null");
    this(TemplateEngine.create(new DirectoryCodeResolver(templateDir), classesDir, ContentType.Html));
  }

  /**
   * Constructs a {@code JTETemplates} backed by the given {@link TemplateEngine}.
   *
   * @param engine The JTE template engine.
   */
  public JTETemplates(TemplateEngine engine) {
    this.engine = Objects.requireNonNull(engine, "engine must not be null");
  }

  private static String deriveTemplateName(HTTPRequest req) {
    String path = req.getPath();
    if (path == null || path.isEmpty() || "/".equals(path)) {
      return "index.jte";
    }

    String name = path.startsWith("/") ? path.substring(1) : path;
    if (name.endsWith("/")) {
      name = name + "index";
    }

    return name + ".jte";
  }

  /**
   * Renders the template derived from the request path with no parameters and writes the result to the response.
   *
   * @param req The request, used to derive the template name.
   * @param res The response to write the rendered HTML to.
   */
  public void html(HTTPRequest req, HTTPResponse res) throws IOException {
    render(deriveTemplateName(req), req, res, Map.of());
  }

  /**
   * Renders the template derived from the request path with the given model and writes the result to the response. The
   * {@code model} parameter is always bound to a template parameter named {@code model}. The {@code request} and
   * {@code response} parameters are always available to the template.
   *
   * @param req   The request, used to derive the template name and exposed to the template as the {@code request}
   *              parameter.
   * @param res   The response to write the rendered HTML to, exposed to the template as the {@code response}
   *              parameter.
   * @param model The model to bind.
   */
  public void html(HTTPRequest req, HTTPResponse res, Object model) throws IOException {
    render(deriveTemplateName(req), req, res, Map.of(DEFAULT_MODEL_NAME, model));
  }

  /**
   * Renders the template derived from the request path with the given model and writes the result to the response. The
   * {@code request} and {@code response} parameters are always available to the template.
   *
   * @param req    The request, used to derive the template name and exposed to the template as the {@code request}
   *               parameter.
   * @param res    The response to write the rendered HTML to, exposed to the template as the {@code response}
   *               parameter.
   * @param models The map of models to bind.
   */
  public void html(HTTPRequest req, HTTPResponse res, Map<String, Object> models) throws IOException {
    render(deriveTemplateName(req), req, res, models);
  }

  /**
   * Renders the named template with the given parameter map and writes the result to the response. Map entries are
   * bound to template parameters using the map keys as the names. The {@code request} and {@code response} parameters
   * are always available to the template.
   *
   * @param name   The template name (e.g., {@code page.jte}).
   * @param req    The request, exposed to the template as the {@code request} parameter.
   * @param res    The response to write the rendered HTML to, exposed to the template as the {@code response}
   *               parameter.
   * @param models The map of template parameter names to values.
   */
  public void html(String name, HTTPRequest req, HTTPResponse res, Map<String, Object> models) throws IOException {
    render(name, req, res, models);
  }

  /**
   * Renders the named template with the given model and writes the result to the response. The model is bound to a
   * template parameter named {@code model}. The {@code request} and {@code response} parameters are always available to
   * the template.
   *
   * @param name  The template name (e.g., {@code page.jte}).
   * @param req   The request, exposed to the template as the {@code request} parameter.
   * @param res   The response to write the rendered HTML to, exposed to the template as the {@code response}
   *              parameter.
   * @param model The model to bind to the {@code model} parameter.
   */
  public void html(String name, HTTPRequest req, HTTPResponse res, Object model) throws IOException {
    render(name, req, res, Map.of(DEFAULT_MODEL_NAME, model));
  }

  private void render(String name, HTTPRequest req, HTTPResponse res, Map<String, Object> params) throws IOException {
    Map<String, Object> merged = new LinkedHashMap<>(params);
    merged.put("request", req);
    merged.put("response", res);

    // Set the response headers first before we write to the stream
    res.setStatus(200);
    res.setContentType("text/html; charset=utf-8");

    // Stream the output to the response
    Writer writer = res.getWriter();
    engine.render(name, merged, new WriterOutput(writer));
    writer.flush();
  }
}
