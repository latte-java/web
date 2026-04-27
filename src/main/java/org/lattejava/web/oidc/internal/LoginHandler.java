package org.lattejava.web.oidc.internal;

import module java.base;
import module org.lattejava.http;
import module org.lattejava.web;

/**
 * A handler that redirects the user to the OIDC provider's authorization endpoint. This handles PCKE as well.
 *
 * @author Brian Pontarelli
 */
public class LoginHandler implements Handler {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final OIDCConfig config;

  public LoginHandler(OIDCConfig config) {
    this.config = config;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res) throws Exception {
    byte[] stateBytes = new byte[22]; // 22 bytes -> 44 hex chars; 128 bits of entropy; satisfies PKCE's >=43-char rule
    RANDOM.nextBytes(stateBytes);
    String state = HexFormat.of().formatHex(stateBytes);
    String codeChallenge = Tools.computeCodeChallenge(state);

    Tools.addTransientCookie(req, res, config.stateCookieName(), state);

    URI redirectURI = config.fullRedirectURI(req);
    StringBuilder url = new StringBuilder(config.authorizeEndpoint().toString());
    url.append(url.indexOf("?") < 0 ? '?' : '&');
    url.append("response_type=code");
    url.append("&client_id=").append(URLEncoder.encode(config.clientId(), StandardCharsets.UTF_8));
    url.append("&redirect_uri=").append(URLEncoder.encode(redirectURI.toString(), StandardCharsets.UTF_8));
    url.append("&scope=").append(URLEncoder.encode(String.join(" ", config.scopes()), StandardCharsets.UTF_8));
    url.append("&state=").append(state);
    url.append("&code_challenge=").append(codeChallenge);
    url.append("&code_challenge_method=S256");

    res.sendRedirect(url.toString());
  }
}
