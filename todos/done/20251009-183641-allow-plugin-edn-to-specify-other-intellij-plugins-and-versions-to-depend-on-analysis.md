# Codebase Analysis for Plugin Dependencies Feature

## Agent 1: SDK Download Mechanism Analysis

Perfect! Now I have all the information needed to provide a comprehensive analysis.

---

# SDK Download and Configuration Analysis

## Overview

The SDK download and configuration mechanism in `/Users/colin/dev/plugin-dev-tools/src/plugin_dev_tools/ensure.clj` provides a sophisticated system for downloading IntelliJ IDEA SDKs from JetBrains repositories and configuring them for use with Clojure-based plugin development.

## Key Functions and Responsibilities

### 1. Configuration Reading

**Entry Point: `ensure-sdk` in `core.clj`**

```clojure
(defn ensure-sdk [args]
  (let [config (edn/read-string (slurp "plugin.edn"))
        marketing-version (:idea-version config)
        resolved-version (if (re-matches #"^\d{4}\.\d+(-eap)?$" marketing-version)
                           (ensure/resolve-idea-version marketing-version)
                           marketing-version)
        sdk (io/file (ensure/sdks-dir) resolved-version)]
    (when-not (fs/exists? sdk)
      (ensure/download-sdk marketing-version))
    (doseq [module (:modules config)]
      (ensure/update-deps-edn module resolved-version))))
```

**Responsibilities:**
- Reads `plugin.edn` from project root
- Extracts `:idea-version` and `:modules` configuration
- Determines if version needs resolution (marketing version vs. full version)
- Orchestrates the download and update process

### 2. Version Resolution

The system supports both marketing versions (e.g., `2025.2`, `2025.3-eap`) and full versions (e.g., `253.20558.43-EAP-SNAPSHOT`).

**Function: `resolve-idea-version`** (lines 104-130)

```clojure
(defn resolve-idea-version
  [marketing-version]
  (let [is-eap? (str/ends-with? marketing-version "-eap")
        repo (if is-eap? "snapshots" "releases")
        clean-version (if is-eap?
                        (str/replace marketing-version #"-eap$" "")
                        marketing-version)
        url (maven-metadata-url repo)]
    (let [resp (curl/get url {:as :stream :throw false})]
      (if (= 200 (:status resp))
        (let [{:keys [versions]} (parse-maven-metadata (:body resp))
              resolved (if is-eap?
                         (resolve-eap-version clean-version versions)
                         (resolve-release-version clean-version versions))]
          resolved)))))
```

**Responsibilities:**
- Detects whether version is EAP or release based on `-eap` suffix
- Selects appropriate JetBrains Maven repository (snapshots vs. releases)
- Fetches `maven-metadata.xml` from repository
- Delegates to specific resolution logic based on version type

**Supporting Functions:**

**`marketing-version->branch`** (lines 35-39) - Converts marketing version to branch number:
```clojure
(defn marketing-version->branch
  [version]
  (let [[year minor] (str/split version #"\.")]
    (str "2" (mod (parse-long year) 10) minor)))
; Example: "2025.3" -> "253"
```

**`resolve-eap-version`** (lines 90-102) - Finds latest EAP snapshot for a branch:
```clojure
(defn resolve-eap-version
  [marketing-version versions]
  (let [branch (marketing-version->branch marketing-version)
        branch-pattern (re-pattern (str "^" branch "\\.\\d+.*-EAP-SNAPSHOT$"))
        eap-versions (->> versions
                          (filter #(re-matches branch-pattern %))
                          (filter #(not (str/includes? % "CANDIDATE")))
                          (sort)
                          reverse)]
    (or (first eap-versions)
        (str branch "-EAP-SNAPSHOT"))))
; Example: "2025.3" -> "253.25908.13-EAP-SNAPSHOT"
```

**`resolve-release-version`** (lines 75-88) - Finds latest point release or exact match:
```clojure
(defn resolve-release-version
  [marketing-version versions]
  (let [prefix-matches (->> versions
                            (filter #(str/starts-with? % (str marketing-version ".")))
                            (sort version-compare)
                            reverse)
        exact-match (some #(when (= % marketing-version) %) versions)]
    (or (first prefix-matches)  ; Prefer latest point release if it exists
        exact-match              ; Otherwise use exact match
        marketing-version)))     ; Fall back to version as-is
; Example: "2025.2" -> "2025.2.3" (if 2025.2.3 exists)
```

### 3. SDK Download

**Function: `download-sdk`** (lines 167-199)

