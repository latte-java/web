/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import org.lattejava.web.Web;
import org.testng.annotations.Test;

import static org.testng.Assert.fail;

@SuppressWarnings("resource")
public class ValidationTest {

  // -------------------------------------------------------------------------
  // Path validation — registration throws IllegalArgumentException
  // -------------------------------------------------------------------------

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_emptyPath_throws() {
    new Web().get("", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_noLeadingSlash_throws() {
    new Web().get("users", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_pathWithSpace_throws() {
    new Web().get("/hello world", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_pathWithControlChar_throws() {
    new Web().get("/hello\tworld", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_unclosedParam_throws() {
    new Web().get("/users/{id", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_unopenedParam_throws() {
    new Web().get("/users/id}", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_mixedLiteralAndParam_throws() {
    new Web().get("/users/foo{id}", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_emptyParamName_throws() {
    new Web().get("/users/{}", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_paramNameWithSpace_throws() {
    new Web().get("/users/{ id }", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_paramNameWithHyphen_throws() {
    new Web().get("/users/{my-id}", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_paramNameStartsWithDigit_throws() {
    new Web().get("/users/{1abc}", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_duplicateParamNames_throws() {
    new Web().get("/users/{id}/posts/{id}", (_, _) -> {
    });
  }

  @Test
  public void route_conflictingParamNames_throws() {
    var web = new Web();
    web.get("/users/{id}", (_, res) -> res.setStatus(200));
    try {
      web.get("/users/{userId}", (_, res) -> res.setStatus(200));
      fail("Expected IllegalStateException");
    } catch (IllegalStateException expected) {
      // expected
    }
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void route_duplicateRegistration_throws() {
    var web = new Web();
    web.get("/test", (_, res) -> res.setStatus(200));
    web.get("/test", (_, res) -> res.setStatus(201));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_percentInPathSpec_throws() {
    // The PathParser rejects '%' in pathSpec (our allowed char set is RFC 3986 pchar minus '%').
    new Web().get("/emoji/%F0%9F%98%80", (_, _) -> {
    });
  }

  // -------------------------------------------------------------------------
  // Method validation
  // -------------------------------------------------------------------------

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_emptyMethods_throws() {
    new Web().route(java.util.List.of(), "/test", (_, res) -> res.setStatus(200));
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_nullMethodEntry_throws() {
    var list = new java.util.ArrayList<String>();
    list.add(null);
    new Web().route(list, "/test", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_blankMethodEntry_throws() {
    new Web().route(java.util.List.of("  "), "/test", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_methodWithWhitespace_throws() {
    new Web().route(java.util.List.of("GE T"), "/test", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_methodWithInvalidChar_throws() {
    new Web().route(java.util.List.of("GET!"), "/test", (_, _) -> {
    });
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void route_methodWithDigits_throws() {
    new Web().route(java.util.List.of("GET1"), "/test", (_, _) -> {
    });
  }
}
