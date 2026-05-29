/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module java.base;

/**
 * Application configuration backed by a layered lookup chain. For each setting name, sources are consulted in order and
 * the first defined value wins:
 * <ol>
 *   <li>The environment variable whose name matches the setting name verbatim.</li>
 *   <li>The environment variable whose name is the setting name uppercased with every non-alphanumeric character
 *       replaced by an underscore (so {@code my-app.some-setting} becomes {@code MY_APP_SOME_SETTING}).</li>
 *   <li>The Java system property with the same name as the setting.</li>
 *   <li>Each properties file passed to the constructor that exists on disk, consulted in the order they were
 *       supplied. Paths that do not exist are silently ignored.</li>
 * </ol>
 * <p>
 * If a setting is not defined in any source, the no-default getters return {@code null} and the default-value
 * getters return the supplied default. Required settings declared at construction time are validated immediately
 * and an {@link IllegalStateException} is thrown if any are missing.
 *
 * @author Brian Pontarelli
 */
public class Configuration {
  private final List<Properties> fileProperties = new ArrayList<>();

  /**
   * Constructs a configuration backed only by environment variables and Java system properties.
   */
  public Configuration() {
    this(List.of());
  }

  /**
   * Constructs a configuration backed by the given properties files in addition to environment variables and Java
   * system properties. Files are consulted in the order they are supplied; the first file to define a setting wins.
   *
   * @param propertiesFiles The paths to properties files readable by {@link Properties#load(java.io.InputStream)}.
   *                        Paths that do not exist are ignored.
   * @throws UncheckedIOException if a file exists but cannot be read.
   */
  public Configuration(Path... propertiesFiles) {
    this(List.of(), propertiesFiles);
  }

  /**
   * Constructs a configuration backed only by environment variables and Java system properties, validating that the
   * given required settings are defined.
   *
   * @param requiredSettings The names of settings that must be defined in at least one source.
   * @throws IllegalStateException if any required setting is not defined.
   */
  public Configuration(List<String> requiredSettings) {
    this(requiredSettings, new Path[0]);
  }

  /**
   * Constructs a configuration backed by the given properties files in addition to environment variables and Java
   * system properties, validating that the given required settings are defined. Files are consulted in the order they
   * are supplied; the first file to define a setting wins.
   *
   * @param requiredSettings The names of settings that must be defined in at least one source.
   * @param propertiesFiles  The paths to properties files readable by {@link Properties#load(java.io.InputStream)}.
   *                         Paths that do not exist are ignored.
   * @throws UncheckedIOException  if a file exists but cannot be read.
   * @throws IllegalStateException if any required setting is not defined.
   */
  public Configuration(List<String> requiredSettings, Path... propertiesFiles) {
    for (Path propertiesFile : propertiesFiles) {
      if (Files.notExists(propertiesFile)) {
        continue;
      }
      var props = new Properties();
      try (var in = Files.newInputStream(propertiesFile)) {
        props.load(in);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      fileProperties.add(props);
    }
    var missing = new ArrayList<String>();
    for (String name : requiredSettings) {
      if (get(name) == null) {
        missing.add(name);
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException("Missing required configuration settings [" + String.join(", ", missing) + "]");
    }
  }

  private static String normalizeEnvName(String name) {
    var sb = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        sb.append(Character.toUpperCase(c));
      } else {
        sb.append('_');
      }
    }
    return sb.toString();
  }

