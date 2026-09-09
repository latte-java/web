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
 * sees. The messages live in the {@value #COOKIE_NAME} cookie, which the
 * {@link org.lattejava.web.middleware.FlashMessages} middleware carries between requests.
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
 *   new Flash(req).addMessage("Settings saved");
 *   res.sendRedirect("/settings");
 * });
 * }</pre>
 * Templates read them. {@link org.lattejava.web.jte.JTETemplates} always binds the {@code request} parameter, so a JTE
 * template only needs:
 * <pre>{@code
 * @import org.lattejava.http.server.HTTPRequest
 * @import org.lattejava.web.Flash
 * @param HTTPRequest request
 * @for(String message : new Flash(request).messages())
 *   <div class="flash">${message}</div>
 * @endfor
 * }</pre>
 * Messages survive any number of redirects and non-GET requests. They are consumed (removed from the cookie) when a GET
 * request completes with a non-redirect status, on the assumption that the page rendered by that request displayed
 * them. Messages added during such a GET are visible to that request's template but are not carried forward.
 * <p>
 * The cookie value is the JSON document {@code {"messages":[...]}} encoded as unpadded Base64URL so that it is
 * cookie-safe. It is neither encrypted nor signed: the browser can read and edit it, which only affects the messages
 * that browser sees. A value that cannot be decoded or parsed, or whose array holds anything other than strings and
 * nulls, reads as no messages; nulls are dropped. Render messages with the same escaping as any other user-supplied
 * text.
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
   * Queues a message by replacing the request's flash cookie with one that also holds the message.
   *
   * @param message The message.
   * @return This Flash for chaining.
   */
  public Flash addMessage(String message) {
    Objects.requireNonNull(message, "message must not be null");
    List<String> messages = new ArrayList<>(messages());
    messages.add(message);

    byte[] json = FlashCookieJSON.toJSONBytes(new FlashCookie(messages));
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    req.addCookies(new Cookie(COOKIE_NAME, value));
    return this;
  }

  /**
   * Discards every queued message, including any carried in from earlier requests, by removing the request's flash
   * cookie.
   *
   * @return This Flash for chaining.
   */
  public Flash clear() {
    req.deleteCookie(COOKIE_NAME);
    return this;
  }

  /**
   * @return {@code true} if at least one message is queued.
   */
  public boolean hasMessages() {
    return !messages().isEmpty();
  }

  /**
   * Parses the request's flash cookie.
   *
   * @return The queued messages in the order they were added, as an unmodifiable list. Empty if the cookie is absent
   *     or unreadable.
   */
  public List<String> messages() {
    Cookie cookie = req.getCookie(COOKIE_NAME);
    if (cookie == null || cookie.value == null || cookie.value.isEmpty()) {
      return List.of();
    }

    try {
      FlashCookie parsed = FlashCookieJSON.fromJSON(Base64.getUrlDecoder().decode(cookie.value));
      if (parsed.messages() == null) {
        return List.of();
      }

      // The browser can edit the cookie. The parser rejects non-string elements but passes null through, so drop those.
      List<String> messages = new ArrayList<>(parsed.messages().size());
      for (Object element : parsed.messages()) {
        if (element instanceof String s) {
          messages.add(s);
        }
      }
      return List.copyOf(messages);
    } catch (IllegalArgumentException | JSONProcessingException e) {
      LOG.log(System.Logger.Level.DEBUG, "Ignoring unreadable flash cookie: {0}", e.getMessage());
      return List.of();
    }
  }
}
