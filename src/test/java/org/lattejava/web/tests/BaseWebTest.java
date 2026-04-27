/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import module java.base;
import module java.net.http;
import module org.lattejava.http;

public abstract class BaseWebTest {
  public static final int PORT = 8080;

  public static final String BASE_URL = "http://localhost:" + PORT;

  public static Cookie getCookie(HttpResponse<?> res, String name) {
    String prefix = name + "=";
    return res.headers()
              .allValues("Set-Cookie")
              .stream()
              .filter(h -> h.startsWith(prefix))
              .map(Cookie::fromResponseHeader)
              .findFirst()
              .orElse(null);
  }

  public static Map<String, String> parseQuery(String query) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String k = eq >= 0 ? pair.substring(0, eq) : pair;
      String v = eq >= 0 ? pair.substring(eq + 1) : "";
      out.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
          URLDecoder.decode(v, StandardCharsets.UTF_8));
    }
    return out;
  }

  protected HttpResponse<String> send(String method, String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(BASE_URL + path))
                                     .method(method, HttpRequest.BodyPublishers.noBody())
                                     .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
