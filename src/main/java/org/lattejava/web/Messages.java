/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module java.base;
import module org.lattejava.http;

import org.lattejava.web.internal.*;

/**
 * Looks up localized messages for the current request from properties files under {@value #DIRECTORY} in the server's
 * base directory (see {@link Web#baseDir(Path)}).
 * <p>
 * The files are laid out to mirror the URL space. A request path names a leaf file, and every directory above it
 * contributes an {@code index.properties}. A path that ends in a slash is itself a directory and starts at that
 * directory's index. Files are consulted from the most specific to the least specific, and the first one that defines a
 * key wins:
 * <table>
 *   <tr><th>Request path</th><th>Files consulted, in order</th></tr>
 *   <tr><td>{@code /}</td><td>{@code index}</td></tr>
 *   <tr><td>{@code /users}</td><td>{@code users}, {@code index}</td></tr>
 *   <tr><td>{@code /users/}</td><td>{@code users/index}, {@code index}</td></tr>
 *   <tr><td>{@code /admin/users/edit}</td><td>{@code admin/users/edit}, {@code admin/users/index}, {@code admin/index},
 *       {@code index}</td></tr>
 * </table>
 * Each name above stands for a family of files that follow the {@link ResourceBundle} naming convention. For a request
 * whose locale is {@code de_DE}, {@code admin/users/edit} is {@code admin/users/edit_de_DE.properties}, then
 * {@code admin/users/edit_de.properties}, then {@code admin/users/edit.properties}. The locale is the request's
 * preferred locale from the {@code Accept-Language} header ({@link HTTPRequest#getLocale()}) unless one is passed to
 * the constructor. Every locale variant of a file is consulted before moving up to the parent directory, so a message
 * defined for the page beats a translation defined for the site.
 * <p>
 * Files are UTF-8 {@link Properties} files. They are read on first use and cached. Every lookup checks the file's
 * last-modified time and size, so a file that is edited, added, or deleted on disk takes effect on the next request
 * without a restart.
 * <p>
 * This is a thin wrapper around the request, so it can be created wherever the request is available. Handlers and
 * middleware create one directly:
 * <pre>{@code
 * web.get("/admin/users/edit", (req, res) -> {
 *   Messages messages = new Messages(req);
 *   String title = messages.get("title");
 *   String saved = messages.get("saved", user.name());
 *   ...
 * });
 * }</pre>
 * Templates do the same. {@link org.lattejava.web.jte.JTETemplates} always binds the {@code request} parameter, so a
 * JTE template only needs:
 * <pre>{@code
 * @import org.lattejava.http.server.HTTPRequest
 * @import org.lattejava.web.Messages
 * @param HTTPRequest request
 * <h1>${new Messages(request).get("title")}</h1>
 * }</pre>
 * When arguments are supplied, the message is a {@link MessageFormat} pattern evaluated in the request's locale. When
 * no arguments are supplied, the message is returned verbatim, so plain text may contain apostrophes and braces without
 * escaping.
 * <p>
 * Messages can also be looked up without a request by naming the base directory, the request path, and the locale
 * directly. This is how a test that drives a server through {@link org.lattejava.web.test.WebTest} fetches the text it
 * expects to see, so the expected value comes from the same files the server reads:
 * <pre>{@code
 * Messages messages = new Messages(baseDir, "/admin/users/edit", Locale.GERMAN);
 * new WebTest(port).withHeader("Accept-Language", "de")
 *                  .get("/admin/users/edit")
 *                  .assertBodyAs(new StringBodyAsserter(), s -> s.contains(messages.get("title")));
 * }</pre>
 * The same lookup rules apply, so the result is exactly what a handler for that path and locale would see.
 *
 * @author Brian Pontarelli
 */
public final class Messages {
  /**
   * The messages directory, relative to the server's base directory.
   */
  public static final String DIRECTORY = "web/messages";

  private static final ResourceBundle.Control CONTROL = ResourceBundle.Control.getControl(ResourceBundle.Control.FORMAT_PROPERTIES);
  private static final String INDEX = "index";
  private static final String STORE_ATTRIBUTE = Messages.class.getName();

  private final List<Properties> chain;
  private final Locale locale;
  private final String path;

  /**
   * Wraps the messages for the given request using the request's preferred locale.
   *
   * @param req The current request.
   */
  public Messages(HTTPRequest req) {
    Objects.requireNonNull(req, "req must not be null");
    this(req, req.getLocale());
  }

  /**
   * Wraps the messages for the given request using an explicit locale instead of the request's preferred locale.
   *
   * @param req    The current request.
   * @param locale The locale to look up messages for.
   */
  public Messages(HTTPRequest req, Locale locale) {
    Objects.requireNonNull(req, "req must not be null");
    HTTPContext context = req.getContext();
    if (context == null) {
      throw new IllegalStateException("The request has no HTTPContext, so the messages directory cannot be located");
    }

    this(store(context), req.getPath(), locale);
  }

  /**
   * Looks up messages without a request, using the JVM's default locale. This matches what a request without an
   * {@code Accept-Language} header sees. The base directory is {@code .}.
   *
   * @param path The request path whose messages to look up (for example {@code /admin/users/edit}).
   */
  public Messages(String path) {
    this(Path.of("."), path, Locale.getDefault());
  }