```clojure
(defn download-sdk
  [marketing-version]
  (let [version (resolve-idea-version marketing-version)
        repo (if (str/includes? version "SNAPSHOT") "snapshots" "releases")
        url (sdk-url repo version)]
    (when-not (fs/exists? (sdks-dir))
      (fs/create-dir (sdks-dir)))
    (when-not (fs/exists? (zipfile version))
      (println "Downloading" url)
      (let [resp (curl/get url {:as :stream :throw false})]
        (if (not= 200 (:status resp))
          (throw (ex-info "Problem downloading SDK" resp)))
        (io/copy (:body resp) (zipfile version))
        @(:exit resp)

        (when-not (fs/exists? (sources-file version))
          (let [url (str "https://www.jetbrains.com/intellij-repository/"
                         repo "/com/jetbrains/intellij/idea/ideaIC/"
                         version "/ideaIC-" version "-sources.jar")
                resp (curl/get url {:as :stream :throw false})]
            (io/copy (:body resp) (sources-file version))))))
    (process-sdk version)
    version))
```

**Responsibilities:**
- Resolves marketing version to full version
- Creates `~/.sdks/` directory if needed
- Downloads SDK zip file to `~/.sdks/ideaIU-{version}.zip`
- Downloads sources jar to `~/.sdks/ideaIC-{version}-sources.jar`
- Calls `process-sdk` to extract and generate deps.edn files
- Returns resolved version for use in subsequent steps

**URL Construction:**

```clojure
(defn sdk-url [repo version]
  (str "https://www.jetbrains.com/intellij-repository/"
       repo
       "/com/jetbrains/intellij/idea/ideaIU/"
       version
       "/ideaIU-" version ".zip"))
```

### 4. SDK Extraction and deps.edn Generation

**Function: `process-sdk`** (lines 132-165)

```clojure
(defn process-sdk
  [version]
  (println "Unzipping SDK")
  (let [sdk (.getAbsolutePath (io/file (sdks-dir) version))
        ret (sh "/usr/bin/unzip" (.getAbsolutePath (zipfile version)) "-d" sdk)]
    (if (not= 0 (:exit ret))
      (throw (ex-info "Problem unzipping" ret)))
    ; Make executables
    (sh "/bin/chmod" "+x" (str sdk "/bin/mac/aarch64/fsnotifier"))
    (sh "/bin/chmod" "+x" (str sdk "/bin/mac/aarch64/printenv"))

    ; Generate deps.edn files
    (let [sdk-file (io/file (sdks-dir) version)
          aliases '{:aliases {:no-clojure {:classpath-overrides {...}}
                              :test       {:extra-paths []}}}
          jars (->> (fs/glob sdk-file "lib/**.jar")
                    (remove #(= (fs/file-name %) "annotations.jar"))
                    (map #(fs/relativize sdk-file %))
                    (mapv str))]
      (spit (io/file sdk-file "deps.edn") (pr-str (merge aliases {:paths jars})))
      (let [plugins (fs/glob sdk-file "plugins/*")]
        (doseq [plugin plugins]
          (when (fs/directory? plugin)
            (let [jars (->> (fs/glob plugin "lib/**.jar")
                            (remove #(str/includes? (fs/file-name %) "jps-plugin"))
                            (map #(fs/relativize plugin %))
                            (mapv str))]
              (spit (str plugin "/deps.edn") (pr-str (merge aliases {:paths jars}))))))))))
```

**Responsibilities:**
- Extracts SDK zip to `~/.sdks/{version}/`
- Sets executable permissions on macOS binaries (fsnotifier, printenv)
- Generates `deps.edn` at SDK root with all SDK jars
- Generates `deps.edn` for each plugin subdirectory
- Filters out problematic jars (`annotations.jar`, `jps-plugin`)
- Creates standard aliases (`:no-clojure`, `:test`)

**Generated deps.edn Structure:**
```clojure
{:paths ["lib/idea.jar" "lib/util.jar" ...]
 :aliases {:no-clojure {:classpath-overrides {org.clojure/clojure ""
                                               org.clojure/spec.alpha ""
                                               org.clojure/core.specs.alpha ""}}
           :test {:extra-paths []}}}
```

### 5. Module deps.edn Updates

**Function: `update-deps-edn`** (lines 201-228)

