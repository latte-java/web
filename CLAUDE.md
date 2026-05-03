# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Latte Java Web — a lightweight web framework built on the Latte Java HTTP server (`org.lattejava:http`). Early stage: core routing is implemented, middleware and body handling are still in design (see `docs/design/2026-04-09-initial-brainstorming.md`).

## Documentation

- `docs/design/` — all design documents and specs (filenames prefixed with `YYYY-MM-DD-` creation date)
- `docs/implementation/` — all implementation plans (filenames prefixed with `YYYY-MM-DD-` creation date)

## Build System

Uses the **Latte** build tool (not Maven/Gradle). Key commands:

- `latte build` — compile and JAR
- `latte test` — run tests (TestNG)
- `latte clean` — clean build artifacts
- `latte int` — local integration build (build + test + publish locally)
- `latte release` — full release (clean + test + publish)

Build config is in `project.latte` (Groovy DSL). Using `--debug` allows for interactive debugging when Latte runs into issues with the project file or the build process.

## Java Version

Java 25. Uses the Java module system (`module-info.java`).

## Architecture

Two packages:

- `org.lattejava.web` (exported) — public API: `Web` and `Handler`
- `org.lattejava.web.internal` (not exported) — implementation: `PathParser`, `RouteTrie`

### Public API

- **`Web`** — fluent entry point. Register routes via `route(methods, pathSpec, handler)` or per-verb shortcuts (`get`, `post`, `put`, `delete`, `patch`, `head`, `options`). Group routes with `prefix(pathPrefix, consumer)` (nestable, returns parent for chaining). Start the server with `start(port)` and close via `close()` (or try-with-resources — `Web implements Closeable`). Registration is locked after `start()`; a JVM shutdown hook is auto-registered to close the server on exit.
- **`Handler`** — `@FunctionalInterface` with signature `void handle(HTTPRequest req, HTTPResponse res) throws Exception`. Supports lambdas and method references.

### Routing

- **`RouteTrie`** — segment-level trie. Each node has a `Map<String, Node> staticChildren` (keyed by literal segment text), a single `paramChild` for `{name}` patterns, and a `Map<String, Handler> handlersByMethod` at terminal nodes. Matching is O(path_length) with backtracking: static children are checked before the param child (literal beats param structurally, no scoring). When a terminal matches but the method doesn't, the matcher falls through to the param branch so `/users/new` (GET) and `/users/{id}` (POST) can coexist. Allow-header method sets are unioned across all path-matching branches.
- **`PathParser`** — character-by-character FSM that validates pathSpec and returns a `List<Segment>` (sealed: `Literal(String)` / `Param(String)`). Enforces RFC 3986 pchar minus `%` for literal characters, Java-identifier rules for parameter names, and rejects duplicate parameter names.
- Returns **404** when no route matches the path, **405** (with aggregated `Allow` header) when a route matches the path but not the method. Path parameters are set as request attributes via `req.getAttribute(name)`.

### Lifecycle

- `start(port)` only succeeds once (`AtomicBoolean started`). Shared between parent and child `Web` (created via `prefix()`) so registration is locked globally after start.
- `close()` is idempotent; concurrent-safe via `AtomicReference<HTTPServer>`.

## Conventions

- License header on all source files (MIT, Latte Java, 2025-2026)
- Fluent API with method chaining
- Tests use TestNG (not JUnit)
- Topic-specific code rules are in `.claude/rules/` (auto-loaded)
