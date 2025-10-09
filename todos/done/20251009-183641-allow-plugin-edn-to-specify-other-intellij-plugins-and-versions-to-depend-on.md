# Allow plugin.edn to specify other IntelliJ plugins and versions to depend on
**Status:** Done
**Agent PID:** 49833

## Original Todo
- Allow plugin.edn to specify other IntelliJ plugins and versions to depend on
  - Add a :plugins entry in the plugin.edn, containing a vector of plugin IDs as described in docs/plugins.md
  - Ensure-sdk should download from plugin repository, install into .sdks
  - Update dependencies
  - Copy plugins to sandbox when deploying

## Description
We're adding support for specifying external IntelliJ plugin dependencies in `plugin.edn`. This will allow projects to declare dependencies on marketplace plugins (like Kotlin, Database Tools, etc.) which will be automatically downloaded and configured.

The feature will:
- Accept `:plugins` configuration in `plugin.edn` as a vector of maps: `[{:id "plugin-id" :version "1.2.3" :channel "eap"}]` (version and channel optional)
- Download plugins as Maven artifacts from `https://plugins.jetbrains.com/maven`
- Extract plugins to `~/.sdks/plugins/{pluginId}/{version}/`
- Generate `deps.edn` files for plugin jars
- Update module `deps.edn` files with namespaced dependencies (e.g., `marketplace-plugin/kotlin`)
- Support the same dual representation (nodes + edn) pattern for deps.edn manipulation

*Read [analysis.md](./analysis.md) in full for detailed codebase research and context*

## Implementation Plan
- [x] Add plugin parsing and URL construction functions in src/plugin_dev_tools/ensure.clj
  - `plugin-maven-url` - Construct Maven URL: `https://plugins.jetbrains.com/maven/{channel}.com.jetbrains.plugins/{pluginId}/{version}/{pluginId}-{version}.zip`
  - `plugin-dir` - Path helper for `~/.sdks/plugins/{pluginId}/{version}/`
  - `plugin-zipfile` - Path helper for downloaded zip
  - Note: parse-plugin-spec was not needed as destructuring directly works

- [x] Add plugin download and extraction in src/plugin_dev_tools/ensure.clj
  - `download-plugin` - Download plugin zip from Maven repository (similar to download-sdk pattern)
  - `process-plugin` - Extract zip and generate deps.edn with plugin jars (similar to process-sdk for bundled plugins)

- [x] Update module deps.edn handling in src/plugin_dev_tools/ensure.clj (update-deps-edn function)
  - Add support for `marketplace-plugin/*` namespace alongside existing `intellij/*` and `plugin/*`
  - Update `:local/root` paths to point to `~/.sdks/plugins/{pluginId}/{version}/`

- [x] Integrate plugin processing into ensure-sdk workflow in src/plugin_dev_tools/core.clj
  - Read `:plugins` from plugin.edn config
  - Call ensure/download-plugin for each plugin if not already downloaded
  - Pass plugin info to ensure/update-deps-edn

- [x] Add unit tests in test/plugin_dev_tools/ensure_test.clj
  - Test plugin-maven-url construction with/without channel
  - Test plugin-dir path generation
  - Test plugin-zipfile path generation
  - Test update-deps-edn with marketplace-plugin/* dependencies

- [x] Add integration test in test/plugin_dev_tools/core_test.clj
  - Verify deps.edn updates with marketplace-plugin/* keys for multiple plugins
  - Test both SDK and marketplace plugin path updates together

- [x] Update project documentation in docs/plugins.md
  - Document the new :plugins configuration format
  - Provide examples of plugin.edn with plugins
  - Add troubleshooting section

## Notes
[Implementation notes]