```clojure
(defn update-deps-edn [file-name version]
  (let [deps-edn-string (slurp file-name)
        nodes (rewrite/parse-string deps-edn-string)
        edn (edn/read-string deps-edn-string)
        nodes (reduce (fn [nodes alias]
                        (let [keys (filter #(#{"intellij" "plugin"} (namespace %))
                                           (keys (get-in edn [:aliases alias :extra-deps])))]
                          (reduce (fn [nodes key]
                                    (let [target [:aliases alias :extra-deps key :local/root]]
                                      (cond
                                        (.endsWith (name key) "$sources")
                                        (rewrite/assoc-in nodes target
                                          (.getAbsolutePath (io/file (sdks-dir)
                                            (str "ideaIC-" version "-sources.jar"))))

                                        (= "intellij" (namespace key))
                                        (rewrite/assoc-in nodes target
                                          (.getAbsolutePath (io/file (sdks-dir) version)))

                                        (= "plugin" (namespace key))
                                        (let [previous (get-in edn target)
                                              file (io/file previous)
                                              name (.getName file)]
                                          (rewrite/assoc-in nodes target
                                            (.getAbsolutePath (io/file (sdks-dir) version "plugins" name)))))))
                                  nodes
                                  keys)))
                      nodes
                      [:sdk :ide])]
    (spit file-name (str nodes))))
```

**Responsibilities:**
- Uses `borkdude.rewrite-edn` to preserve formatting and comments
- Processes `:sdk` and `:ide` aliases
- Updates `:local/root` paths for namespaced dependencies:
  - `intellij/*` keys → `~/.sdks/{version}`
  - `plugin/*` keys → `~/.sdks/{version}/plugins/{plugin-name}`
  - Keys ending in `$sources` → `~/.sdks/ideaIC-{version}-sources.jar`
- Maintains original file structure while updating only paths

**Example Transformation:**
```clojure
; Before
{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root "/Users/colin/.sdks/253.20558.43-EAP-SNAPSHOT"}}}}}

; After (with new version)
{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root "/Users/colin/.sdks/253.25908.15-EAP-SNAPSHOT"}}}}}
```

## Overall Flow

```
1. User runs: clj -Ttools ensure-sdk
   ↓
2. core/ensure-sdk reads plugin.edn
   ↓
3. Checks if version is marketing version (2025.3) or full version (253.xxx)
   ↓
4. If marketing version:
   → ensure/resolve-idea-version
   → Fetches maven-metadata.xml from JetBrains repository
   → Resolves to latest EAP snapshot or release point version
   ↓
5. Checks if SDK exists at ~/.sdks/{resolved-version}/
   ↓
6. If not exists:
   → ensure/download-sdk
   → Downloads ideaIU-{version}.zip
   → Downloads ideaIC-{version}-sources.jar
   → ensure/process-sdk
     → Extracts zip to ~/.sdks/{version}/
     → Sets executable permissions
     → Generates deps.edn for SDK root
     → Generates deps.edn for each plugin
   ↓
7. For each module in :modules:
   → ensure/update-deps-edn
   → Updates :local/root paths using rewrite-edn
   → Preserves formatting and comments
```

## Key Patterns Identified

### HTTP Downloads
- Uses `babashka.curl/get` with `:as :stream` for efficient streaming
- Status checking: `(= 200 (:status resp))`
- Streaming to files: `(io/copy (:body resp) target-file)`
- Deref exit: `@(:exit resp)` to ensure completion

```clojure
(let [resp (curl/get url {:as :stream :throw false})]
  (if (not= 200 (:status resp))
    (throw (ex-info "Problem downloading SDK" resp)))
  (io/copy (:body resp) (zipfile version))
  @(:exit resp))
```

### File Extraction
- Uses shell commands via `clojure.java.shell/sh`
- Direct invocation: `(sh "/usr/bin/unzip" source "-d" destination)`
- Exit code checking: `(if (not= 0 (:exit ret)) (throw ...))`
- macOS-specific permissions: `(sh "/bin/chmod" "+x" path)`

### EDN Manipulation
- **Dual parsing strategy**: Parse as both string nodes and EDN data
  - `nodes` for structure-preserving updates
  - `edn` for data navigation/querying
- **Path-based updates**: `(rewrite/assoc-in nodes [:path :to :key] new-value)`
- **Preservation**: Original formatting, comments, and structure maintained
- **Namespaced keys**: Used for categorization (`intellij/*`, `plugin/*`)

```clojure
(let [deps-edn-string (slurp file-name)
      nodes (rewrite/parse-string deps-edn-string)  ; For updates
      edn (edn/read-string deps-edn-string)]        ; For queries
  (rewrite/assoc-in nodes [:path] value))
```

### File System Operations
- Uses `babashka.fs` for modern file operations
- Globbing: `(fs/glob base-path "lib/**.jar")`
- Relativization: `(fs/relativize base-path file-path)`
- Existence checks: `(fs/exists? path)`
- Directory creation: `(fs/create-dir path)`

### Maven Metadata Parsing
- XML parsing with `clojure.xml/parse`
- Extract versioning information from XML structure
- Version comparison using semantic versioning
- Pattern matching for EAP snapshots

## File Locations

