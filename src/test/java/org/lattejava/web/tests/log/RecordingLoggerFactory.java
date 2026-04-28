/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.log;

import module java.base;
import module org.lattejava.http;

/**
 * Test helper that records every log call for inspection. Returns a single shared {@link RecordingLogger} for any
 * class — matching the singleton semantics of the bundled factories so {@code setLevel} affects every observer.
 *
 * @author Brian Pontarelli
 */
public class RecordingLoggerFactory implements LoggerFactory {
  public final RecordingLogger logger = new RecordingLogger();

  @Override
  public Logger getLogger(Class<?> klass) {
    return logger;
  }

  public static final class Entry {
    public final Level level;
    public final String message;
    public final Throwable throwable;

    Entry(Level level, String message, Throwable throwable) {
      this.level = level;
      this.message = message;
      this.throwable = throwable;
    }
  }

  public static final class RecordingLogger implements Logger {
    public final List<Entry> entries = new ArrayList<>();
    private Level level = Level.Info;

    @Override
    public void debug(String message) {
      record(Level.Debug, message, null);
    }

    @Override
    public void debug(String message, Object... values) {
      record(Level.Debug, format(message, values), null);
    }

    @Override
    public void debug(String message, Throwable throwable) {
      record(Level.Debug, message, throwable);
    }

    @Override
    public void error(String message) {
      record(Level.Error, message, null);
    }

    @Override
    public void error(String message, Throwable throwable) {
      record(Level.Error, message, throwable);
    }

    @Override
    public void info(String message) {
      record(Level.Info, message, null);
    }

    @Override
    public void info(String message, Object... values) {
      record(Level.Info, format(message, values), null);
    }

    @Override
    public boolean isDebugEnabled() {
      return level.ordinal() <= Level.Debug.ordinal();
    }

    @Override
    public boolean isErrorEnabled() {
      return level.ordinal() <= Level.Error.ordinal();
    }

    @Override
    public boolean isInfoEnabled() {
      return level.ordinal() <= Level.Info.ordinal();
    }

    @Override
    public boolean isTraceEnabled() {
      return level.ordinal() <= Level.Trace.ordinal();
    }

    @Override
    public void setLevel(Level level) {
      this.level = level;
    }

    @Override
    public void trace(String message) {
      record(Level.Trace, message, null);
    }

    @Override
    public void trace(String message, Object... values) {
      record(Level.Trace, format(message, values), null);
    }

    public List<String> messagesAtLevel(Level wanted) {
      List<String> out = new ArrayList<>();
      for (Entry e : entries) {
        if (e.level == wanted) {
          out.add(e.message);
        }
      }
      return out;
    }

    private String format(String message, Object[] values) {
      for (Object value : values) {
        String replacement = value != null ? value.toString() : "null";
        message = message.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(replacement));
      }
      return message;
    }

    private void record(Level entryLevel, String message, Throwable throwable) {
      if (entryLevel.ordinal() < level.ordinal()) {
        return;
      }
      entries.add(new Entry(entryLevel, message, throwable));
    }
  }
}
