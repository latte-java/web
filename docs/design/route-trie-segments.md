# RouteTrie segments refactor

Branch: `features/mvp-security`.

## Goal

Replace `String.split("/", -1)` + `System.arraycopy` in `RouteTrie.match` with a single `indexOf('/')` pass that builds an `ArrayList<String>` of segments. Surface that list on every `Outcome` so the subsequent middleware-resolution step in `Web.handleRequest` can reuse it instead of re-parsing the request path.

## Changes

### RouteTrie.match

- Parse the request path inline using `indexOf('/')`. Skip a single leading `/`. Preserve trailing-empty semantics.
- Build an `ArrayList<String>` with initial capacity `11` (covers typical REST depths without resizing; caps at `MAX_SEGMENTS=256` via the existing guard, now enforced during the parse).
- Wrap the final list with `Collections.unmodifiableList` once, pass to the recursive walker and to every outcome.

### RouteTrie.matchRecursive

- Take `List<String> segments` instead of `String[]`.
- Same recursive shape; `segments.get(idx)` is O(1) on `ArrayList`.
- Return sites pass the segments list through to the outcome record.

### RouteTrie.Outcome

All three outcomes gain a `List<String> segments` field:

```java
record Found(Handler handler, List<Middleware> middlewares,
             Map<String, String> pathParams, List<String> segments) implements Outcome { }
record MethodNotAllowed(Set<String> allowedMethods, List<String> segments) implements Outcome { }
record NotFound(List<String> segments) implements Outcome { }
```

`NotFound.INSTANCE` singleton is removed; a fresh record is returned per match.

### Web middleware resolution

Change the storage from `Map<String, List<Middleware>>` to `Map<List<String>, List<Middleware>>`. Keys are segment lists (root = `List.of()`).

At `install()` time, parse the current `Web.pathPrefix` String into segments once and use as the map key.

At request time, `collectPrefixMiddlewares(List<String> requestSegments)`:
1. Iterate map entries; include entries where the key list is an element-wise prefix of the request segments.
2. Sort matching entries by key size ascending.
3. Concatenate values.

The previous `prefixMatches(String, String)` string helper is removed.

### Behavior preservation

The refactor is purely internal. The existing `RoutingTest`, `MiddlewareTest`, and `StaticResourcesTest` suites serve as the regression net — no user-visible semantics change.

## Non-goals

- No public API change to `Web`, `Handler`, or `Middleware`.
- `MAX_SEGMENTS=256` unchanged; enforced during the parse rather than after.
- No performance tuning beyond the structural change (no micro-optimization of the walker itself).
