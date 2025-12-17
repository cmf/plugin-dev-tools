# Plugin dev tools

Tooling for developing IntelliJ plugins with `deps.edn` instead of Gradle. This
project is both a Clojure CLI tool (`plugin-dev-tools.core`) and a small build
library (`plugin-dev-tools.build`).

## Configuration (`plugin.edn`)
- Shared between the CLI and the build library.
- Required top-level keys: `:idea-version`, `:kotlin-version`, `:serialization-version`,
  `:coroutines-version`, `:ksp-version`, and a `:modules` **map** keyed by module id.
- Module metadata (most optional, sensible defaults applied):
  - `:module-path` (default module id), `:description`, `:depends` (module ids).
  - `:main-plugin?` toggles plugin.xml updating/packaging; `:plugin-directory` overrides the
    directory name under `sandbox/plugins/` (default module id).
  - Source/resource/test paths: `:kotlin-src-paths`, `:java-src-paths`, `:clojure-src-paths`,
    `:kotlin-test-paths`, `:java-test-paths`, `:resource-paths`.
  - Kotlin extras: `:serialization?` adds the serialization compiler plugin.
  - Packaging: `:include-in-sandbox?` (default true) controls whether a module’s jar goes into
    the sandbox; `:merge-into-main?` (default false) copies resources/classes into the main
    plugin jar instead of shipping a separate jar.
  - Optional KSP blocks: `:ksp` and `:ksp-test` accept `{:processor-module \"build-tools\"
    :target-packages [\"com.example\"] :target-packages-prop \"my.prop\"}`. The processor jar
    is derived from the referenced module, cache/output dirs are derived automatically, and
    the processor module is added to `:depends`.
- `module-info` derives per-module data (including `:deps-file` paths) in dependency order; all
  commands below use it to locate `deps.edn` files.

## CLI usage (SDK/Kotlin management)
- Install as a tool and run from a consuming plugin repo:
  ```bash
  clojure -Ttools install-latest :lib io.github.cmf/plugin-dev-tools :as plugin
  clj -Tplugin ensure-sdk
  clj -Tplugin ensure-kotlin
  ```
- `ensure-sdk` downloads the IDEA SDK/plugins into `~/.sdks/`, generates SDK `deps.edn` files,
  and rewrites each module’s `deps.edn` `:local/root` entries to point at the downloaded SDK
  and any requested plugins.
- `ensure-kotlin` rewrites Kotlin/serialization/coroutines/KSP versions across all module
  `deps.edn` files.

## Build library usage (`build.clj`)
- Add the git dep on a `:build` alias, then wrap the entry points in your `build.clj`
  (`plugin-dev-tools.build/compile`, `compile-tests`, `package`). See Cursive/Scribe for
  reference wrappers.
- KSP is declarative via the module `:ksp`/`:ksp-test` blocks; `compile` and `compile-tests`
  will build processor modules as needed and invoke KSP before Kotlin compilation.
- `package` compiles, syncs kotlinc config, builds jars (merging as configured), prepares the
  sandbox, and zips to `build/distributions/<plugin>-<version>.zip`.
- Utilities for running `kotlinc`, `javac`, `ksp-run`, `prepare-sandbox`, `plugin.xml`
  updates, and `verify-plugin` are available if you need custom flows.

## Notes
- This repo is meant for Kotlin/Java IntelliJ plugins; it does not help write
  IntelliJ plugins in Clojure itself.
- `borkdude.rewrite-edn` is used so `deps.edn` formatting/comments are preserved
  when rewriting paths and versions.
