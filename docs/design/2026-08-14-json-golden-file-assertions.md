# JSON Golden-File Assertions

## Problem

Asserting a JSON response against the `@JSON` domain object is circular: the same serializer produces both sides, so
an unintended change to the object (drift, wire-format break) still passes. The expected side must be literal text,
independent of the code that produced the response.

## Decision

Add a golden-file assertion to `JSONBodyAsserter`:

```java
public JSONBodyAsserter equalToFile(Path file, Object... substitutions)
```

The expected response is a literal `.json` file committed to the repo (convention: `src/test/json/**`, referenced by
`Path` — no classpath machinery). Any wire change requires editing a checked-in file, so the PR diff is the wire diff.
Existing `equalTo(...)`/`hasValue(...)` remain for quick internal tests; public-API tests should use `equalToFile`.

Rejected: JSON Schema (misses additions unless `additionalProperties:false` everywhere; no value checks),
Pact/OpenAPI validation (cross-repo infrastructure this project doesn't need), lenient compare modes (miss additions).

## Expected-file format

Plain JSON plus two kinds of tokens, each occupying part or all of a JSON **string** node:

1. **Substitutions** — values the test knows: `"${applicationId}"`. Supplied as name/value varargs pairs:
   `json.equalToFile(path, "applicationId", id)`. A string node that is exactly one token is replaced by the value
   (converted via `toJSONObject`, so numbers/booleans/UUIDs keep their JSON type). A string node containing embedded tokens
   (`"http://localhost:${port}/"`) interpolates the value's text form. Substitution happens on the parsed tree, never
   by text templating — no JSON-injection issues, no FreeMarker dependency.
2. **Placeholders** — values the test cannot know:

   | Placeholder        | Matches                                                 |
   |--------------------|---------------------------------------------------------|
   | `${anyBoolean}`    | any boolean                                             |
   | `${anyInstant}`    | string accepted by `Instant.parse`                      |
   | `${anyNumber}`     | any JSON number                                         |
   | `${anyString}`     | any string                                              |
   | `${anyUUID}`       | string accepted by `UUID.fromString`                    |
   | `${regex:PATTERN}` | scalar whose text form (`asText`) fully matches PATTERN |

   Placeholders assert type/shape at that position; the key must still be present (key-set comparison is unaffected).
   Placeholders must be the entire string node.

   A colon after the placeholder name starts an argument. `regex` takes its pattern bare. `anyNumber`, `anyString`,
   and `anyInstant` take an inclusive range: `${anyNumber:[-10:100]}` bounds the value, `${anyString:[0:100]}` bounds
   the length, and `${anyInstant:[2026-01-01T00:00:00Z/2026-12-31T00:00:00Z]}` bounds the instant using ISO 8601
   interval notation (slash separator, because instants contain colons). Either bound may be empty (open-ended), but
   not both, and the minimum must not exceed the maximum.

Rules:

- An unknown `${...}` token fails the assertion (catches typos).
- A supplied substitution that the file never references fails the assertion (keeps files honest; prime-mvc's rule).
- Literal `${` in expected text is escaped as `$${`.

## Comparison semantics

- Strict both ways: extra fields fail, missing fields fail, types are strict (existing `deepEquals`).
- Object keys unordered (JSON semantics). **Arrays always positional** — order is part of the wire format;
  `equalToFile` ignores the `unorderedArrays` setting. Rare unordered cases can load the file themselves and use
  `equalTo(String)`.

## Bootstrap and update

- **Missing file**: write the actual response to it, pretty-printed (2-space indent, wire key order), then fail with
  a "generated — review and commit" message.
- **Mismatch with `-Dlatte.web.json.update=true`**: rewrite the file from the actual response and pass. Tokens are
  preserved: a substitution whose value equals the actual value is written back as its token; a placeholder that
  still matches the actual value is kept; everything else is written literally (and therefore shows in `git diff`,
  which is the review mechanism).
- **CI (`CI` env var set)**: never write; a missing file or the update flag fails the test.

## Failure output

TestNG wire format via existing `Assertions.failNotEqual`, but with both trees pretty-printed (2-space indent) so the
IDE diff shows only semantic change.

## Implementation notes

- Split across two classes in `org.lattejava.web.test`: package-private `ExpectedJSONFile` loads, parses, and
  substitutes the file (tokens, placeholders, unused/unknown detection); `JSONBodyAsserter.equalToFile` does the
  placeholder-aware comparison (positional arrays) and the bootstrap/update logic.
- Rendering (pretty and compact) goes through the vendored `JSONWriter`; no new dependencies.