**Configuration:**
- Input: `{project-root}/plugin.edn`
- Module deps: Paths specified in `:modules` (e.g., `{project-root}/deps.edn`)

**SDK Storage:**
- Root: `~/.sdks/`
- Downloaded zip: `~/.sdks/ideaIU-{version}.zip`
- Extracted SDK: `~/.sdks/{version}/`
- SDK deps.edn: `~/.sdks/{version}/deps.edn`
- Plugin deps.edn: `~/.sdks/{version}/plugins/{plugin-name}/deps.edn`
- Sources: `~/.sdks/ideaIC-{version}-sources.jar`

This architecture provides a clean separation between SDK management and project configuration, with intelligent version resolution and structure-preserving updates to keep project files maintainable.

## Agent 2: EDN File Manipulation Patterns

Perfect! Now I have all the information needed to provide a comprehensive analysis. Let me compile the detailed findings.

---

# EDN Manipulation Patterns in plugin-dev-tools

Based on my research of `/Users/colin/dev/plugin-dev-tools/src/plugin_dev_tools/ensure.clj` and `/Users/colin/dev/plugin-dev-tools/src/plugin_dev_tools/update_kotlin.clj`, here's a detailed analysis of how this codebase manipulates EDN files using `borkdude.rewrite-edn`.

## Core Pattern: Dual Representation Strategy

Both modules use the same fundamental approach for EDN manipulation:

```clojure
(let [deps-edn-string (slurp file-name)
      nodes (rewrite/parse-string deps-edn-string)  ; String-based AST representation
      edn (edn/read-string deps-edn-string)]        ; Data structure representation
  ;; ... manipulations ...
  (spit file-name (str nodes)))
```

**Why two representations?**
- `nodes`: Used for **writing** - preserves formatting, comments, and whitespace
- `edn`: Used for **reading** - provides easy navigation and querying of structure

## Key Functions and Patterns

### 1. Path-Based Updates with `rewrite/assoc-in`

The primary manipulation operation is updating values at specific paths:

```clojure
(rewrite/assoc-in nodes path new-value)
```

**Example from `ensure.clj` (line 212-213):**
```clojure
(rewrite/assoc-in nodes target
  (.getAbsolutePath (io/file (sdks-dir) (str "ideaIC-" version "-sources.jar"))))
```

This updates the `:local/root` path for IntelliJ sources while preserving all formatting.

### 2. Navigating Nested Structures

Both modules navigate deeply nested paths in deps.edn:

**Pattern:** `[:aliases alias-name :extra-deps dependency-key :mvn/version]`

**Example from `update_kotlin.clj` (line 40):**
```clojure
(rewrite/assoc-in nodes (into path [key :mvn/version]) kotlin-version)
```

Where `path` might be `[:aliases :build :extra-deps]` and `key` is the dependency symbol like `'org.jetbrains.kotlin/kotlin-stdlib`.

### 3. Conditional Updates with `reduce`

Both modules use `reduce` to iterate over collections and conditionally update nodes:

**From `ensure.clj` (lines 205-227):**
```clojure
(reduce (fn [nodes alias]
          (let [keys (filter #(#{"intellij" "plugin"} (namespace %))
                            (keys (get-in edn [:aliases alias :extra-deps])))]
            (reduce (fn [nodes key]
                      (let [target [:aliases alias :extra-deps key :local/root]]
                        (cond
                          (.endsWith (name key) "$sources")
                          (rewrite/assoc-in nodes target ...)
                          (= "intellij" (namespace key))
                          (rewrite/assoc-in nodes target ...)
                          ;; etc.
                          :else nodes)))
                    nodes
                    keys)))
        nodes
        [:sdk :ide])
```

**Pattern breakdown:**
1. Use `edn` data structure to **find** which keys to update
2. Use `nodes` AST to **perform** the updates
3. Thread `nodes` through reduce operations
4. Return updated `nodes` at the end

### 4. Filtering and Identification Predicates

**From `update_kotlin.clj` (lines 12-31):**

```clojure
(defn kotlin-key? [key]
  (and (= "org.jetbrains.kotlin" (namespace key))
       (or (= "kotlin-serialization-compiler-plugin" (name key))
           (str/starts-with? (name key) "kotlin-test")
           (str/starts-with? (name key) "kotlin-stdlib")
           ;; etc.
           )))
```

These predicates use namespace and name functions on symbols to identify which dependencies should be updated.

### 5. Helper Function: `base-key`

**From `update_kotlin.clj` (lines 7-10):**

```clojure
(defn base-key [key]
  (if-let [index (str/index-of (name key) \$)]
    (symbol (namespace key) (subs (name key) 0 index))
    key))
```

