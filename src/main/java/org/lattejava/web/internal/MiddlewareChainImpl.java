/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.internal;

import java.util.List;

import org.lattejava.http.server.HTTPRequest;
import org.lattejava.http.server.HTTPResponse;
import org.lattejava.web.Handler;
import org.lattejava.web.Middleware;
import org.lattejava.web.MiddlewareChain;

/**
 * Executes a list of middlewares followed by the terminal handler.
 *
 * @author Brian Pontarelli
 */
public class MiddlewareChainImpl implements MiddlewareChain {
  private final List<Middleware> middlewares;

  private final Handler handler;

  private int index = 0;

  public MiddlewareChainImpl(List<Middleware> middlewares, Handler handler) {
    this.middlewares = middlewares;
    this.handler = handler;
  }

  @Override
  public void next(HTTPRequest req, HTTPResponse res) throws Exception {
    if (index >= middlewares.size()) {
      handler.handle(req, res);
    } else {
      Middleware m = middlewares.get(index++);
      m.handle(req, res, this);
    }
  }
}
