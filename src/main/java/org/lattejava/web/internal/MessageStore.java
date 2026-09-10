/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.internal;

import module java.base;

/**
 * Loads and caches the properties files under a messages directory. Each file is read on first use and kept until it
 * changes on disk. Every lookup checks the file's last-modified time and size, and a file whose stamp differs from the
 * cached one is read again, so an edit, a new file, or a deleted file is picked up on the next lookup without a
 * restart. Missing files are remembered as missing until they appear. {@code Messages} keeps one store in each
 * {@code HTTPContext}, so the cache is shared by every request the server handles.
 *
 * @author Brian Pontarelli
 */
public final class MessageStore {
  private static final Entry MISSING = new Entry(null, null);

  private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();
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
   * Returns the properties in the given file, reading it on first use and again whenever its last-modified time or
   * size has changed since it was last read.
   *
   * @param relative The file path relative to the messages directory (for example
   *                 {@code admin/users/edit_de.properties}).
   * @return The properties, or {@code null} if the file does not exist, is not a regular file, or resolves outside the
   *     messages directory.
   * @throws UncheckedIOException if the file exists but cannot be read.
   */
  public Properties load(String relative) {
    Path file = directory.resolve(relative).normalize();
    if (!file.startsWith(directory)) {
      return null;
    }

    Stamp stamp = stamp(file);
    Entry entry = cache.get(relative);
    if (entry == null || !Objects.equals(entry.stamp, stamp)) {
      entry = cache.compute(relative, (_, current) -> current != null && Objects.equals(current.stamp, stamp) ? current : read(file, stamp));
    }
    return entry.props;
  }

  private static Entry read(Path file, Stamp stamp) {
    if (stamp == null) {
      return MISSING;
    }

    Properties props = new Properties();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      props.load(reader);
    } catch (NoSuchFileException e) {
      // Deleted between the stamp and the read; the next lookup sees the new stamp
      return MISSING;
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read message file [" + file + "]", e);
    }
    return new Entry(stamp, props);
  }

  /**
   * Reads the file's current stamp with a single attribute lookup.
   *
   * @param file The file.
   * @return The stamp, or {@code null} if the file does not exist or is not a regular file.
   */
  private static Stamp stamp(Path file) {
    try {
      BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
      return attributes.isRegularFile() ? new Stamp(attributes.lastModifiedTime(), attributes.size()) : null;
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * A cached file. A missing file has a {@code null} stamp and {@code null} properties.
   */
  private record Entry(Stamp stamp, Properties props) {
  }

  /**
   * The on-disk identity of a file at the time it was read. Size is included because some file systems keep
   * last-modified time at one-second resolution.
   */
  private record Stamp(FileTime modified, long size) {
  }
}
