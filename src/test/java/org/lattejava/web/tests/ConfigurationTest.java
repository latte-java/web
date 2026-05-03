/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests;

import module java.base;
import module org.testng;
import java.nio.file.Files;

import org.lattejava.web.Configuration;

import static org.testng.Assert.*;

public class ConfigurationTest {

  @Test
  public void constructorWithFileAndRequiredSettings() throws Exception {
    Path file = Files.createTempFile("config-test", ".properties");
    try {
      Files.writeString(file, "config-test.required.in-file=ok\n");
      var config = new Configuration(file, List.of("config-test.required.in-file"));
      assertEquals(config.get("config-test.required.in-file"), "ok");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  public void constructorWithRequiredSettingsErrorMessageListsMissing() {
    try {
      new Configuration(List.of("config-test.required.absent.one", "config-test.required.absent.two"));
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("config-test.required.absent.one"), "Message should list first missing key: " + e.getMessage());
      assertTrue(e.getMessage().contains("config-test.required.absent.two"), "Message should list second missing key: " + e.getMessage());
    }
  }

  @Test
  public void constructorWithRequiredSettingsOnly() {
    String key = "config-test.required.from-sysprop";
    System.setProperty(key, "ok");
    try {
      var config = new Configuration(List.of(key));
      assertEquals(config.get(key), "ok");
    } finally {
      System.clearProperty(key);
    }
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void constructorWithRequiredSettingsThrowsWhenMissing() {
    new Configuration(List.of("config-test.required.absent.one", "config-test.required.absent.two"));
  }

  @Test
  public void getBigDecimalParsesValue() {
    String key = "config-test.big-decimal";
    System.setProperty(key, "3.14159265358979323846");
    try {
      var config = new Configuration();
      assertEquals(config.getBigDecimal(key), new BigDecimal("3.14159265358979323846"));
    } finally {
      System.clearProperty(key);
    }
  }

  @Test
  public void getBigDecimalReturnsDefaultWhenMissing() {
    var config = new Configuration();
    assertEquals(config.getBigDecimal("config-test.missing.bd", new BigDecimal("42.5")), new BigDecimal("42.5"));
  }

  @Test
  public void getBigDecimalReturnsNullWhenMissing() {
    var config = new Configuration();
    assertNull(config.getBigDecimal("config-test.missing.bd"));
  }

  @Test
  public void getBigIntegerParsesValue() {
    String key = "config-test.big-integer";
    System.setProperty(key, "12345678901234567890");
    try {
      var config = new Configuration();
      assertEquals(config.getBigInteger(key), new BigInteger("12345678901234567890"));
    } finally {
      System.clearProperty(key);
    }
  }

  @Test
  public void getBigIntegerReturnsDefaultWhenMissing() {
    var config = new Configuration();
    assertEquals(config.getBigInteger("config-test.missing.bi", BigInteger.valueOf(99)), BigInteger.valueOf(99));
  }

  @Test
  public void getBigIntegerReturnsNullWhenMissing() {
    var config = new Configuration();
    assertNull(config.getBigInteger("config-test.missing.bi"));
  }

  @Test
  public void getBooleanParsesTrue() {
    String key = "config-test.bool-true";
    System.setProperty(key, "true");
    try {
      var config = new Configuration();
      assertEquals(config.getBoolean(key), Boolean.TRUE);
    } finally {
      System.clearProperty(key);
    }
  }

  @Test
  public void getBooleanReturnsDefaultWhenMissing() {
    var config = new Configuration();
    assertTrue(config.getBoolean("config-test.missing.bool", true));
    assertFalse(config.getBoolean("config-test.missing.bool", false));
  }

  @Test
  public void getBooleanReturnsNullWhenMissing() {
    var config = new Configuration();
    assertNull(config.getBoolean("config-test.missing.bool"));
  }

  @Test
  public void getEnvVarOverridesSystemProperty() {
    String envValue = System.getenv("PATH");
    assertNotNull(envValue, "Test assumes PATH env var is set");
    System.setProperty("PATH", "from-sysprop");
    try {
      var config = new Configuration();
      assertEquals(config.get("PATH"), envValue);
    } finally {
      System.clearProperty("PATH");
    }
  }

  @Test
  public void getIntegerParsesValue() {
    String key = "config-test.int";
    System.setProperty(key, "42");
    try {
      var config = new Configuration();
      assertEquals(config.getInteger(key), Integer.valueOf(42));
    } finally {
      System.clearProperty(key);
    }
  }

  @Test
  public void getIntegerReturnsDefaultWhenMissing() {
    var config = new Configuration();
    assertEquals(config.getInteger("config-test.missing.int", 99), 99);
  }

  @Test
  public void getIntegerReturnsNullWhenMissing() {
    var config = new Configuration();
    assertNull(config.getInteger("config-test.missing.int"));
  }

  @Test
  public void getReadsFromEnvVarLiteralName() {
    String envValue = System.getenv("PATH");
    assertNotNull(envValue, "Test assumes PATH env var is set");
    var config = new Configuration();
    assertEquals(config.get("PATH"), envValue);
  }

  @Test
  public void getReadsFromEnvVarNormalizedName() {
    String envValue = System.getenv("PATH");
    assertNotNull(envValue, "Test assumes PATH env var is set");
    assertNull(System.getenv("path"), "Test assumes [path] is not a separate env var (case-sensitive systems)");
    var config = new Configuration();
    assertEquals(config.get("path"), envValue);
  }

  @Test
  public void getReadsFromPropertiesFile() throws Exception {
    Path file = Files.createTempFile("config-test", ".properties");
    try {
      Files.writeString(file, "my-app.some-setting=from-file\n");
      var config = new Configuration(file);
      assertEquals(config.get("my-app.some-setting"), "from-file");
    } finally {
      Files.deleteIfExists(file);
    }
  }

  @Test
  public void getReadsFromSystemProperty() {
    String key = "config-test.sysprop-only";
    System.setProperty(key, "from-sysprop");
    try {
      var config = new Configuration();
      assertEquals(config.get(key), "from-sysprop");
    } finally {
      System.clearProperty(key);
    }
  }

  @Test
  public void getReturnsDefaultWhenMissing() {
    var config = new Configuration();
    assertEquals(config.get("config-test.missing", "fallback"), "fallback");
  }

  @Test
  public void getReturnsNullWhenNotFound() {
    var config = new Configuration();
    assertNull(config.get("does.not.exist.anywhere.config.test"));
  }

  @Test
  public void getSystemPropertyOverridesFile() throws Exception {
    String key = "config-test.precedence-sysprop-vs-file";
    Path file = Files.createTempFile("config-test", ".properties");
    try {
      Files.writeString(file, key + "=from-file\n");
      System.setProperty(key, "from-sysprop");
      var config = new Configuration(file);
      assertEquals(config.get(key), "from-sysprop");
    } finally {
      System.clearProperty(key);
      Files.deleteIfExists(file);
    }
  }
}
