# CLAUDE.md

Concise notes for working here:
- Purpose: Clojure tooling for Kotlin/Java IntelliJ plugins (CLI + build lib). Avoids Gradle.
- Config (`plugin.edn`): map under `:modules` keyed by module id; metadata includes `:module-path`, `:description`, `:depends`, path vectors, `:main-plugin?`, `:plugin-directory`, `:include-in-sandbox?` (default true), `:merge-into-main?` (default false), `:serialization?`, optional `:ksp`/`:ksp-test` blocks (`:processor-module`, `:target-packages`, optional `:target-packages-prop`, alias overrides). `module-info` derives deps files and ordering for all commands.
- CLI (`plugin-dev-tools.core`): `clj -Tplugin ensure-sdk` downloads SDK/plugins to `~/.sdks`, rewrites module `deps.edn` roots; `clj -Tplugin ensure-kotlin` rewrites Kotlin/serialization/coroutines/KSP versions.
- Build lib (`plugin-dev-tools.build`): main entry points `compile`, `compile-tests`, `package`; KSP runs declaratively from module blocks and will build processor modules as needed; helpers for `ksp-run`, `kotlinc`, `javac`, sandbox prep, plugin.xml update, verifier.
- `deps.edn` rewrites preserve formatting via `borkdude.rewrite-edn`. Plugins/SDK stored under `~/.sdks/` and `~/.sdks/plugins/{id}/{version}/`.
