# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.0] - 2026-09-10

### Added

- `Messages` constructors that take a base directory, request path, and optional locale instead of a request. Tests that drive a server through `WebTest` can fetch the expected text from the same files the server reads.

### Changed

- `Messages` reloads a properties file when it changes on disk. Every lookup compares the file's last-modified time and size to the cached copy, so edits, new files, and deleted files take effect on the next request without a restart.

## [0.8.0] - 2026-09-10

### Added

- `Messages` for locale-aware display text loaded from properties files under `web/messages`, laid out to mirror the URL path. Lookups fall back from page to section to site. `MissingMessageException` is thrown for undefined keys via `get`.
- `Flash` wrapper around the `flash` cookie for messages that survive a redirect, plus the `FlashMessages` middleware that keeps the cookie in step with the request.
- Dependency injection support. Register an `Injector` with `Web.injector(...)` and use `Web.inject(...)` to construct `Middleware`, `Handler`, controller handlers, and controller body handlers from any DI framework.

[Unreleased]: https://github.com/latte-java/web/compare/0.8.1...HEAD
[0.8.1]: https://github.com/latte-java/web/compare/0.8.0...0.8.1
[0.8.0]: https://github.com/latte-java/web/compare/0.7.0...0.8.0
