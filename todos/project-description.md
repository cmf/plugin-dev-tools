# Project: plugin-dev-tools

Clojure CLI tools for managing IntelliJ IDEA plugin development with Kotlin, eliminating the need for Gradle.

## Features

- **Automated SDK Management**: Downloads IntelliJ IDEA SDKs (release or snapshot) from JetBrains repositories, extracts them to `~/.sdks/`, and generates deps.edn files for SDK core libraries and plugins
- **Kotlin Version Management**: Updates Kotlin and related dependency versions (kotlinx-serialization, kotlinx-coroutines, KSP) across multiple plugin modules
- **Structure-Preserving Updates**: Uses rewrite-edn to update deps.edn files while preserving formatting and comments
- **Multi-Module Support**: Processes multiple plugin modules in a single operation based on plugin.edn configuration

## Tech Stack

- **Language**: Clojure
- **Libraries**:
  - babashka/fs 0.5.26 (file system operations)
  - babashka/curl (HTTP downloads)
  - borkdude/rewrite-edn (structure-preserving EDN rewriting)
  - org.clojure/test.check 1.1.1 (property-based testing)
- **Build Tool**: Clojure CLI (tools.deps) with deps.edn
- **Dependency Management**: tools.deps with Maven and Git dependencies
- **Testing**: clojure.test with cognitect.test-runner
- **Linting**: clj-kondo

## Structure

```
src/plugin_dev_tools/
  ├── core.clj              # Entry points for CLI tools
  ├── ensure.clj            # SDK download and management logic
  └── update_kotlin.clj     # Kotlin version update logic

test/plugin_dev_tools/      # Comprehensive test suite
  ├── core_test.clj
  ├── ensure_test.clj
  └── update_kotlin_test.clj

deps.edn                    # Dependencies and test alias configuration
plugin.edn                  # Expected in consuming projects (not in this repo)
```

## Architecture

**Entry Points** (plugin-dev-tools.core):
- `ensure-sdk`: Downloads and configures IntelliJ IDEA SDK
- `ensure-kotlin`: Updates Kotlin dependency versions

**SDK Management** (plugin-dev-tools.ensure):
- Downloads from JetBrains repositories (releases/snapshots)
- Extracts to `~/.sdks/{version}/`
- Generates deps.edn for SDK and plugins
- Updates local/root paths in module deps.edn files

**Kotlin Updates** (plugin-dev-tools.update-kotlin):
- Identifies dependencies by namespace patterns (org.jetbrains.kotlin/*, org.jetbrains.kotlinx/*)
- Updates :mvn/version values while preserving file structure
- Handles dependency variants with $ suffixes

**Configuration**: Consuming projects provide plugin.edn with IDEA version, Kotlin versions, and module paths.

## Commands

- **Build**: N/A (Clojure library, no compilation needed)
- **Test**: `clj -M:test` or `clj -X:test`
- **Lint**: `clj-kondo --lint src test`
- **Usage** (from consuming projects):
  - `clj -Ttools ensure-sdk` - Download and configure SDK
  - `clj -Ttools ensure-kotlin` - Update Kotlin versions

## Testing

**Framework**: clojure.test with cognitect.test-runner

**Creating New Tests**:
1. Create test file in `test/plugin_dev_tools/<namespace>_test.clj`
2. Use standard clojure.test structure with `deftest` and `is` assertions
3. Use `/tmp/` for temporary file operations
4. Test both happy paths and error handling
5. Verify formatting preservation for EDN rewriting tests

**Running Tests**:
- All tests: `clj -M:test`
- Specific namespace: `clj -M:test -n plugin-dev-tools.your-module-test`
