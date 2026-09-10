/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.internal;

import module java.base;
import module org.lattejava.json;

/**
 * The JSON shape of the flash cookie: an object with a single {@code messages} object that maps each message type to
 * the messages of that type. Wrapping the map in an object leaves room to add fields later without invalidating cookies
 * that are already in browsers.
 *
 * @param messages The pending flash messages by type. Types are in the order they were first added; each list is in
 *     the order its messages were added.
 * @author Brian Pontarelli
 */
@JSON
public record FlashCookie(Map<String, List<String>> messages) {
}