  /**
   * Returns the value of the given setting, walking the lookup chain described on the class.
   *
   * @param name The setting name.
   * @return The first value found, or {@code null} if no source defines the setting.
   */
  public String get(String name) {
    String value = System.getenv(name);
    if (value != null) {
      return value;
    }
    value = System.getenv(normalizeEnvName(name));
    if (value != null) {
      return value;
    }
    value = System.getProperty(name);
    if (value != null) {
      return value;
    }
    for (Properties props : fileProperties) {
      value = props.getProperty(name);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  /**
   * Returns the value of the given setting, or the supplied default if no source defines it.
   *
   * @param name         The setting name.
   * @param defaultValue The value to return when the setting is not defined.
   * @return The first value found, or {@code defaultValue} if no source defines the setting.
   */
  public String get(String name, String defaultValue) {
    String value = get(name);
    return value != null ? value : defaultValue;
  }

  /**
   * Returns the value of the given setting parsed as a {@link BigDecimal}.
   *
   * @param name The setting name.
   * @return The parsed value, or {@code null} if no source defines the setting.
   * @throws NumberFormatException if the value is defined but cannot be parsed as a {@link BigDecimal}.
   */
  public BigDecimal getBigDecimal(String name) {
    String value = get(name);
    return value != null ? new BigDecimal(value) : null;
  }

  /**
   * Returns the value of the given setting parsed as a {@link BigDecimal}, or the supplied default if no source defines
   * it.
   *
   * @param name         The setting name.
   * @param defaultValue The value to return when the setting is not defined.
   * @return The parsed value, or {@code defaultValue} if no source defines the setting.
   * @throws NumberFormatException if the value is defined but cannot be parsed as a {@link BigDecimal}.
   */
  public BigDecimal getBigDecimal(String name, BigDecimal defaultValue) {
    String value = get(name);
    return value != null ? new BigDecimal(value) : defaultValue;
  }

  /**
   * Returns the value of the given setting parsed as a {@link BigInteger}.
   *
   * @param name The setting name.
   * @return The parsed value, or {@code null} if no source defines the setting.
   * @throws NumberFormatException if the value is defined but cannot be parsed as a {@link BigInteger}.
   */
  public BigInteger getBigInteger(String name) {
    String value = get(name);
    return value != null ? new BigInteger(value) : null;
  }

  /**
   * Returns the value of the given setting parsed as a {@link BigInteger}, or the supplied default if no source defines
   * it.
   *
   * @param name         The setting name.
   * @param defaultValue The value to return when the setting is not defined.
   * @return The parsed value, or {@code defaultValue} if no source defines the setting.
   * @throws NumberFormatException if the value is defined but cannot be parsed as a {@link BigInteger}.
   */
  public BigInteger getBigInteger(String name, BigInteger defaultValue) {
    String value = get(name);
    return value != null ? new BigInteger(value) : defaultValue;
  }

  /**
   * Returns the value of the given setting parsed as a {@link Boolean}. A value is treated as {@code true} only when it
   * equals {@code "true"} ignoring case, per {@link Boolean#valueOf(String)}.
   *
   * @param name The setting name.
   * @return The parsed value, or {@code null} if no source defines the setting.
   */
  public Boolean getBoolean(String name) {
    String value = get(name);
    return value != null ? Boolean.valueOf(value) : null;
  }

  /**
   * Returns the value of the given setting parsed as a {@code boolean}, or the supplied default if no source defines
   * it. A value is treated as {@code true} only when it equals {@code "true"} ignoring case, per
   * {@link Boolean#parseBoolean(String)}.
   *
   * @param name         The setting name.
   * @param defaultValue The value to return when the setting is not defined.
   * @return The parsed value, or {@code defaultValue} if no source defines the setting.
   */
  public boolean getBoolean(String name, boolean defaultValue) {
    String value = get(name);
    return value != null ? Boolean.parseBoolean(value) : defaultValue;
  }

  /**
   * Returns the value of the given setting parsed as an {@link Integer}.
   *
   * @param name The setting name.
   * @return The parsed value, or {@code null} if no source defines the setting.
   * @throws NumberFormatException if the value is defined but cannot be parsed as an {@code int}.
   */
  public Integer getInteger(String name) {
    String value = get(name);
    return value != null ? Integer.valueOf(value) : null;
  }

  /**
   * Returns the value of the given setting parsed as an {@code int}, or the supplied default if no source defines it.
   *
   * @param name         The setting name.
   * @param defaultValue The value to return when the setting is not defined.
   * @return The parsed value, or {@code defaultValue} if no source defines the setting.
   * @throws NumberFormatException if the value is defined but cannot be parsed as an {@code int}.
   */
  public int getInteger(String name, int defaultValue) {
    String value = get(name);
    return value != null ? Integer.parseInt(value) : defaultValue;
  }
}
