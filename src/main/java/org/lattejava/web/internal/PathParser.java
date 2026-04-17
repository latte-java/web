/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates path specifications and parses them into a list of {@link Segment}s using a
 * character-by-character finite state machine.
 *
 * @author Brian Pontarelli
 */
public class PathParser {
  /**
   * A segment of a parsed path specification.
   */
  public sealed interface Segment permits Segment.Literal, Segment.Param {
    record Literal(String value) implements Segment {}

    record Param(String name) implements Segment {}
  }

  private enum State {
    INITIAL,
    SEGMENT_START,
    LITERAL,
    PARAM_NAME_START,
    PARAM_NAME,
    PARAM_END
  }

  /**
   * Validates and parses the given path specification into a list of segments.
   *
   * @param pathSpec the path specification to parse (e.g., {@code /api/users/{id}})
   * @return a list of {@link Segment} objects representing each path segment
   * @throws IllegalArgumentException if the path specification is invalid
   */
  public static List<Segment> parse(String pathSpec) {
    Objects.requireNonNull(pathSpec, "pathSpec must not be null");

    if (pathSpec.isEmpty()) {
      throw new IllegalArgumentException("pathSpec must not be empty");
    }

    List<Segment> segments = new ArrayList<>();
    Set<String> seenParamNames = new HashSet<>();
    State state = State.INITIAL;
    StringBuilder buffer = new StringBuilder();

    for (int i = 0; i < pathSpec.length(); i++) {
      char c = pathSpec.charAt(i);

      switch (state) {
        case INITIAL -> {
          if (c == '/') {
            state = State.SEGMENT_START;
          } else {
            throw new IllegalArgumentException(
                "pathSpec must start with [/]: [" + pathSpec + "]");
          }
        }
        case SEGMENT_START -> {
          if (c == '/') {
            segments.add(new Segment.Literal(""));
            // state stays SEGMENT_START
          } else if (c == '{') {
            state = State.PARAM_NAME_START;
          } else if (isValidPathCharacter(c)) {
            buffer.append(c);
            state = State.LITERAL;
          } else {
            throw new IllegalArgumentException("pathSpec contains invalid character [" + c + "] at position " + i + ": [" + pathSpec + "]");
          }
        }
        case LITERAL -> {
          if (c == '/') {
            segments.add(new Segment.Literal(buffer.toString()));
            buffer.setLength(0);
            state = State.SEGMENT_START;
          } else if (c == '{') {
            throw new IllegalArgumentException(
                "Invalid parameter syntax — mixed literal and parameter in segment: [" + buffer + "] in [" + pathSpec + "]");
          } else if (c == '}') {
            throw new IllegalArgumentException(
                "Invalid parameter syntax (unopened [}]): in [" + pathSpec + "]");
          } else if (isValidPathCharacter(c)) {
            buffer.append(c);
          } else {
            throw new IllegalArgumentException(
                "pathSpec contains invalid character [" + c + "] at position " + i + ": [" + pathSpec + "]");
          }
        }
        case PARAM_NAME_START -> {
          if (c == '}') {
            throw new IllegalArgumentException(
                "Empty parameter name: [{}] in [" + pathSpec + "]");
          } else if (Character.isLetter(c) || c == '_') {
            buffer.append(c);
            state = State.PARAM_NAME;
          } else {
            throw new IllegalArgumentException(
                "Invalid parameter name starting with [" + c + "] in [" + pathSpec + "]");
          }
        }
        case PARAM_NAME -> {
          if (c == '}') {
            String name = buffer.toString();
            buffer.setLength(0);
            if (seenParamNames.contains(name)) {
              throw new IllegalArgumentException(
                  "Duplicate parameter name [" + name + "] in pathSpec [" + pathSpec + "]");
            }
            seenParamNames.add(name);
            segments.add(new Segment.Param(name));
            state = State.PARAM_END;
          } else if (Character.isLetterOrDigit(c) || c == '_') {
            buffer.append(c);
          } else {
            throw new IllegalArgumentException(
                "Invalid character [" + c + "] in parameter name in [" + pathSpec + "]");
          }
        }
        case PARAM_END -> {
          if (c == '/') {
            state = State.SEGMENT_START;
          } else {
            throw new IllegalArgumentException(
                "Unexpected content after parameter at position " + i + " in [" + pathSpec + "]");
          }
        }
      }
    }

    // Handle end-of-string
    switch (state) {
      case INITIAL -> throw new IllegalArgumentException("pathSpec must not be empty");
      case SEGMENT_START -> segments.add(new Segment.Literal(""));
      case LITERAL -> {
        segments.add(new Segment.Literal(buffer.toString()));
      }
      case PARAM_NAME_START -> throw new IllegalArgumentException(
          "Invalid parameter syntax (unclosed [{]): in [" + pathSpec + "]");
      case PARAM_NAME -> throw new IllegalArgumentException(
          "Invalid parameter syntax (unclosed [{]): in [" + pathSpec + "]");
      case PARAM_END -> {
        // Valid end state — param was already added
      }
    }

    return segments;
  }

  private static boolean isValidPathCharacter(char c) {
    // RFC 3986 pchar minus '%'
    if (c >= 'A' && c <= 'Z') return true;
    if (c >= 'a' && c <= 'z') return true;
    if (c >= '0' && c <= '9') return true;
    // unreserved marks
    if (c == '-' || c == '.' || c == '_' || c == '~') return true;
    // sub-delims
    if (c == '!' || c == '$' || c == '&' || c == '\'' || c == '(' || c == ')') return true;
    if (c == '*' || c == '+' || c == ',' || c == ';' || c == '=') return true;
    // extra
    if (c == ':' || c == '@') return true;
    return false;
  }
}
