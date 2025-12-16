# CLAUDE.md

This repo provides Clojure tooling for Kotlin/Java IntelliJ plugins (avoid Gradle). It is used both as a CLI tool and as a build library (Cursive/Scribe).

- **Config:** `plugin.edn` in consuming repos. `:modules` is now usually a map keyed by module id with metadata (`:module-path`, `:description`, `:main-plugin?`, source/resource path vectors, `:depends`, etc.).
- **CLI (`plugin-dev-tools.core`):**
  - `clj -Ttools ensure-sdk` – downloads SDK/plugins to `~/.sdks`, rewrites module `deps.edn` `:local/root`s via `plugin-dev-tools.ensure`.
  - `clj -Ttools ensure-kotlin` – rewrites Kotlin/serialization/coroutines/KSP versions in module `deps.edn` via `plugin-dev-tools.update-kotlin`.
- **Build library (`plugin-dev-tools.build`):** helpers for module expansion (`module-info`), javac/kotlinc wrappers, KSP runner, plugin.xml updater, sandbox packaging, plugin verifier. Consuming `build.clj` typically wraps these entry points: `compile`, `compile-tests`, `package`.
- **deps.edn rewriting:** uses `borkdude.rewrite-edn` to preserve formatting/comments.
- **SDK storage:** `~/.sdks/ideaIU-{version}.zip`, `~/.sdks/{version}/`, `~/.sdks/ideaIC-{version}-sources.jar`, plugins under `~/.sdks/plugins/{id}/{version}/`.
