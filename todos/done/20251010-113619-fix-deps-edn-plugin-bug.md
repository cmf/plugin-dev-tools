# Fix deps.edn plugin bug with UnixPath
**Status:** Done
**Agent PID:** 82296

## Original Todo
- Fix bug when creating deps.edn files for downloaded plugins:
  - ~/d/scribe (master)> clojure -T:plugin-dev ensure-sdk
    Resolving version 2025.3-eap from snapshots repository
    Resolved 2025.3-eap to 253.25908.13-EAP-SNAPSHOT
    Downloading plugin com.cursiveclojure.cursive 2025.2.1-eap3-253 from https://plugins.jetbrains.com/maven/eap/com/jetbrains/plugins/com.cursiveclojure.cursive/2025.2.1-eap3-253/com.cursiveclojure.cursive-2025.2.1-eap3-253.zip
    Unzipping plugin com.cursiveclojure.cursive
    Error: No implementation of method: :as-file of protocol: #'clojure.java.io/Coercions found for class: sun.nio.fs.UnixPath
  - The plugin is unzipped correctly, so I believe the problem is in the deps.edn creation

## Description
Fix a type coercion bug in the `process-plugin` function (src/plugin_dev_tools/ensure.clj) where `java.nio.file.Path` objects from `babashka/fs` functions are incorrectly passed to `clojure.java.io/file`, causing an "UnixPath cannot be coerced to File" error when creating deps.edn files for downloaded IntelliJ plugins. The bug occurs because `fs/list-dir` returns Path objects, but these are passed to `io/file` which expects File or String arguments.

*Read [analysis.md](./analysis.md) in full for detailed codebase research and context*

## Implementation Plan
- [x] Fix line 186 in src/plugin_dev_tools/ensure.clj: Change `(io/file % "lib")` to `(fs/file % "lib")` to use babashka/fs consistently with Path objects
- [x] Fix line 197 in src/plugin_dev_tools/ensure.clj: Change `(io/file actual-plugin-dir "deps.edn")` to `(fs/file actual-plugin-dir "deps.edn")` to handle Path objects correctly
- [x] Automated test: Add test in test/plugin_dev_tools/ensure_test.clj that verifies process-plugin correctly handles plugins with nested directory structures
- [x] User test: Run `clj -Ttools ensure-sdk` with a plugin configuration to verify the bug is fixed and deps.edn is created successfully

## Notes
The fix changes two instances of `io/file` to `fs/file` to maintain consistency when working with Path objects returned by babashka/fs functions.
