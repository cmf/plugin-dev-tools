# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Purpose

This is a collection of Clojure tools for IntelliJ plugin development. It's **not** for writing plugins in Clojure itself, but rather for using Clojure tooling to manage Kotlin-based IntelliJ plugins and avoid Gradle.

## Core Functionality

The tools provide two main functions invoked via Clojure CLI:

1. **SDK Management** (`ensure-sdk`): Downloads IntelliJ IDEA SDKs and configures deps.edn files
2. **Kotlin Version Management** (`ensure-kotlin`): Updates Kotlin and related dependency versions across plugin modules

Both functions expect a `plugin.edn` configuration file in the project root (not in this repo, but in the consuming project).

## Architecture

### Configuration File (`plugin.edn`)

Expected structure in consuming projects:
```clojure
{:idea-version "253.20558.43-EAP-SNAPSHOT"
 :kotlin-version "2.1.0"
 :serialization-version "1.7.3"
 :coroutines-version "1.9.0"
 :ksp-version "2.1.0-1.0.29"
 :modules ["path/to/module1/deps.edn" "path/to/module2/deps.edn"]}
```

### Module Organization

- `plugin-dev-tools.core`: Entry points for both main functions
- `plugin-dev-tools.ensure`: SDK download and management logic
  - Downloads from JetBrains releases or snapshots
  - Generates deps.edn files for SDK and plugins
  - Updates local/root paths in consuming project deps.edn
- `plugin-dev-tools.update-kotlin`: Kotlin dependency version updates
  - Rewrites deps.edn files preserving structure
  - Handles kotlin, kotlinx-serialization, kotlinx-coroutines, and KSP dependencies
  - Uses namespaced keys to identify dependencies (e.g., `org.jetbrains.kotlin/kotlin-stdlib`)

### SDK Storage

SDKs are downloaded to `~/.sdks/`:
- `~/.sdks/ideaIU-{version}.zip` (downloaded archive)
- `~/.sdks/{version}/` (extracted SDK)
- `~/.sdks/ideaIC-{version}-sources.jar` (sources)

### deps.edn Rewriting Strategy

Both ensure and update-kotlin modules use `borkdude.rewrite-edn` to preserve formatting and comments when updating deps.edn files. They:
1. Parse deps.edn as both string nodes and EDN data
2. Navigate to specific paths (`:deps`, `:aliases/:sdk/:extra-deps`, etc.)
3. Update version strings or paths in-place
4. Write back preserving original formatting

## Commands

Run as Clojure CLI tools (from consuming project):

```bash
# Ensure SDK is downloaded and configured
clj -Ttools ensure-sdk

# Update Kotlin versions across modules
clj -Ttools ensure-kotlin
```

The `:tools/usage {:ns-default plugin-dev-tools.core}` in deps.edn allows invoking functions by name.

## Dependencies

- `babashka/fs`: File system operations
- `babashka/curl`: HTTP downloads
- `borkdude/rewrite-edn`: Structure-preserving EDN rewriting