This handles keys with suffixes like `'org.jetbrains.kotlin/kotlin-stdlib$sources`, stripping the `$sources` part to check if it's a Kotlin dependency.

### 6. Reusable Update Function

**From `update_kotlin.clj` (lines 33-50):**

```clojure
(defn update-deps-map [nodes deps-map path versions]
  (if deps-map
    (let [{:keys [kotlin-version serialization-version ...]} versions]
      (reduce (fn [nodes key]
                (let [base (base-key key)]
                  (cond
                    (kotlin-key? base)
                    (rewrite/assoc-in nodes (into path [key :mvn/version]) kotlin-version)
                    (serialization-key? base)
                    (rewrite/assoc-in nodes (into path [key :mvn/version]) serialization-version)
                    ;; etc.
                    :else nodes)))
              nodes
              (keys (get-in deps-map path))))
    nodes))
```

This is a reusable function that:
- Takes a base path (e.g., `[:deps]` or `[:aliases :build :extra-deps]`)
- Iterates over all dependencies at that path
- Updates matching dependencies based on predicates
- Returns updated nodes

## Complete Example: Adding New Dependencies

Here's a complete example showing how to add new dependencies to a deps.edn file while preserving structure:

```clojure
(ns example.add-deps
  (:require [borkdude.rewrite-edn :as rewrite]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn add-dependency
  "Add a new dependency to deps.edn at the specified path"
  [file-name dep-path dep-key dep-config]
  (let [deps-edn-string (slurp file-name)
        nodes (rewrite/parse-string deps-edn-string)
        edn (edn/read-string deps-edn-string)

        ;; Check if dependency already exists
        existing-deps (get-in edn dep-path)

        ;; Only add if it doesn't exist
        updated-nodes (if (contains? existing-deps dep-key)
                        nodes  ; Already exists, don't modify
                        (rewrite/assoc-in nodes
                                         (into dep-path [dep-key])
                                         dep-config))]
    (spit file-name (str updated-nodes))))

;; Example usage: Add a new dependency to :deps
(add-dependency
  "deps.edn"
  [:deps]
  'org.clojure/data.json
  {:mvn/version "2.4.0"})

;; Example usage: Add to an alias
(add-dependency
  "deps.edn"
  [:aliases :test :extra-deps]
  'io.github.cognitect-labs/test-runner
  {:git/tag "v0.5.1" :git/sha "dfb30dd"})
```

## Example: Updating Multiple Dependencies

Here's how to update multiple dependency versions at once (pattern from `update_kotlin.clj`):

```clojure
(defn update-multiple-deps
  "Update versions for multiple dependencies across all locations"
  [file-name dep-map]
  (let [deps-edn-string (slurp file-name)
        nodes (rewrite/parse-string deps-edn-string)
        edn (edn/read-string deps-edn-string)

        ;; Helper to update a single dependency map
        update-in-map (fn [nodes path]
                        (if-let [deps (get-in edn path)]
                          (reduce (fn [n dep-key]
                                    (if-let [new-version (get dep-map dep-key)]
                                      (rewrite/assoc-in n
                                                       (into path [dep-key :mvn/version])
                                                       new-version)
                                      n))
                                  nodes
                                  (keys deps))
                          nodes))

        ;; Update in :deps
        nodes (update-in-map nodes [:deps])

        ;; Update in all aliases
        nodes (reduce (fn [n alias]
                        (-> n
                            (update-in-map [:aliases alias :deps])
                            (update-in-map [:aliases alias :extra-deps])))
                      nodes
                      (keys (:aliases edn)))]
    (spit file-name (str nodes))))

;; Example usage
(update-multiple-deps
  "deps.edn"
  {'org.clojure/clojure "1.12.0"
   'org.clojure/core.async "1.6.681"
   'org.clojure/data.json "2.5.0"})
```

## Key Principles

1. **Always parse twice**: Once for structure-preserving manipulation (rewrite-edn), once for data navigation (edn/read-string)

2. **Thread nodes through operations**: `reduce` operations should thread the `nodes` value, returning updated nodes each time

3. **Query with EDN, write with nodes**: Use the parsed EDN map to determine what to change, use nodes to make the changes

4. **Convert back to string with `str`**: The final `(spit file-name (str nodes))` converts the AST back to formatted EDN

5. **Use `get-in` for navigation**: Both on EDN maps and as paths to `rewrite/assoc-in`

6. **Preserve structure with `cond`**: Use conditional logic to handle different types of updates without disrupting unrelated entries

## Testing Pattern

Both modules include comprehensive tests that verify:
- Correct value updates
- Formatting preservation (comments, whitespace)
- Selective updates (only target dependencies changed)

