/*
 * Copyright (c) 2025-2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.internal;

import java.net.*;
import java.security.cert.*;
import java.util.*;

import org.lattejava.http.server.*;

/**
 * Some web tools and stuff.
 *
 * @author Brian Pontarelli
 */
public class WebTools {
  public static String bindAddressHost(InetAddress address) {
    // InetAddress retains the name it was created from, and toString() exposes it without triggering the reverse DNS
    // lookup that getHostName() performs (the name part is empty when the address was created from an IP literal)
    String repr = address.toString();
    String name = repr.substring(0, repr.indexOf('/'));
    if (!name.isEmpty() && !isIPLiteral(name)) {
      return name;
    }

    if (address.isAnyLocalAddress() || address.isLoopbackAddress()) {
      return "localhost";
    }

    if (address instanceof Inet6Address) {
      return "[" + address.getHostAddress() + "]";
    }

    return address.getHostAddress();
  }

  public static String buildURL(HTTPListenerConfiguration listener) {
    String scheme = listener.isTLS() ? "https" : "http";
    String host = certificateHost(listener);
    if (host == null) {
      host = bindAddressHost(listener.getBindAddress());
    }

    int port = listener.getPort();
    if ((listener.isTLS() && port == 443) || (!listener.isTLS() && port == 80)) {
      return scheme + "://" + host;
    }

    return scheme + "://" + host + ":" + port;
  }

  public static String certificateHost(HTTPListenerConfiguration listener) {
    if (!(listener.getCertificate() instanceof X509Certificate x509)) {
      return null;
    }

    try {
      Collection<List<?>> names = x509.getSubjectAlternativeNames();
      if (names == null) {
        return null;
      }

      // Entries are [type, value] pairs; type 2 is dNSName (RFC 5280). Wildcard names can't be used as a URL host.
      for (List<?> entry : names) {
        if (entry.size() >= 2 && Integer.valueOf(2).equals(entry.getFirst()) &&
            entry.get(1) instanceof String name && !name.startsWith("*")) {
          return name;
        }
      }
    } catch (CertificateParsingException e) {
      // Fall through to the bind address
    }

    return null;
  }

  public static boolean isIPLiteral(String name) {
    // Colons only appear in IPv6 literals; a name that is nothing but digits and dots is an IPv4 literal
    return name.indexOf(':') >= 0 || name.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.');
  }

  public static boolean isValidMethodToken(String method) {
    int len = method.length();
    if (len == 0) {
      return false;
    }
    for (int i = 0; i < len; i++) {
      char c = method.charAt(i);
      if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
        return false;
      }
    }
    return true;
  }
}
