/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.tests.injection;

import module java.base;
import module java.net.http;
import module org.lattejava.web;
import module org.testng;

import io.avaje.inject.BeanScope;
import org.lattejava.web.tests.BaseWebTest;

import static org.testng.Assert.*;

public class InjectorTest extends BaseWebTest {
  private static final BodySupplier<String> STRING_SUPPLIER =
      (req, _) -> new String(req.getBodyBytes(), StandardCharsets.UTF_8);

  @Test
  public void bodyHandler() throws Exception {
    try (var scope = BeanScope.builder().build(); var web = new Web()) {
      web.injector(scope::get)
         .post("/users/{id}", web.inject(CreateUserHandler.class, CreateUserHandler::handle), STRING_SUPPLIER,
             web.inject(AuditMiddleware.class))
         .get("/users/{id}", web.inject(UserHandler.class, UserHandler::handle))
         .start(PORT);

      assertEquals(sendWithBody("POST", "/users/2", "Daniel").statusCode(), 201);
      assertEquals(scope.get(AuditLog.class).paths(), List.of("/users/2"));

      HttpResponse<String> response = send("GET", "/users/2");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), "Hello, Daniel");
    }
  }

  @Test
  public void controller() throws Exception {
    try (var scope = BeanScope.builder().build(); var web = new Web()) {
      web.injector(scope::get)
         .get("/users/{id}", web.inject(UserController.class, UserController::show))
         .post("/users/{id}", web.inject(UserController.class, UserController::create), STRING_SUPPLIER,
             web.inject(AuditMiddleware.class))
         .start(PORT);

      InstanceCounter counter = scope.get(InstanceCounter.class);
      assertEquals(counter.count(), 0);

      assertEquals(sendWithBody("POST", "/users/3", "Emily").statusCode(), 201);
      assertEquals(counter.count(), 1);
      assertEquals(scope.get(AuditLog.class).paths(), List.of("/users/3"));

      HttpResponse<String> response = send("GET", "/users/3");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), "Hello, Emily");
      assertEquals(counter.count(), 2);
    }
  }

  @Test
  public void handler() throws Exception {
    try (var scope = BeanScope.builder().build(); var web = new Web()) {
      web.injector(scope::get)
         .get("/users/{id}", web.inject(UserHandler.class, UserHandler::handle))
         .start(PORT);

      HttpResponse<String> response = send("GET", "/users/1");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), "Hello, Brian");

      assertEquals(send("GET", "/users/missing").statusCode(), 404);
    }
  }

  @Test
  public void handlerCreatedPerRequest() throws Exception {
    try (var scope = BeanScope.builder().build(); var web = new Web()) {
      web.injector(scope::get)
         .get("/count", web.inject(CountingHandler.class, CountingHandler::handle))
         .start(PORT);

      InstanceCounter counter = scope.get(InstanceCounter.class);
      assertEquals(counter.count(), 0);

      assertEquals(send("GET", "/count").statusCode(), 204);
      assertEquals(send("GET", "/count").statusCode(), 204);
      assertEquals(counter.count(), 2);
    }
  }

  @Test
  public void middleware() throws Exception {
    try (var scope = BeanScope.builder().build(); var web = new Web()) {
      web.injector(scope::get)
         .install(web.inject(AuditMiddleware.class))
         .get("/users/{id}", web.inject(UserHandler.class, UserHandler::handle))
         .start(PORT);

      HttpResponse<String> response = send("GET", "/users/1");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), "Hello, Brian");
      assertEquals(response.headers().firstValue("X-Audited").orElse(null), "true");
      assertEquals(scope.get(AuditLog.class).paths(), List.of("/users/1"));
    }
  }

  @Test
  public void noInjector() {
    try (var web = new Web()) {
      IllegalStateException handlerEx = expectThrows(IllegalStateException.class,
          () -> web.inject(UserHandler.class, UserHandler::handle));
      assertTrue(handlerEx.getMessage().contains("No injector configured"));

      IllegalStateException middlewareEx = expectThrows(IllegalStateException.class,
          () -> web.inject(AuditMiddleware.class));
      assertTrue(middlewareEx.getMessage().contains("No injector configured"));
    }
  }

  @Test
  public void prefix() throws Exception {
    try (var scope = BeanScope.builder().build(); var web = new Web()) {
      web.injector(scope::get)
         .prefix("/api", api -> api.get("/users/{id}", api.inject(UserHandler.class, UserHandler::handle)))
         .start(PORT);

      HttpResponse<String> response = send("GET", "/api/users/1");
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), "Hello, Brian");
    }
  }
}
