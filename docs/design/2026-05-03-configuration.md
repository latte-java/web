# Configuration

Design a configuration mechanism for web applications that use the `web` framework.

# Problem statement

Applications need a way to store configuration, both insecure and secure values, in a way that allows the application to load and override the values. Applications that are deploy in containers need a mechanism to provide configuration outside of the container in a secure manner as well.

# Strategy

Configuration values are name value pairs. These values loaded using a multi-step process that stops once it finds the first definition of a value. Given a configuration named `my-app.some-setting`, the steps are as follows:

1. See if there is an environment variable with the name `my-app.some-setting`
2. See if there is an environment variable with the name `MY_APP_SOME_SETTING` (replacing all non-alpha characters with underscores so that it matches *nix standard naming for environment variables)
3. See if there is a Java system property with the name `my-app.some-setting` (i.e. `-Dmy-app.some-setting=value`)
4. See if there is a configuration value in a properties file provided to the `Configuration` object in the constructor (i.e. `my-app.some-setting=value` or `my-app.some-setting: value`)

If the lookup fails, an empty optional is returned. The syntax looks like this:

```java
var config = new Configuration(Path.of("config.properties")); // No required settings
var config = new Configuration(
    Path.of("config.properties"),
    List.of("my-app.some-required-setting", "my-app.some-other-required-setting") // Required settings
);

var value = config.get("my-app.some-setting"); // Returns String or null if not found
var value = config.getBoolean("my-app.some-setting"); // Returns Boolean or null if not found
var value = config.getInteger("my-app.some-setting"); // Returns Integer or null if not found
var value = config.getBigDecimal("my-app.some-setting"); // Returns BigDecimal or null if not found
var value = config.getBigInteger("my-app.some-setting"); // Returns BigInteger or null if not found

var value = config.get("my-app.some-setting", "default"); // Returns String or <default> if not found
var value = config.getBoolean("my-app.some-setting", false); // Returns boolean or <default> if not found
var value = config.getInteger("my-app.some-setting", 42); // Returns int or <default> if not found
var value = config.getBigDecimal("my-app.some-setting", BigDecimal.valueOf(42)); // Returns BigDecimal or <default> if not found
var value = config.getBigInteger("my-app.some-setting", BigInteger.valueOf(42)); // Returns BigInteger or <default> if not found
```
