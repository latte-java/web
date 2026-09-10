package org.lattejava.web.middleware;

import org.lattejava.http.server.*;
import org.lattejava.web.*;

/**
 * Runs the wrapped middleware only for requests whose path starts with the given prefix. Other requests go straight to
 * the next middleware in the chain.
 *
 * @author Brian Pontarelli
 */
public class FilteredMiddleware implements Middleware {
  private final Middleware middleware;
  private final String prefix;

  public FilteredMiddleware(String prefix, Middleware middleware) {
    this.middleware = middleware;
    this.prefix = prefix;
  }

  @Override
  public void handle(HTTPRequest req, HTTPResponse res, MiddlewareChain chain) throws Exception {
    // This forks and assumes that the filtered middleware will call chain.next()
    if (req.getPath().startsWith(prefix)) {
      middleware.handle(req, res, chain);
    } else {
      chain.next(req, res);
    }
  }
}