**Example test pattern:**
```clojure
(testing "preserves formatting and comments"
  (let [input "; Comment at top\n{:deps {...}}"
        _ (spit "/tmp/test.edn" input)
        _ (update-function "/tmp/test.edn" versions)
        result (slurp "/tmp/test.edn")]
    (is (re-find #"; Comment at top" result))
    (is (re-find #"expected-value" result))))
```

## Summary

The codebase demonstrates a robust pattern for EDN manipulation that:
- **Preserves formatting** through rewrite-edn's AST approach
- **Provides clean APIs** through reusable helper functions
- **Handles edge cases** (namespaced keys, suffixed keys, nested paths)
- **Maintains safety** by only updating targeted dependencies
- **Is testable** with clear before/after assertions

This dual-representation pattern (AST + data) is the key insight that makes complex EDN updates tractable while maintaining human-readable formatting.

## Agent 3: Test Patterns and Structure

Now I have comprehensive information about the testing patterns. Let me create a detailed analysis:

---

## Testing Patterns Analysis for plugin-dev-tools

Based on my analysis of `/Users/colin/dev/plugin-dev-tools/test/plugin_dev_tools/ensure_test.clj` and related test files, here's a detailed breakdown of the testing patterns used in this codebase:

### 1. **Test Structure & Organization**

**Namespace Setup:**
```clojure
(ns plugin-dev-tools.ensure-test
  (:require [clojure.test :refer :all]
            [plugin-dev-tools.ensure :as ensure]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import (java.io File)))
```

- Uses standard `clojure.test` framework
- Imports the namespace under test with an alias (`ensure`, `kotlin`, `core`)
- Includes necessary utilities (`edn`, `io`, `fs`)
- Imports Java classes when needed (`java.io.File`)

**Test Definition Pattern:**
```clojure
(deftest test-function-name
  (testing "description of what this test verifies"
    (let [setup-vars ...]
      (is (= expected actual)))))
```

- Each `deftest` focuses on a single function
- Multiple `testing` blocks within a `deftest` for different scenarios
- Uses `let` bindings for test setup
- `is` macro for assertions

### 2. **Using /tmp/ for Temporary Files**

**Pattern 1: Direct /tmp/ usage without cleanup**
```clojure
(deftest test-update-deps-edn
  (testing "updates intellij dependency paths"
    (let [version "2023.1.1"
          input "{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root \"/old/path/2023.1.0\"}}}}}"
          _ (spit "/tmp/test-ensure-deps.edn" input)
          _ (ensure/update-deps-edn "/tmp/test-ensure-deps.edn" version)
          result (slurp "/tmp/test-ensure-deps.edn")
          parsed (edn/read-string result)]
      (is (= expected-path (get-in parsed [:aliases :sdk :extra-deps 'intellij/sdk :local/root]))))))
```

**Key observations:**
- Uses fixed paths like `/tmp/test-ensure-deps.edn`
- Creates files with `spit`
- Reads back with `slurp`
- Parses EDN with `edn/read-string` for assertions
- **No explicit cleanup** (relies on /tmp/ being temporary)

**Pattern 2: With cleanup (from core_test.clj)**
```clojure
(deftest test-ensure-kotlin-integration
  (testing "ensure-kotlin integration with update-deps-edn"
    (let [module1 "/tmp/test-module1-deps.edn"
          module1-content "{:deps {org.jetbrains.kotlin/kotlin-stdlib {:mvn/version \"2.0.0\"}}}"
          versions {:kotlin-version "2.1.0" ...}]

      (spit module1 module1-content)
      (kotlin/update-deps-edn module1 versions)

      (let [result (edn/read-string (slurp module1))]
        (is (= "2.1.0" (get-in result [:deps 'org.jetbrains.kotlin/kotlin-stdlib :mvn/version]))))

      (io/delete-file module1))))  ; Explicit cleanup
```

### 3. **Mock/Fixture Patterns**

**Mock Data as Def Constants:**
```clojure
(def sample-release-metadata-xml
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<metadata>
  <groupId>com.jetbrains.intellij.idea</groupId>
  <artifactId>ideaIU</artifactId>
  <versioning>
    <latest>2025.2.3</latest>
    <versions>
      <version>2024.3</version>
      ...
    </versions>
  </versioning>
</metadata>")

(def sample-snapshot-metadata-xml "...")
```

**Using Mock Data in Tests:**
```clojure
(deftest test-parse-maven-metadata
  (testing "parses release metadata correctly"
    (let [input-stream (java.io.ByteArrayInputStream.
                         (.getBytes sample-release-metadata-xml))
          result (ensure/parse-maven-metadata input-stream)]
      (is (= "2025.2.3" (:latest result)))
      (is (= [...] (:versions result))))))
```

