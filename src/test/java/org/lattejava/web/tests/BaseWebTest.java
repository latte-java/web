/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import module java.base;
import module java.net.http;

public abstract class BaseWebTest {
  protected static final int PORT = 8080;

  protected static final String BASE_URL = "http://localhost:" + PORT;

  protected HttpResponse<String> send(String method, String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
                                     .uri(URI.create(BASE_URL + path))
                                     .method(method, HttpRequest.BodyPublishers.noBody())
                                     .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
