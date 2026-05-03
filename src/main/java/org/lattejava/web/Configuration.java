/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
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
 *   <li>The optional properties file passed to the constructor.</li>
 * </ol>
 * <p>
 * If a setting is not defined in any source, the no-default getters return {@code null} and the default-value
 * getters return the supplied default. Required settings declared at construction time are validated immediately
 * and an {@link IllegalStateException} is thrown if any are missing.
 *
 * @author Brian Pontarelli
 */
public class Configuration {
  private final Properties fileProperties = new Properties();

  /**
   * Constructs a configuration backed only by environment variables and Java system properties.
   */
  public Configuration() {
    this(null, List.of());
  }

  /**
   * Constructs a configuration backed only by environment variables and Java system properties, validating that the
   * given required settings are defined.
   *
   * @param requiredSettings The names of settings that must be defined in at least one source.
   * @throws IllegalStateException if any required setting is not defined.
   */
  public Configuration(List<String> requiredSettings) {
    this(null, requiredSettings);
  }

  /**
   * Constructs a configuration backed by the given properties file in addition to environment variables and Java system
   * properties.
   *
   * @param propertiesFile The path to a properties file readable by {@link Properties#load(java.io.InputStream)}.
   * @throws UncheckedIOException if the file cannot be read.
   */
  public Configuration(Path propertiesFile) {
    this(propertiesFile, List.of());
  }

  /**
   * Constructs a configuration backed by the given properties file in addition to environment variables and Java system
   * properties, validating that the given required settings are defined.
   *
   * @param propertiesFile   The path to a properties file readable by {@link Properties#load(java.io.InputStream)}.
   * @param requiredSettings The names of settings that must be defined in at least one source.
   * @throws UncheckedIOException  if the file cannot be read.
   * @throws IllegalStateException if any required setting is not defined.
   */
  public Configuration(Path propertiesFile, List<String> requiredSettings) {
    if (propertiesFile != null) {
      try (var in = Files.newInputStream(propertiesFile)) {
        fileProperties.load(in);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
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
    return fileProperties.getProperty(name);
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
