/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web;

import module java.base;
import module org.lattejava.http;

import org.lattejava.web.internal.*;
import org.lattejava.web.internal.internal.*;

/**
 * Flash messages for the current request: short strings queued by one request and displayed by the next page the user
 * sees. Each message has a type, a freeform string such as {@code "info"} or {@code "error"} that the application
 * chooses and that usually decides how the message is styled. The messages live in the {@value #COOKIE_NAME} cookie,
 * which the {@link org.lattejava.web.middleware.FlashMessages} middleware carries between requests.
 * <p>
 * This is a thin wrapper around the request. Reading parses the request's flash cookie; adding or clearing replaces
 * that cookie on the request, so every {@code new Flash(req)} within a request sees the same messages, and the
 * middleware writes the final cookie to the response once the handler returns. Without the middleware, reading works
 * but changes never reach the browser.
 * <p>
 * Handlers add messages, typically right before redirecting:
 * <pre>{@code
 * web.post("/settings", (req, res) -> {
 *   ...
 *   new Flash(req).addMessage("success", "Settings saved");
 *   res.sendRedirect("/settings");
 * });
 * }</pre>
 * Templates read them, either grouped by type or one type at a time. {@link org.lattejava.web.jte.JTETemplates} always
 * binds the {@code request} parameter, so a JTE template only needs:
 * <pre>{@code
 * @import org.lattejava.http.server.HTTPRequest
 * @import org.lattejava.web.Flash
 * @param HTTPRequest request
 * @for(var entry : new Flash(request).messages().entrySet())
 *   @for(String message : entry.getValue())
 *     <div class="flash ${entry.getKey()}">${message}</div>
 *   @endfor
 * @endfor
 * }</pre>
 * Messages survive any number of redirects and non-GET requests. They are consumed (removed from the cookie) when a GET
 * request completes with a non-redirect status, on the assumption that the page rendered by that request displayed
 * them. Messages added during such a GET are visible to that request's template but are not carried forward.
 * <p>
 * The cookie value is the JSON document {@code {"messages":{"<type>":["..."],...}}} encoded as unpadded Base64URL so
 * that it is cookie-safe. It is neither encrypted nor signed: the browser can read and edit it, which only affects the
 * messages that browser sees. A value that cannot be decoded or parsed, or whose {@code messages} member is anything
 * other than an object whose values are arrays of strings and nulls, reads as no messages. Nulls are dropped, as is any
 * type left with no messages. Render types and messages with the same escaping as any other user-supplied text.
 *
 * @author Brian Pontarelli
 */
public final class Flash {
  /**
   * The name of the cookie that carries the messages.
   */
  public static final String COOKIE_NAME = "flash";

  private static final System.Logger LOG = System.getLogger(Flash.class.getName());

  private final HTTPRequest req;

  /**
   * Wraps the flash messages of the given request.
   *
   * @param req The current request.
   */
  public Flash(HTTPRequest req) {
    this.req = Objects.requireNonNull(req, "req must not be null");
  }

  /**
   * Queues a message of the given type by replacing the request's flash cookie with one that also holds the message.
   *
   * @param type The message type, such as {@code "info"} or {@code "error"}. Types are freeform; this class attaches
   *     no meaning to them.
   * @param message The message.
   * @return This Flash for chaining.
   */
  public Flash addMessage(String type, String message) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(message, "message must not be null");
    Map<String, List<String>> messages = new LinkedHashMap<>();
    messages().forEach((t, list) -> messages.put(t, new ArrayList<>(list)));
    messages.computeIfAbsent(type, _ -> new ArrayList<>()).add(message);

    byte[] json = FlashCookieJSON.toJSONBytes(new FlashCookie(messages));
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    req.addCookies(new Cookie(COOKIE_NAME, value));
    return this;
  }

  /**
   * Discards every queued message of every type, including any carried in from earlier requests, by removing the
   * request's flash cookie.
   *
   * @return This Flash for chaining.
   */
  public Flash clear() {
    req.deleteCookie(COOKIE_NAME);
    return this;
  }

  /**
   * @return {@code true} if at least one message of any type is queued.
   */
  public boolean hasMessages() {
    return !messages().isEmpty();
  }

  /**
   * @param type The message type.
   * @return {@code true} if at least one message of the given type is queued.
   */
  public boolean hasMessages(String type) {
    return !messages(type).isEmpty();
  }

  /**
   * Parses the request's flash cookie.
   *
   * @return The queued messages grouped by type, as an unmodifiable map of unmodifiable lists. Types are in the order
   *     they were first added and each list is in the order its messages were added. Only types with at least one
   *     message are present. Empty if the cookie is absent or unreadable.
   */
  public Map<String, List<String>> messages() {
    Cookie cookie = req.getCookie(COOKIE_NAME);
    if (cookie == null || cookie.value == null || cookie.value.isEmpty()) {
      return Map.of();
    }

    try {
      FlashCookie parsed = FlashCookieJSON.fromJSON(Base64.getUrlDecoder().decode(cookie.value));
      if (parsed.messages() == null) {
        return Map.of();
      }

      // The browser can edit the cookie. The parser rejects values and elements of the wrong shape but passes nulls
      // through, so drop those along with any type that has nothing left.
      Map<String, List<String>> messages = new LinkedHashMap<>();
      parsed.messages().forEach((type, list) -> {
        if (list == null) {
          return;
        }

        List<String> strings = new ArrayList<>(list.size());
        for (Object element : list) {
          if (element instanceof String s) {
            strings.add(s);
          }
        }

        if (!strings.isEmpty()) {
          messages.put(type, List.copyOf(strings));
        }
      });
      return Collections.unmodifiableMap(messages);
    } catch (IllegalArgumentException | JSONProcessingException e) {
      LOG.log(System.Logger.Level.DEBUG, "Ignoring unreadable flash cookie: {0}", e.getMessage());
      return Map.of();
    }
  }

  /**
   * Parses the request's flash cookie and returns the messages of one type.
   *
   * @param type The message type.
   * @return The queued messages of that type in the order they were added, as an unmodifiable list. Empty if there are
   *     none or the cookie is absent or unreadable.
   */
  public List<String> messages(String type) {
    Objects.requireNonNull(type, "type must not be null");
    return messages().getOrDefault(type, List.of());
  }
}
