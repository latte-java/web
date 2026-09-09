/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.internal;

import module java.base;
import module org.lattejava.json;

/**
 * The JSON shape of the flash cookie: an object with a single {@code messages} array. Wrapping the array in an object
 * leaves room to add fields later without invalidating cookies that are already in browsers.
 *
 * @param messages The pending flash messages, in the order they were added.
 * @author Brian Pontarelli
 */
@JSON
public record FlashCookie(List<String> messages) {
}