**Physical Fixtures:**
- Located in `/Users/colin/dev/plugin-dev-tools/test/fixtures/`
- Example: `test/fixtures/sdk/` contains mock SDK structure
- Used for testing file system operations without downloading actual SDKs

### 4. **Testing File Downloads**

**No Actual Download Mocking:**
The codebase doesn't mock HTTP downloads in tests. Instead, it tests:

1. **URL Construction** (pure functions):
```clojure
(deftest test-sdk-url
  (testing "constructs correct SDK URL for releases"
    (let [version "2023.1.1"
          expected "https://www.jetbrains.com/intellij-repository/releases/..."
          actual (ensure/sdk-url "releases" version)]
      (is (= expected actual)))))
```

2. **XML Parsing** (with mock data):
```clojure
(deftest test-parse-maven-metadata
  (testing "parses release metadata correctly"
    (let [input-stream (java.io.ByteArrayInputStream.
                         (.getBytes sample-release-metadata-xml))
          result (ensure/parse-maven-metadata input-stream)]
      (is (= "2025.2.3" (:latest result))))))
```

3. **Error Handling** (graceful degradation):
```clojure
(testing "handles malformed XML gracefully"
  (let [malformed-xml "<metadata><broken>"
        input-stream (java.io.ByteArrayInputStream. (.getBytes malformed-xml))
        result (ensure/parse-maven-metadata input-stream)]
    (is (nil? (:latest result)))
    (is (= [] (:versions result)))))
```

**Key Strategy:**
- Separate pure logic (URL building, parsing) from side effects (downloading)
- Test pure functions with mock data
- Integration tests accept that actual downloads may fail

### 5. **Testing EDN Manipulation**

**Standard Pattern:**
```clojure
(testing "updates kotlin version in deps"
  (let [input "{:deps {org.jetbrains.kotlin/kotlin-stdlib {:mvn/version \"1.9.0\"}}}"
        versions {:kotlin-version "2.0.0"}
        _ (spit "/tmp/test-deps.edn" input)
        _ (kotlin/update-deps-edn "/tmp/test-deps.edn" versions)
        result (slurp "/tmp/test-deps.edn")
        parsed (edn/read-string result)]
    (is (= "2.0.0" (get-in parsed [:deps 'org.jetbrains.kotlin/kotlin-stdlib :mvn/version])))))
```

**Steps:**
1. Create input EDN string with old values
2. Write to temp file with `spit`
3. Call function under test
4. Read result with `slurp`
5. Parse with `edn/read-string`
6. Assert with `get-in` navigation

**Testing Format Preservation:**
```clojure
(testing "preserves formatting"
  (let [version "2023.1.1"
        input "; Comment\n{:aliases {:sdk {:extra-deps {intellij/sdk {:local/root \"/old/path\"}}}}}"
        _ (spit "/tmp/test-ensure-deps.edn" input)
        _ (ensure/update-deps-edn "/tmp/test-ensure-deps.edn" version)
        result (slurp "/tmp/test-ensure-deps.edn")]
    (is (re-find #"; Comment" result))))
```

Uses regex matching on string output to verify formatting is preserved (crucial when using `borkdude.rewrite-edn`).

### 6. **Helper Functions for Testing**

The codebase **doesn't define explicit test helper functions**. Instead, it uses:

1. **Inline let bindings** for setup
2. **Standard library functions** (`spit`, `slurp`, `edn/read-string`)
3. **Clojure.test macros** (`is`, `testing`)
4. **Java interop** when needed (`java.io.ByteArrayInputStream`)

### 7. **Error Handling Tests**

```clojure
(deftest test-ensure-kotlin-error-handling
  (testing "ensure-kotlin handles missing plugin.edn gracefully"
    ; This test just verifies that ensure-kotlin doesn't throw when plugin.edn is missing
    ; We expect it to print an error but not crash
    ; Note: This will print an error message during test execution, which is expected
    (is (nil? (core/ensure-kotlin [])))))
```

**Pattern:**
- Accept that error messages will be printed
- Verify function returns gracefully (e.g., `nil`)
- Use comments to document expected error output

### 8. **Integration vs Unit Tests**

**Unit Tests** (most common):
```clojure
(deftest test-kotlin-key?
  (testing "identifies kotlin dependencies"
    (is (true? (kotlin/kotlin-key? 'org.jetbrains.kotlin/kotlin-stdlib)))
    (is (true? (kotlin/kotlin-key? 'org.jetbrains.kotlin/kotlin-test))))

  (testing "rejects non-kotlin dependencies"
    (is (false? (kotlin/kotlin-key? 'org.jetbrains.kotlinx/kotlinx-coroutines-core)))))
```

