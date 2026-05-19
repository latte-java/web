package org.lattejava.web.middleware;

import org.lattejava.http.server.*;
import org.lattejava.web.*;

/**
 * This middleware takes a path prefix and only applies the given Middleware if the path matches the prefix.
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
