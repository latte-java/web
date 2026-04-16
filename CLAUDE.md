# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Latte Java Web — a lightweight web framework built on the Latte Java HTTP server (`org.lattejava:http`). Early stage: core routing is implemented, middleware and body handling are still in design (see `design/initial-brainstorming.md`).

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

The framework is minimal — two public types in `org.lattejava.web`:

- **`Web`** — main entry point. Registers routes via `route(pathSpec, handler)`, starts an HTTP server via `start(port)`. Routes are matched in registration order using regex compiled from path specs. Path parameters use `{name}` syntax and are set as request attributes. Returns 404 if no route matches.
- **`Handler`** — `@FunctionalInterface` with signature `void handle(HTTPRequest req, HTTPResponse res) throws Exception`. Supports lambdas.

The `Web` class wraps `HTTPServer` from the `org.lattejava.http` module. The inner `Route` class converts path specs like `/api/user/{id}` into regex patterns and extracts parameter names.

## Conventions

- License header on all source files (MIT, Latte Java, 2025-2026)
- Fluent API with method chaining
- Tests use TestNG (not JUnit)
