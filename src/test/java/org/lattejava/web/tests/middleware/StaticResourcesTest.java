/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.middleware;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Locale;

import org.lattejava.web.middleware.StaticResources;
import org.lattejava.web.Web;
import org.lattejava.web.tests.*;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class StaticResourcesTest extends BaseWebTest {
  private static final DateTimeFormatter HTTP_DATE =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

  private Path tempDir;

  @BeforeMethod
  public void setUp() throws IOException {
    tempDir = Files.createTempDirectory("latte-static-test");
    Files.createDirectory(tempDir.resolve("assets"));
    Files.writeString(tempDir.resolve("assets/app.css"), "body { color: red; }");
    Files.writeString(tempDir.resolve("assets/app.js"), "console.log('hi');");
    Files.createDirectory(tempDir.resolve("assets/sub"));
    Files.writeString(tempDir.resolve("assets/sub/nested.txt"), "nested file");
  }

  @AfterMethod
  public void tearDown() throws IOException {
    if (tempDir != null && Files.exists(tempDir)) {
      try (var stream = Files.walk(tempDir)) {
        stream.sorted(Comparator.reverseOrder()).forEach(p -> {
          try {
            Files.delete(p);
          } catch (IOException ignored) {
          }
        });
      }
    }
  }

  @Test
  public void serves_fileUnderPrefix() throws Exception {
    try (var web = new Web()) {
      web.baseDir(tempDir).files("/assets").start(PORT);

      Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      HttpResponse<String> response = send("GET", "/assets/app.css");
      Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);

      assertStaticResourceHeaders(response, tempDir.resolve("assets/app.css"), "text/css", start, end);
      assertEquals(response.body(), "body { color: red; }");
    }
  }

  @Test
  public void servesNestedFile() throws Exception {
    try (var web = new Web()) {
      web.baseDir(tempDir).files("/assets").start(PORT);

      Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      HttpResponse<String> response = send("GET", "/assets/sub/nested.txt");
      Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);

      assertStaticResourceHeaders(response, tempDir.resolve("assets/sub/nested.txt"), "text/plain", start, end);
      assertEquals(response.body(), "nested file");
    }
  }

  @Test
  public void returns404_forMissingFile() throws Exception {
    try (var web = new Web()) {
      web.baseDir(tempDir).files("/assets").start(PORT);

      HttpResponse<String> response = send("GET", "/assets/does-not-exist");
      assertEquals(response.statusCode(), 404);
    }
  }

  @Test
  public void fallsThrough_forPathsOutsidePrefix() throws Exception {
    try (var web = new Web()) {
      web.baseDir(tempDir).files("/assets");
      web.get("/api/foo", (_, res) -> res.setStatus(201));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/api/foo");
      assertEquals(response.statusCode(), 201);
    }
  }

  @Test
  public void preventsPathTraversal() throws Exception {
    // Create a secret file OUTSIDE tempDir (as a sibling)
    Path secret = tempDir.getParent().resolve("latte-secret-" + System.nanoTime() + ".txt");
    Files.writeString(secret, "SECRET");
    try (var web = new Web()) {
      web.baseDir(tempDir).files("/assets").start(PORT);

      // Request that attempts to traverse up out of tempDir/assets
      HttpResponse<String> response = send("GET", "/assets/../../" + secret.getFileName());
      // HTTPContext.resolve() returns null for escapes → 404
      assertEquals(response.statusCode(), 404);
    } finally {
      Files.deleteIfExists(secret);
    }
  }

  @Test
  public void head_returnsHeadersWithoutBody() throws Exception {
    try (var web = new Web()) {
      web.baseDir(tempDir).files("/assets").start(PORT);

      Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      HttpResponse<String> response = send("HEAD", "/assets/app.css");
      Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);

      assertStaticResourceHeaders(response, tempDir.resolve("assets/app.css"), "text/css", start, end);
      assertEquals(response.body(), "");
    }
  }

  @Test
  public void returns304_onIfModifiedSinceAtOrAfterMtime() throws Exception {
    try (var web = new Web()) {
      web.baseDir(tempDir).files("/assets").start(PORT);

      Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      HttpResponse<String> first = send("GET", "/assets/app.css");
      Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);

      assertStaticResourceHeaders(first, tempDir.resolve("assets/app.css"), "text/css", start, end);

      // Second request with If-Modified-Since equal to Last-Modified → 304
      String lastModified = first.headers().firstValue("Last-Modified").orElseThrow();
      HttpResponse<String> second = sendWithHeader("GET", "/assets/app.css", "If-Modified-Since", lastModified);
      assertEquals(second.statusCode(), 304);
      assertEquals(second.body(), "");
    }
  }

  @Test
  public void serves200_onIfModifiedSinceBeforeMtime() throws Exception {
    try (var web = new Web()) {
      web.baseDir(tempDir).files("/assets").start(PORT);

      String before = HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC).minusDays(30));

      Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      HttpResponse<String> response = sendWithHeader("GET", "/assets/app.css", "If-Modified-Since", before);
      Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);

      assertStaticResourceHeaders(response, tempDir.resolve("assets/app.css"), "text/css", start, end);
    }
  }

  @Test
  public void explicitSubdirectoryMapping() throws Exception {
    // URL prefix /public maps to subdirectory "assets" (different names)
    try (var web = new Web()) {
      web.baseDir(tempDir).files("/public", "assets").start(PORT);

      Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      HttpResponse<String> response = send("GET", "/public/app.css");
      Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);

      assertStaticResourceHeaders(response, tempDir.resolve("assets/app.css"), "text/css", start, end);
      assertEquals(response.body(), "body { color: red; }");
    }
  }

  @Test
  public void filter_canBlockRequest() throws Exception {
    try (var web = new Web()) {
      web.baseDir(tempDir);
      web.install(new StaticResources("/assets", "assets", Duration.ofDays(7),
          (uri, _) -> !uri.endsWith(".js")));
      web.get("/assets/app.js", (_, res) -> res.setStatus(202)); // fall-through target
      web.start(PORT);

      // Filter allows CSS → served
      Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      HttpResponse<String> css = send("GET", "/assets/app.css");
      Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      assertStaticResourceHeaders(css, tempDir.resolve("assets/app.css"), "text/css", start, end);

      // Filter blocks JS → falls through to the route
      HttpResponse<String> js = send("GET", "/assets/app.js");
      assertEquals(js.statusCode(), 202);
    }
  }

  @Test
  public void doesNotMatchPrefixWithoutSlashBoundary() throws Exception {
    // Request /assetsX should NOT be treated as under /assets
    try (var web = new Web()) {
      web.baseDir(tempDir).files("/assets");
      web.get("/assetsX", (_, res) -> res.setStatus(203));
      web.start(PORT);

      HttpResponse<String> response = send("GET", "/assetsX");
      assertEquals(response.statusCode(), 203);
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void constructor_rejectsPrefixWithoutLeadingSlash() {
    new StaticResources("assets");
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void constructor_rejectsPrefixWithTrailingSlash() {
    new StaticResources("/assets/");
  }

  @Test
  public void baseDir_afterStart_throws() throws Exception {
    try (var web = new Web()) {
      web.get("/x", (_, res) -> res.setStatus(200));
      web.start(PORT);
      try {
        web.baseDir(tempDir);
        org.testng.Assert.fail("Expected IllegalStateException");
      } catch (IllegalStateException expected) {
        // expected
      }
    }
  }

  // Helper: assert status 200 and all content/cache headers for a static resource response.
  // requestStart/requestEnd bracket the HTTP call and define the acceptable window for the Date header.
  private void assertStaticResourceHeaders(HttpResponse<String> response, Path file, String expectedContentType,
                                           Instant requestStart, Instant requestEnd) throws IOException {
    Instant fileMtime = Files.getLastModifiedTime(file).toInstant().truncatedTo(ChronoUnit.SECONDS);

    assertEquals(response.statusCode(), 200);
    assertEquals(response.headers().firstValue("Content-Type").orElse(null), expectedContentType);
    assertEquals(response.headers().firstValue("Content-Length").orElse(null), String.valueOf(Files.size(file)));
    assertEquals(response.headers().firstValue("Cache-Control").orElse(null),
        "public, max-age=" + Duration.ofDays(7).toSeconds());
    assertEquals(parseHTTPDate(response.headers().firstValue("Last-Modified").orElseThrow()), fileMtime);

    Instant date = parseHTTPDate(response.headers().firstValue("Date").orElseThrow());
    assertTrue(!date.isBefore(requestStart) && !date.isAfter(requestEnd),
        "Date [" + date + "] not in [" + requestStart + ", " + requestEnd + "]");
    assertEquals(parseHTTPDate(response.headers().firstValue("Expires").orElseThrow()),
        date.plus(Duration.ofDays(7)));
  }

  // Helper: parse an HTTP-date header value into an Instant (server uses RFC 1123 format).
  private static Instant parseHTTPDate(String value) {
    return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
  }

  // Helper: send with a custom header
  private HttpResponse<String> sendWithHeader(String method, String path, String name, String value) throws Exception {
    var request = java.net.http.HttpRequest.newBuilder()
        .uri(java.net.URI.create(BASE_URL + path))
        .header(name, value)
        .method(method, java.net.http.HttpRequest.BodyPublishers.noBody())
        .build();
    return java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
  }
}
