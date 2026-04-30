/*
 * Copyright (c) 2025-2026, Latte Java, All Rights Reserved
 *
 * Licensed under the MIT License. See LICENSE for details.
 */
package org.lattejava.web.tests.test;

import module java.base;
import module org.lattejava.web;
import module org.testng;

import static org.testng.Assert.*;

@Test
public class JSONBodyAsserterTest {
  @Test
  public void equalToObject_failsOnDifferentValue() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);

    expectAssertionError(() -> asserter.equalTo(Map.of("name", "Bob")),
        "JSON body does not match");
  }

  @Test
  public void equalToObject_listMatchesArrayInAnyOrder() {
    var asserter = asserterFor("""
        [1, 2, 3]
        """);

    asserter.equalTo(List.of(1, 2, 3));
    asserter.equalTo(List.of(3, 2, 1));

    // Different content still fails (size matches but elements differ).
    expectAssertionError(() -> asserter.equalTo(List.of(1, 2, 4)),
        "JSON body does not match");
  }

  @Test
  public void equalToObject_listOfPOJOsOrderInsensitive() {
    var asserter = asserterFor("""
        [
          { "city": "Boulder", "zip": "80301" },
          { "city": "Denver",  "zip": "80202" }
        ]
        """);

    // POJOs supplied in the opposite order from the JSON. Since arrays compare as multisets, this still matches.
    asserter.equalTo(List.of(
        new POJOAddress("Denver", "80202"),
        new POJOAddress("Boulder", "80301")));
  }

  @Test
  public void equalToObject_mapMatchesObject() {
    var asserter = asserterFor("""
        { "name": "Jane", "age": 33 }
        """);

    // Map.of order is unspecified, but JSON object equality ignores key order so this still passes.
    asserter.equalTo(Map.of("name", "Jane", "age", 33));
  }

  @Test
  public void equalToObject_nestedMapAndListReorderedKeys() {
    var asserter = asserterFor("""
        {
          "user": {
            "id": 7,
            "tags": ["admin", "user"],
            "profile": {
              "name": "Jane",
              "email": "jane@example.com"
            }
          }
        }
        """);

    // Build the expected tree with deliberately-different insertion order on the inner objects.
    var profile = new LinkedHashMap<String, Object>();
    profile.put("email", "jane@example.com");
    profile.put("name", "Jane");
    var user = new LinkedHashMap<String, Object>();
    user.put("profile", profile);
    user.put("tags", List.of("admin", "user"));
    user.put("id", 7);
    asserter.equalTo(Map.of("user", user));
  }

  @Test
  public void equalToObject_nestedPOJO() {
    var asserter = asserterFor("""
        {
          "address": { "city": "Boulder", "zip": "80301" },
          "name": "Jane",
          "age": 33
        }
        """);

    // Order of POJO fields is irrelevant — JSON object equality ignores key order.
    asserter.equalTo(new POJOUser("Jane", 33, new POJOAddress("Boulder", "80301")));
  }

  @Test
  public void equalToObject_nestedRecord() {
    var asserter = asserterFor("""
        {
          "name": "Jane",
          "age": 33,
          "address": { "zip": "80301", "city": "Boulder" }
        }
        """);

    asserter.equalTo(new TestUser("Jane", 33, new TestAddress("Boulder", "80301")));
  }

  @Test
  public void equalToObject_nullForJSONNull() {
    var asserter = asserterFor("null");
    // Cast to Object so this routes to equalTo(Object), not the String overload (which would treat null as a JSON
    // document literal).
    asserter.equalTo((Object) null);
  }

  @Test
  public void equalToObject_pojoFailsForDifferentValues() {
    var asserter = asserterFor("""
        { "city": "Boulder", "zip": "80301" }
        """);

    expectAssertionError(() -> asserter.equalTo(new POJOAddress("Denver", "80202")),
        "JSON body does not match");
  }

  @Test
  public void equalToObject_pojoMatchesJSONIgnoringKeyOrder() {
    var asserter = asserterFor("""
        { "zip": "80301", "city": "Boulder" }
        """);

    // POJO fields are declared (city, zip); JSON has them in (zip, city) order. Should still match.
    asserter.equalTo(new POJOAddress("Boulder", "80301"));
  }

  @Test
  public void equalToObject_primitiveMatchesScalar() {
    asserterFor("42").equalTo(42);
    // Cast steers the call to equalTo(Object); without it the String overload tries to parse "hello" as raw JSON.
    asserterFor("\"hello\"").equalTo((Object) "hello");
    asserterFor("true").equalTo(true);
  }

  @Test
  public void equalToObject_recordFailsForDifferentValues() {
    var asserter = asserterFor("""
        { "city": "Boulder", "zip": "80301" }
        """);

    expectAssertionError(() -> asserter.equalTo(new TestAddress("Denver", "80202")),
        "JSON body does not match");
  }

  @Test
  public void equalToObject_recordIgnoresJSONKeyOrder() {
    // Record components are (city, zip); JSON puts them in the opposite order.
    var asserter = asserterFor("""
        { "zip": "80301", "city": "Boulder" }
        """);

    asserter.equalTo(new TestAddress("Boulder", "80301"));
  }

  @Test
  public void equalToObject_recordMatchesJSON() {
    var asserter = asserterFor("""
        { "city": "Boulder", "zip": "80301" }
        """);

    asserter.equalTo(new TestAddress("Boulder", "80301"));
  }

  @Test
  public void equalToObject_recordWithListOfRecordsOrderInsensitive() {
    var asserter = asserterFor("""
        {
          "id": "ord-1",
          "items": [
            { "sku": "A", "quantity": 1 },
            { "sku": "B", "quantity": 2 }
          ]
        }
        """);

    // Items supplied in reverse order; multiset semantics make this pass.
    asserter.equalTo(new TestOrder("ord-1", List.of(new TestItem("B", 2), new TestItem("A", 1))));
  }

  @Test
  public void equalToObject_recordWithReorderedNestedFields() {
    var asserter = asserterFor("""
        {
          "address": { "zip": "80301", "city": "Boulder" },
          "age": 33,
          "name": "Jane"
        }
        """);

    asserter.equalTo(new TestUser("Jane", 33, new TestAddress("Boulder", "80301")));
  }

  @Test
  public void equalTo_arrayDuplicatesUseMultisetSemantics() {
    var asserter = asserterFor("""
        { "values": [1, 1, 2] }
        """);

    // Same multiset (one 2 and two 1s, regardless of order).
    asserter.equalTo("""
        { "values": [1, 2, 1] }
        """);
    asserter.equalTo("""
        { "values": [2, 1, 1] }
        """);

    // Different multiset: same size, but counts differ.
    expectAssertionError(() -> asserter.equalTo("""
        { "values": [1, 2, 2] }
        """), "JSON body does not match");
  }

  @Test
  public void equalTo_arrayOrderIgnored() {
    var asserter = asserterFor("""
        { "tags": ["a", "b", "c"] }
        """);

    // Original order matches.
    asserter.equalTo("""
        { "tags": ["a", "b", "c"] }
        """);

    // Reversed order also matches because arrays are compared as multisets.
    asserter.equalTo("""
        { "tags": ["c", "b", "a"] }
        """);

    // A truly different multiset still fails.
    expectAssertionError(() -> asserter.equalTo("""
        { "tags": ["a", "b", "d"] }
        """), "JSON body does not match");
  }

  @Test
  public void equalTo_arraySizeMismatchFails() {
    var asserter = asserterFor("""
        { "values": [1, 2, 3] }
        """);

    expectAssertionError(() -> asserter.equalTo("""
        { "values": [1, 2] }
        """), "JSON body does not match");
  }

  @Test
  public void equalTo_deeplyNestedReorderedKeys() {
    var asserter = asserterFor("""
        {
          "user": {
            "id": 42,
            "profile": {
              "email": "user@example.com",
              "name": "Jane",
              "addresses": [
                { "city": "Boulder", "zip": "80301" },
                { "city": "Denver",  "zip": "80202" }
              ]
            }
          }
        }
        """);

    asserter.equalTo("""
        {
          "user": {
            "profile": {
              "addresses": [
                { "zip": "80301", "city": "Boulder" },
                { "zip": "80202", "city": "Denver" }
              ],
              "name": "Jane",
              "email": "user@example.com"
            },
            "id": 42
          }
        }
        """);
  }

  @Test
  public void equalTo_differentValueFails() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);

    expectAssertionError(() -> asserter.equalTo("""
        { "name": "John" }
        """), "JSON body does not match - expected [");
  }

  @Test
  public void equalTo_extraKeyFails() {
    var asserter = asserterFor("""
        { "a": 1, "b": 2 }
        """);

    expectAssertionError(() -> asserter.equalTo("""
        { "a": 1 }
        """), "JSON body does not match");
  }

  @Test
  public void equalTo_ignoresKeyOrder() {
    var asserter = asserterFor("""
        { "a": 1, "b": 2, "c": 3 }
        """);

    asserter.equalTo("""
        { "c": 3, "a": 1, "b": 2 }
        """);
  }

  @Test
  public void equalTo_ignoresWhitespace() {
    var asserter = asserterFor("""
        {"a":1,"b":[1,2,3]}
        """);

    asserter.equalTo("""
        {
          "a" : 1,
          "b" : [ 1, 2, 3 ]
        }
        """);
  }

  @Test
  public void equalTo_invalidActualJSONFails() {
    var asserter = new JSONBodyAsserter();
    asserter.body("{ this is not json".getBytes(StandardCharsets.UTF_8));

    expectAssertionError(() -> asserter.equalTo("{}"), "Could not parse [body] as JSON");
  }

  @Test
  public void equalTo_invalidExpectedJSONFails() {
    var asserter = asserterFor("{}");

    expectAssertionError(() -> asserter.equalTo("not json"), "Could not parse [expected] as JSON");
  }

  @Test
  public void equalTo_nestedArraysReorderedAtEveryLevel() {
    var asserter = asserterFor("""
        {
          "groups": [
            { "name": "admins", "members": [1, 2, 3] },
            { "name": "users",  "members": [4, 5, 6] }
          ]
        }
        """);

    // Outer array reordered, member arrays inside each object also reordered.
    asserter.equalTo("""
        {
          "groups": [
            { "name": "users",  "members": [6, 5, 4] },
            { "name": "admins", "members": [3, 1, 2] }
          ]
        }
        """);
  }

  @Test
  public void equalTo_nullBodyFails() {
    var asserter = new JSONBodyAsserter();
    asserter.body(null);

    expectAssertionError(() -> asserter.equalTo("{}"), "JSON body for [body] is null");
  }

  @Test
  public void equalTo_numericTypesAreCompared() {
    var asserter = asserterFor("""
        { "n": 1 }
        """);

    asserter.equalTo("""
        { "n": 1 }
        """);

    // Jackson's JsonNode.equals distinguishes integers from floats by default.
    expectAssertionError(() -> asserter.equalTo("""
        { "n": 1.0 }
        """), "JSON body does not match");
  }

  @Test
  public void hasElement_arrayIndex() {
    var asserter = asserterFor("""
        { "items": ["red", "green", "blue"] }
        """);

    asserter.hasElement("/items/0")
            .hasElement("/items/2");

    expectAssertionError(() -> asserter.hasElement("/items/3"),
        "JSON body is missing element at pointer [/items/3]");
  }

  @Test
  public void hasElement_nestedPointer() {
    var asserter = asserterFor("""
        {
          "user": {
            "addresses": [
              { "city": "Boulder", "zip": "80301" }
            ]
          }
        }
        """);

    asserter.hasElement("/user")
            .hasElement("/user/addresses")
            .hasElement("/user/addresses/0")
            .hasElement("/user/addresses/0/city")
            .hasElement("/user/addresses/0/zip");
  }

  @Test
  public void hasElement_nullValueIsPresent() {
    var asserter = asserterFor("""
        { "deleted": null }
        """);

    asserter.hasElement("/deleted");
  }

  @Test
  public void hasElement_pointerEscapes() {
    // ~ is encoded as ~0 and / is encoded as ~1 per RFC 6901.
    var asserter = asserterFor("""
        {
          "weird~name": 1,
          "path/with/slash": 2
        }
        """);

    asserter.hasElement("/weird~0name")
            .hasElement("/path~1with~1slash");
  }

  @Test
  public void hasElement_simplePointer() {
    var asserter = asserterFor("""
        { "name": "Jane", "age": 33 }
        """);

    asserter.hasElement("/name")
            .hasElement("/age");
  }

  @Test
  public void hasElement_throwsForMissingPath() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);

    expectAssertionError(() -> asserter.hasElement("/missing"),
        "JSON body is missing element at pointer [/missing]");
  }

  @Test
  public void hasNoElement_succeedsForMissingPath() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);

    asserter.hasNoElement("/missing")
            .hasNoElement("/name/typo");
  }

  @Test
  public void hasNoElement_throwsForExistingPath() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);

    expectAssertionError(() -> asserter.hasNoElement("/name"),
        "JSON body unexpectedly contains element at pointer [/name]");
  }

  @Test
  public void hasValueObject_booleanMatches() {
    var asserter = asserterFor("""
        { "active": true }
        """);

    asserter.hasValue("/active", true);

    expectAssertionError(() -> asserter.hasValue("/active", false),
        "JSON value at pointer [/active] does not match");
  }

  @Test
  public void hasValueObject_chainsAcrossPointers() {
    var asserter = asserterFor("""
        {
          "user": {
            "id": 7,
            "addresses": [
              { "city": "Boulder", "zip": "80301" },
              { "city": "Denver",  "zip": "80202" }
            ]
          }
        }
        """);

    asserter.hasValue("/user/id", 7)
            .hasValue("/user/addresses/0/city", "Boulder")
            .hasValue("/user/addresses/1/zip", "80202");
  }

  @Test
  public void hasValueObject_doubleMatches() {
    var asserter = asserterFor("""
        { "price": 9.99 }
        """);

    asserter.hasValue("/price", 9.99);
  }

  @Test
  public void hasValueObject_intMatches() {
    var asserter = asserterFor("""
        { "age": 33 }
        """);

    asserter.hasValue("/age", 33);

    expectAssertionError(() -> asserter.hasValue("/age", 34),
        "JSON value at pointer [/age] does not match");
  }

  @Test
  public void hasValueObject_nullMatchesExplicitNull() {
    var asserter = asserterFor("""
        { "deleted": null }
        """);

    // Cast to Object so the call routes to the Object overload (otherwise null picks the more-specific String overload).
    asserter.hasValue("/deleted", (Object) null);
  }

  @Test
  public void hasValueObject_nullMatchesMissingPointer() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);

    asserter.hasValue("/missing", (Object) null);

    expectAssertionError(() -> asserter.hasValue("/missing", (Object) "Jane"),
        "JSON value at pointer [/missing] does not match");
  }

  @Test
  public void hasValueObject_strictTypedComparison() {
    // The Object overload uses JsonNode.equals so number-vs-string mismatches are surfaced.
    var asserter = asserterFor("""
        { "age": 33 }
        """);

    expectAssertionError(() -> asserter.hasValue("/age", (Object) "33"),
        "JSON value at pointer [/age] does not match");
  }

  @Test
  public void hasValueObject_stringRoutesToStringOverload() {
    // String literals stay on the existing String overload (more specific). This documents the dispatch.
    var asserter = asserterFor("""
        { "age": 33 }
        """);

    asserter.hasValue("/age", "33");
  }

  @Test
  public void hasValue_booleanAsText() {
    var asserter = asserterFor("""
        { "active": true }
        """);

    asserter.hasValue("/active", "true");

    expectAssertionError(() -> asserter.hasValue("/active", "false"),
        "JSON value at pointer [/active] does not match");
  }

  @Test
  public void hasValue_chainsAcrossPointers() {
    var asserter = asserterFor("""
        {
          "user": {
            "id": 7,
            "addresses": [
              { "city": "Boulder", "zip": "80301" },
              { "city": "Denver",  "zip": "80202" }
            ]
          }
        }
        """);

    asserter.hasValue("/user/id", "7")
            .hasValue("/user/addresses/0/city", "Boulder")
            .hasValue("/user/addresses/1/zip", "80202");
  }

  @Test
  public void hasValue_missingPointerActsAsNull() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);

    asserter.hasValue("/missing", null);

    expectAssertionError(() -> asserter.hasValue("/missing", "Jane"),
        "JSON value at pointer [/missing] does not match");
  }

  @Test
  public void hasValue_nullJSONValueIsNull() {
    var asserter = asserterFor("""
        { "deleted": null }
        """);

    asserter.hasValue("/deleted", null);
  }

  @Test
  public void hasValue_numberAsText() {
    var asserter = asserterFor("""
        { "age": 33 }
        """);

    asserter.hasValue("/age", "33");
  }

  @Test
  public void hasValue_stringExactMatch() {
    var asserter = asserterFor("""
        { "name": "Jane" }
        """);

    asserter.hasValue("/name", "Jane");

    expectAssertionError(() -> asserter.hasValue("/name", "Bob"),
        "JSON value at pointer [/name] does not match");
  }

  @Test
  public void rebody_clearsCachedTree() {
    var asserter = asserterFor("""
        { "first": 1 }
        """);
    asserter.hasElement("/first");

    // Re-binding to a new payload should invalidate the previously parsed tree.
    asserter.body("""
        { "second": 2 }
        """.getBytes(StandardCharsets.UTF_8));
    asserter.hasElement("/second")
            .hasNoElement("/first");
  }

  @Test
  public void unorderedArrays_constructorFalseEnforcesPositional() {
    var asserter = new JSONBodyAsserter(false);
    asserter.body("""
        { "tags": ["a", "b", "c"] }
        """.getBytes(StandardCharsets.UTF_8));

    // Same order matches.
    asserter.equalTo("""
        { "tags": ["a", "b", "c"] }
        """);

    // Reversed array no longer matches because positional comparison is in effect.
    expectAssertionError(() -> asserter.equalTo("""
        { "tags": ["c", "b", "a"] }
        """), "JSON body does not match");
  }

  @Test
  public void unorderedArrays_defaultIsTrue() {
    var asserter = new JSONBodyAsserter();
    assertTrue(asserter.unorderedArrays(), "Default should treat arrays as unordered multisets");
  }

  @Test
  public void unorderedArrays_positionalAppliesToHasValue() {
    var asserter = new JSONBodyAsserter(false);
    asserter.body("""
        { "tags": ["a", "b", "c"] }
        """.getBytes(StandardCharsets.UTF_8));

    asserter.hasValue("/tags", List.of("a", "b", "c"));

    // Positional mode rejects the reordered list at the same pointer.
    expectAssertionError(() -> asserter.hasValue("/tags", List.of("c", "b", "a")),
        "JSON value at pointer [/tags] does not match");
  }

  @Test
  public void unorderedArrays_positionalRecursesIntoNestedArrays() {
    var asserter = new JSONBodyAsserter(false);
    asserter.body("""
        { "matrix": [[1, 2], [3, 4]] }
        """.getBytes(StandardCharsets.UTF_8));

    // Same matrix layout matches.
    asserter.equalTo("""
        { "matrix": [[1, 2], [3, 4]] }
        """);

    // Reordering the inner array breaks positional comparison even though sizes match.
    expectAssertionError(() -> asserter.equalTo("""
        { "matrix": [[2, 1], [3, 4]] }
        """), "JSON body does not match");
  }

  @Test
  public void unorderedArrays_settable() {
    var asserter = asserterFor("""
        { "tags": ["a", "b", "c"] }
        """);

    // Default (unordered): reversed arrays match.
    asserter.equalTo("""
        { "tags": ["c", "b", "a"] }
        """);

    // Switch to positional and the same comparison must now fail.
    asserter.unorderedArrays(false);
    assertFalse(asserter.unorderedArrays(), "Setter should reflect the new value");
    expectAssertionError(() -> asserter.equalTo("""
        { "tags": ["c", "b", "a"] }
        """), "JSON body does not match");

    // Switching back at runtime restores the multiset behavior.
    asserter.unorderedArrays(true);
    asserter.equalTo("""
        { "tags": ["c", "b", "a"] }
        """);
  }

  private JSONBodyAsserter asserterFor(String json) {
    var asserter = new JSONBodyAsserter();
    asserter.body(json.getBytes(StandardCharsets.UTF_8));
    return asserter;
  }

  private void expectAssertionError(Runnable runnable, String expectedMessageFragment) {
    try {
      runnable.run();
      fail("Expected an AssertionError but none was thrown");
    } catch (AssertionError e) {
      String message = e.getMessage();
      assertNotNull(message, "AssertionError message must not be null");
      assertTrue(message.contains(expectedMessageFragment),
          "AssertionError message [" + message + "] does not contain expected fragment ["
              + expectedMessageFragment + "]");
    }
  }

  public static class POJOAddress {
    public String city;
    public String zip;

    public POJOAddress(String city, String zip) {
      this.city = city;
      this.zip = zip;
    }
  }

  public static class POJOUser {
    public POJOAddress address;
    public int age;
    public String name;

    public POJOUser(String name, int age, POJOAddress address) {
      this.name = name;
      this.age = age;
      this.address = address;
    }
  }

  public record TestAddress(String city, String zip) {
  }

  public record TestItem(String sku, int quantity) {
  }

  public record TestOrder(String id, List<TestItem> items) {
  }

  public record TestUser(String name, int age, TestAddress address) {
  }
}
