# IntelliJ Plugin Dependencies

This document explains how to configure IntelliJ marketplace plugin dependencies in your `plugin.edn` file.

## Configuration Format

Add a `:plugins` key to your `plugin.edn` file with a vector of plugin specification maps. Each map should contain:

- `:id` (required) - The plugin ID from the JetBrains marketplace
- `:version` (required) - The specific version to download
- `:channel` (optional) - The distribution channel (e.g., "eap" for early access releases)

### Example plugin.edn

```clojure
{:idea-version "2025.3-eap"
 :kotlin-version "2.1.0"
 :serialization-version "1.7.3"
 :coroutines-version "1.9.0"
 :ksp-version "2.1.0-1.0.29"
 :modules ["deps.edn" "module1/deps.edn" "module2/deps.edn"]
 :plugins [{:id "org.intellij.plugins.markdown"
            :version "253.5981.147"}
           {:id "Pythonid"
            :version "253.5981.151"
            :channel "eap"}]}
```

## How It Works

When you run `clj -Ttools ensure-sdk`, the tool will:

1. **Download plugins** from the JetBrains Maven repository at `https://plugins.jetbrains.com/maven`
2. **Extract plugins** to `~/.sdks/plugins/{plugin-id}/{version}/`
3. **Generate deps.edn** files for each plugin with all JAR dependencies
4. **Update module deps.edn** files to point to the downloaded plugins

## Using Plugins in Your deps.edn

Reference marketplace plugins in your module `deps.edn` files using the `marketplace-plugin` namespace:

```clojure
{:aliases
 {:sdk
  {:extra-deps
   {intellij/sdk {:local/root "/Users/you/.sdks/253.5981.147"}
    marketplace-plugin/org.intellij.plugins.markdown {:local/root "/Users/you/.sdks/plugins/org.intellij.plugins.markdown/253.5981.147"}
    marketplace-plugin/Pythonid {:local/root "/Users/you/.sdks/plugins/Pythonid/253.5981.151"}}}}}
```

The `ensure-sdk` command will automatically update these paths when you change versions in `plugin.edn`.

## Finding Plugin IDs and Versions

Plugin IDs can be found on the JetBrains marketplace or in the IntelliJ settings:

1. Go to https://plugins.jetbrains.com/
2. Find your plugin (e.g., "Markdown")
3. The plugin ID is in the URL: `https://plugins.jetbrains.com/plugin/7793-markdown` → ID is typically shown on the plugin page
4. For the actual Maven artifact ID, check the plugin's `plugin.xml` or use the plugin manager in IntelliJ

**Note:** Some plugins use different IDs for the marketplace vs. the Maven repository. Common examples:
- Markdown plugin: `org.intellij.plugins.markdown`
- Python plugin: `Pythonid`
- Kotlin plugin: Usually bundled with the SDK

## Maven Repository Pattern

Plugins are downloaded from Maven using this URL pattern:

```
https://plugins.jetbrains.com/maven/{channel}/com/jetbrains/plugins/{pluginId}/{version}/{pluginId}-{version}.zip
```

- Without channel: `https://plugins.jetbrains.com/maven/com/jetbrains/plugins/{pluginId}/{version}/...`
- With channel: `https://plugins.jetbrains.com/maven/{channel}/com/jetbrains/plugins/{pluginId}/{version}/...`

## Troubleshooting

**Plugin version not found:**
- Ensure the version exists in the JetBrains Maven repository
- Check that the plugin ID is correct (marketplace name vs. Maven artifact ID may differ)
- Try accessing the Maven URL directly in a browser to verify availability

**Plugin not loading:**
- Verify the plugin is compatible with your IntelliJ SDK version
- Check that all plugin dependencies are also specified in `:plugins`
- Review the plugin's deps.edn to ensure JARs were extracted correctly