  /**
   * Looks up messages without a request. The base directory is {@code .}.
   *
   * @param path   The request path whose messages to look up (for example {@code /admin/users/edit}).
   * @param locale The locale to look up messages for.
   */
  public Messages(String path, Locale locale) {
    this(Path.of("."), path, locale);
  }

  /**
   * Looks up messages without a request, using the JVM's default locale. This matches what a request without an
   * {@code Accept-Language} header sees.
   *
   * @param baseDir The server's base directory (the value passed to {@link Web#baseDir(Path)}).
   * @param path    The request path whose messages to look up (for example {@code /admin/users/edit}).
   */
  public Messages(Path baseDir, String path) {
    this(baseDir, path, Locale.getDefault());
  }

  /**
   * Looks up messages without a request. The files are read from {@value #DIRECTORY} under the base directory, the same
   * place the server reads them, and the same lookup rules apply.
   *
   * @param baseDir The server's base directory (the value passed to {@link Web#baseDir(Path)}).
   * @param path    The request path whose messages to look up (for example {@code /admin/users/edit}).
   * @param locale  The locale to look up messages for.
   */
  public Messages(Path baseDir, String path, Locale locale) {
    Objects.requireNonNull(baseDir, "baseDir must not be null");
    this(new MessageStore(baseDir.resolve(DIRECTORY)), path, locale);
  }

  private Messages(MessageStore store, String path, Locale locale) {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(locale, "locale must not be null");
    this.locale = locale;
    this.path = path;
    this.chain = resolve(store, path, locale);
  }

  /**
   * Computes the file base names for a request path, most specific first. A path that ends in a slash is a directory
   * and starts at that directory's index; any other path starts at the file named by its last segment. Every path then
   * walks the index of each directory above it.
   *
   * @param path The request path.
   * @return The base names without locale suffix or extension (for example {@code admin/users/edit},
   *     {@code admin/users/index}, {@code admin/index}, {@code index}).
   */
  private static List<String> baseNames(String path) {
    List<String> segments = new ArrayList<>();
    for (String segment : path.split("/")) {
      if (!segment.isEmpty()) {
        segments.add(segment);
      }
    }

    // A LinkedHashSet drops the duplicate produced by a path whose last segment is itself named index
    Set<String> names = new LinkedHashSet<>();
    int depth = segments.size();
    if (!path.endsWith("/") && depth > 0) {
      names.add(String.join("/", segments));
      depth--;
    }
    for (int i = depth; i >= 0; i--) {
      String prefix = String.join("/", segments.subList(0, i));
      names.add(prefix.isEmpty() ? INDEX : prefix + "/" + INDEX);
    }
    return List.copyOf(names);
  }

  private static String fileName(String baseName, Locale locale) {
    return CONTROL.toBundleName(baseName, locale) + ".properties";
  }

  private static List<Properties> resolve(MessageStore store, String path, Locale locale) {
    List<Locale> candidates = CONTROL.getCandidateLocales(INDEX, locale);
    List<Properties> chain = new ArrayList<>();
    for (String baseName : baseNames(path)) {
      for (Locale candidate : candidates) {
        Properties props = store.load(fileName(baseName, candidate));
        if (props != null) {
          chain.add(props);
        }
      }
    }
    return List.copyOf(chain);
  }

  private static MessageStore store(HTTPContext context) {
    // The attribute map is a synchronized map, so computeIfAbsent creates the store exactly once per server
    Object store = context.getAttributes()
                          .computeIfAbsent(STORE_ATTRIBUTE, _ -> new MessageStore(context.baseDir.resolve(DIRECTORY)));
    return (MessageStore) store;
  }

  /**
   * Looks up a message, returning {@code null} when no file in the lookup chain defines the key.
   *
   * @param key  The message key.
   * @param args The {@link MessageFormat} arguments. When empty, the message is returned verbatim.
   * @return The message, formatted if arguments were given, or {@code null} if the key is not defined.
   */
  public String find(String key, Object... args) {
    Objects.requireNonNull(key, "key must not be null");
    String message = lookup(key);
    if (message == null || args == null || args.length == 0) {
      return message;
    }
    return new MessageFormat(message, locale).format(args);
  }

  /**
   * Looks up a message, throwing when no file in the lookup chain defines the key.
   *
   * @param key  The message key.
   * @param args The {@link MessageFormat} arguments. When empty, the message is returned verbatim.
   * @return The message, formatted if arguments were given.
   * @throws MissingMessageException if the key is not defined.
   */
  public String get(String key, Object... args) {
    String message = find(key, args);
    if (message == null) {
      throw new MissingMessageException(key, path, locale);
    }
    return message;
  }

  /**
   * @param key The message key.
   * @return {@code true} if a file in the lookup chain defines the key.
   */
  public boolean has(String key) {
    Objects.requireNonNull(key, "key must not be null");
    return lookup(key) != null;
  }

  /**
   * @return The locale messages are looked up for.
   */
  public Locale locale() {
    return locale;
  }

  private String lookup(String key) {
    for (Properties props : chain) {
      String value = props.getProperty(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }
}
