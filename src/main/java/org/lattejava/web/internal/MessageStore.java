/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.internal;

import module java.base;

/**
 * Loads and caches the properties files under a messages directory. Each file is read once, on first use, and kept for
 * the life of the store; missing files are remembered as missing. One store lives in each {@code HTTPContext}, so the
 * cache is shared by every request the server handles.
 *
 * @author Brian Pontarelli
 */
public final class MessageStore {
  private static final Properties MISSING = new Properties();

  private final ConcurrentMap<String, Properties> cache = new ConcurrentHashMap<>();
  private final Path directory;

  /**
   * Creates a store over the given directory. The directory need not exist; every lookup then reads as missing.
   *
   * @param directory The messages directory.
   */
  public MessageStore(Path directory) {
    this.directory = Objects.requireNonNull(directory, "directory must not be null").toAbsolutePath().normalize();
  }

  /**
   * Returns the properties in the given file, reading it on first use.
   *
   * @param relative The file path relative to the messages directory (for example
   *                 {@code admin/users/edit_de.properties}).
   * @return The properties, or {@code null} if the file does not exist, is not a regular file, or resolves outside the
   *     messages directory.
   * @throws UncheckedIOException if the file exists but cannot be read.
   */
  public Properties load(String relative) {
    Properties props = cache.computeIfAbsent(relative, this::read);
    return props == MISSING ? null : props;
  }

  private Properties read(String relative) {
    Path file = directory.resolve(relative).normalize();
    if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
      return MISSING;
    }

    Properties props = new Properties();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      props.load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read message file [" + file + "]", e);
    }
    return props;
  }
}
