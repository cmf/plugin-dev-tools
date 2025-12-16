# Plugin dev tools

Tooling for developing IntelliJ plugins with `deps.edn` instead of Gradle. This
project is both a Clojure CLI tool (`plugin-dev-tools.core`) and a small build
library (`plugin-dev-tools.build`).

## Library usage (build.clj)
- Add the git dep to your plugin repo, typically on a `:build` alias, and point
  your `build.clj` at `plugin-dev-tools.build` (see Cursive’s `build.clj` for a
  reference wrapper).
- `plugin.edn` drives everything and uses a map for `:modules`, keyed by a
  module id with per-module metadata:
  ```clojure
  {:idea-version "2025.3-eap"
   :kotlin-version "2.2.20"
   :serialization-version "1.8.1"
   :coroutines-version "1.10.1-intellij-4"
   :ksp-version "2.2.20-2.0.2"
   :modules {"my-plugin" {:module-path "."
                          :description "Main plugin"
                          :main-plugin? true         ; controls plugin.xml update & packaging
                          :serialization? true       ; adds Kotlin serialization compiler plugin
                          :plugin-directory "my-plugin-id" ; name under sandbox/plugins, defaults to module ID
                          :kotlin-src-paths ["src/kotlin"]
                          :java-src-paths ["src/java"]
                          :clojure-src-paths ["src/clojure"]
                          :kotlin-test-paths ["test/kotlin"]
                          :java-test-paths ["test/java"]
                          :resource-paths ["resources"]
                          :depends ["shared"]}       ; other module ids
             "shared"    {:module-path "shared"
                          :description "Shared code"
                          :java-src-paths ["src/java"]
                          :resource-paths ["resources"]}}}
  ```
- Build entry points (all read `plugin.edn`):
  - `plugin-dev-tools.build/compile` – clean + compile modules (Java/Kotlin/Clojure).
  - `plugin-dev-tools.build/compile-tests` – compile test variants.
  - `plugin-dev-tools.build/package` – compile, populate sandbox, and zip plugin
    (`build/distributions/<plugin>-<version>.zip`).
  - Helpers for KSP (`ksp-run`), sandbox prep, plugin.xml updating, verifier
    download/invocation, etc., are available if you want custom wrappers.

## CLI usage (SDK/Kotlin management)
- Install as a tool (git tag/sha):
  ```bash
  clojure -Ttools install-latest :lib io.github.cmf/plugin-dev-tools :as plugin
  ```
- Run from a consuming plugin repo:
  ```bash
  clj -Tplugin ensure-sdk
  clj -Tplugin ensure-kotlin
  ```
- `plugin.edn` must provide `:idea-version`, Kotlin/serialization/coroutines/KSP
  versions, optional `:plugins` to download from the marketplace, and **a
  sequence of module paths** (e.g. `["deps.edn" "submodule/deps.edn"]`) so the
  tool knows which `deps.edn` files to rewrite. The build-library module map is
  not yet consumed here.
- SDKs and plugins are downloaded to `~/.sdks/` and `~/.sdks/plugins/`.

## Notes
- This repo is meant for Kotlin/Java IntelliJ plugins; it does not help write
  IntelliJ plugins in Clojure itself.
- `borkdude.rewrite-edn` is used so `deps.edn` formatting/comments are preserved
  when rewriting paths and versions.
