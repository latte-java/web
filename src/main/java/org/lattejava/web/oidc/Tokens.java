/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.web.oidc;

import module org.lattejava.json;

/**
 * A set of OIDC tokens and their expiry. Any field may be {@code null} — a request might present only an access token,
 * a refresh response might omit the refresh or id token, and the expiry is unknown until a token endpoint reports it.
 * <p>
 * This single record is used throughout the OIDC subsystem: as the result of a refresh exchange
 * ({@code Tools.refresh}), as the value a {@link TokenReader} pulls off a request and a {@link TokenWriter} puts back,
 * and as the return of the test fixture's login helper.
 *
 * @param accessToken  The access token, or {@code null}.
 * @param refreshToken The refresh token, or {@code null}.
 * @param idToken      The id token, or {@code null}.
 * @param expiresIn    The access-token lifetime in seconds, or {@code null} if unknown.
 * @author Brian Pontarelli
 */
@JSON(naming = NamingStrategy.SNAKE_CASE)
public record Tokens(String accessToken, String refreshToken, String idToken, Long expiresIn) {
}