**Integration Tests** (limited):
```clojure
(deftest test-ensure-kotlin-integration
  (testing "ensure-kotlin integration with update-deps-edn"
    ; Creates temp files, calls function, verifies result, cleans up
    ...))
```

---

## How to Write Tests for New Functionality

### Example: Testing a New Function That Downloads and Updates EDN

```clojure
(ns plugin-dev-tools.new-feature-test
  (:require [clojure.test :refer :all]
            [plugin-dev-tools.new-feature :as feature]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; 1. Test pure URL construction
(deftest test-build-download-url
  (testing "constructs correct download URL"
    (let [artifact "some-lib"
          version "1.0.0"
          expected "https://repo.example.com/some-lib/1.0.0/some-lib-1.0.0.jar"
          actual (feature/build-download-url artifact version)]
      (is (= expected actual)))))

;; 2. Mock data for parsing
(def sample-response-data
  "{\"version\": \"1.0.0\", \"url\": \"https://example.com/download\"}")

;; 3. Test parsing with mock data
(deftest test-parse-response
  (testing "parses JSON response correctly"
    (let [input-stream (java.io.ByteArrayInputStream.
                         (.getBytes sample-response-data))
          result (feature/parse-response input-stream)]
      (is (= "1.0.0" (:version result)))
      (is (= "https://example.com/download" (:url result)))))

  (testing "handles malformed response gracefully"
    (let [malformed "{broken json"
          input-stream (java.io.ByteArrayInputStream. (.getBytes malformed))
          result (feature/parse-response input-stream)]
      (is (nil? result)))))

;; 4. Test EDN file updates
(deftest test-update-config-file
  (testing "updates dependency version in config"
    (let [input "{:deps {com.example/library {:mvn/version \"1.0.0\"}}}"
          new-version "2.0.0"
          temp-file "/tmp/test-new-feature-config.edn"
          _ (spit temp-file input)
          _ (feature/update-config-file temp-file "com.example/library" new-version)
          result (slurp temp-file)
          parsed (edn/read-string result)]
      (is (= "2.0.0" (get-in parsed [:deps 'com.example/library :mvn/version])))
      (io/delete-file temp-file)))

  (testing "preserves comments and formatting"
    (let [input "; Configuration file\n{:deps {com.example/library {:mvn/version \"1.0.0\"}}}"
          new-version "2.0.0"
          temp-file "/tmp/test-format-preservation.edn"
          _ (spit temp-file input)
          _ (feature/update-config-file temp-file "com.example/library" new-version)
          result (slurp temp-file)]
      (is (re-find #"; Configuration file" result))
      (is (re-find #"2\.0\.0" result))
      (io/delete-file temp-file)))

  (testing "handles missing dependency gracefully"
    (let [input "{:deps {other/library {:mvn/version \"1.0.0\"}}}"
          temp-file "/tmp/test-missing-dep.edn"
          _ (spit temp-file input)
          result (feature/update-config-file temp-file "com.example/library" "2.0.0")]
      ; Should not throw, should return indication of no-op or similar
      (is (not (nil? result)))
      (io/delete-file temp-file))))

;; 5. Integration test (if necessary)
(deftest test-download-and-update-integration
  (testing "full workflow without actual download"
    ; This would call your main function but with a test config
    ; that doesn't actually trigger downloads
    (let [config-file "/tmp/test-integration-config.edn"
          config "{:dependencies {com.example/library \"1.0.0\"}}"
          _ (spit config-file config)]
      ; Call integration function
      ; Verify results
      (io/delete-file config-file))))
```

### Best Practices Summary

1. **File Testing:**
   - Use `/tmp/` for temporary files
   - Use descriptive names: `/tmp/test-<feature>-<scenario>.edn`
   - Clean up with `io/delete-file` when needed (optional for /tmp/)

2. **EDN Testing:**
   - Write input as EDN string
   - `spit` to file
   - Call function under test
   - `slurp` result
   - Parse with `edn/read-string`
   - Use `get-in` for deep assertions

3. **Download/HTTP Testing:**
   - Don't mock the actual HTTP call
   - Test URL construction as pure functions
   - Test response parsing with `ByteArrayInputStream` and mock data
   - Store mock response data as `def` constants

4. **Format Preservation:**
   - Test with comments and whitespace in input
   - Use `re-find` on string output to verify preservation

5. **Error Handling:**
   - Test graceful degradation
   - Accept that error messages may print
   - Document expected errors in comments

6. **Organization:**
   - One `deftest` per function
   - Multiple `testing` blocks for scenarios
   - Mock data as module-level `def` constants
   - No need for complex test helpers
