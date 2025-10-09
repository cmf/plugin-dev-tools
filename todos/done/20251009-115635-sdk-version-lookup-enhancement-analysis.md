# Codebase Analysis for SDK Version Lookup Enhancement

## Agent 1: Current Version Handling Analysis

### Where and How `:idea-version` is Used

**File**: `/Users/colin/dev/plugin-dev-tools/src/plugin_dev_tools/core.clj`

**Lines 22-34**: The `:idea-version` is read from `plugin.edn` and used in two ways:

```clojure
(defn ensure-sdk [args]
  (try
    (let [config (edn/read-string (slurp "plugin.edn"))
          version (:idea-version config)                    ; Line 25: Extract version
          sdk (io/file (ensure/sdks-dir) version)]          ; Line 26: Check if SDK exists

      ; Download SDK if not present
      (when-not (fs/exists? sdk)
        (ensure/download-sdk version))                      ; Line 30: Download SDK

      ; Update deps.edn files with version
      (for [module (:modules config)]
        (ensure/update-deps-edn module version)))           ; Line 34: Update paths
```

**Key Usage Points**:
- Extracted from `plugin.edn` configuration (line 25)
- Used to check if SDK directory exists (line 26)
- Passed to `download-sdk` for downloading (line 30)
- Passed to `update-deps-edn` to update module paths (line 34)

### URL Construction

**File**: `/Users/colin/dev/plugin-dev-tools/src/plugin_dev_tools/ensure.clj`

**Lines 20-27**: URL construction function:

```clojure
(defn sdk-url [repo version]
  (str "https://www.jetbrains.com/intellij-repository/"
       repo                                                  ; "releases" or "snapshots"
       "/com/jetbrains/intellij/idea/ideaIU/"
       version
       "/ideaIU-"
       version
       ".zip"))
```

**URL Format**:
- Base: `https://www.jetbrains.com/intellij-repository/`
- Repository type: `releases` or `snapshots`
- Maven path: `/com/jetbrains/intellij/idea/ideaIU/{version}/ideaIU-{version}.zip`

### Current Expected Format for `:idea-version`

**Format**: The version string is used **as-is** without any parsing or transformation.

**Supported formats** (from tests):
- **Stable releases**: `2023.1.1` (semantic versioning)
- **EAP/Snapshot releases**: `253.20558.43-EAP-SNAPSHOT` (build number with suffix)

**No version parsing occurs** - the version string is treated as an opaque identifier.

### Repository Selection Logic (Releases vs Snapshots)

**File**: `/Users/colin/dev/plugin-dev-tools/src/plugin_dev_tools/ensure.clj`

**Lines 64-97**: The repository selection uses a **fallback strategy**:

```clojure
(defn download-sdk [version]
  (let [url (sdk-url "releases" version)]                   ; Line 65: Try releases first
    ;...
    (when-not (fs/exists? (zipfile version))
      (println "Downloading" url)
      (let [[resp repo]
            (let [resp (curl/get url {:as :stream :throw false})]  ; Line 71: Try download
              (if (= 200 (:status resp))
                [resp "releases"]                                    ; Line 73: Success on releases
                [(let [url (sdk-url "snapshots" version)]           ; Line 74: Fallback to snapshots
                   (println "Not found (response" (:status resp) "), downloading" url)
                   (curl/get url {:as :stream :throw false}))
                 "snapshots"]))]                                    ; Line 77: Use snapshots repo
```

**Strategy**:
1. **Always try `releases` first** (line 65)
2. **If 404/not found**, try `snapshots` (lines 74-77)
3. **Use the successful repository** for both SDK zip and sources jar (lines 84-96)
4. **Throw exception if both fail** (line 79)

**No version-based heuristics** - the code doesn't look at the version string to decide which repository to use.

### Key Functions Involved

**`plugin-dev-tools.core/ensure-sdk`** (lines 22-39 of core.clj)
- Reads `plugin.edn` configuration
- Orchestrates SDK download and deps.edn updates

**`plugin-dev-tools.ensure/download-sdk`** (lines 64-98 of ensure.clj)
- Main download orchestrator
- Handles repository fallback logic
- Downloads both SDK zip and sources jar

**`plugin-dev-tools.ensure/sdk-url`** (lines 20-27 of ensure.clj)
- Constructs download URLs
- Takes `repo` ("releases" or "snapshots") and `version`

**`plugin-dev-tools.ensure/update-deps-edn`** (lines 100-127 of ensure.clj)
- Updates module `deps.edn` files
- Rewrites `:local/root` paths for dependencies

---

## Agent 2: XML Parsing Research

### Available XML Parsing Options

**RECOMMENDED: Built-in `clojure.xml`**
- Already available in Clojure core (no dependencies needed)
- Perfect fit for this project's minimal dependency approach
- Works seamlessly with `babashka.curl`'s stream responses
- Simple API: `(xml/parse (:body http-response))`

### Maven Metadata XML Structure

```xml
<metadata>
  <versioning>
    <latest>253.25908.13-EAP-SNAPSHOT</latest>
    <versions>
      <version>242-EAP-SNAPSHOT</version>
      <version>253.20558.43-EAP-SNAPSHOT</version>
      <version>253.25908.13-EAP-SNAPSHOT</version>
    </versions>
  </versioning>
</metadata>
```

**Key insight:** The `<latest>` tag provides the most recent version without needing to parse version numbers.

### Code Pattern for Parsing

```clojure
(require '[clojure.xml :as xml])
(require '[babashka.curl :as curl])

(defn get-versions [metadata]
  (->> metadata
       :content
       (filter #(= :versioning (:tag %)))
       first
       :content
       (filter #(= :versions (:tag %)))
       first
       :content
       (map :content)
       (map first)))

(defn fetch-maven-metadata [repo]
  (let [url (str "https://www.jetbrains.com/intellij-repository/"
                 repo "/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml")
        resp (curl/get url {:as :stream})]
    (when (= 200 (:status resp))
      (xml/parse (:body resp)))))
```

### Version Matching Logic

**Challenge:** Convert short versions like `"253-eap"` to full versions like `"253.25908.13-EAP-SNAPSHOT"`

**Strategy:**
1. Extract prefix from short version (e.g., "253" from "253-eap")
2. Check if `<latest>` matches the prefix - if yes, use it (most reliable)
3. Otherwise, filter versions list for matches
4. Take the last (latest) specific build from filtered list

**Implementation:**
```clojure
(defn match-eap-version [short-version versions latest]
  (let [prefix (-> short-version str/lower-case (str/replace #"-eap$" ""))
        simple-version (str prefix "-EAP-SNAPSHOT")
        matches (->> versions
                     (filter #(str/starts-with? (str/lower-case %) prefix))
                     (filter #(or (str/includes? (str/lower-case %) "eap-snapshot")
                                  (str/includes? (str/lower-case %) "eap-candidate")))
                     (remove #(= (str/lower-case %) (str/lower-case simple-version))))]
    (or
      (when (and latest (str/starts-with? (str/lower-case latest) prefix))
        latest)
      (last matches)
      simple-version)))
```

---

## Agent 3: Test Pattern Analysis

### Current Test Structure

**File: `/Users/colin/dev/plugin-dev-tools/test/plugin_dev_tools/ensure_test.clj`**

**Testing Patterns:**
- **Pure unit tests** for utility functions (path construction, URL building)
- **File-based integration tests** for deps.edn manipulation
- **No HTTP mocking** - tests focus on logic rather than network calls

### Key Patterns

**Path/URL Construction Tests:**
```clojure
(deftest test-sdk-url
  (testing "constructs correct SDK URL for releases"
    (let [version "2023.1.1"
          expected "https://www.jetbrains.com/intellij-repository/releases/..."
          actual (ensure/sdk-url "releases" version)]
      (is (= expected actual)))))
```

**File System Tests:**
- Write temp file → Transform → Read and verify
- Location: Uses `/tmp/` for temporary test files
- Verification: Both string regex matching and EDN parsing

### Recommended Testing Approach

**For XML functionality:**

1. **Create pure parsing function** - test with XML string fixtures
2. **Test URL construction** - follow `test-sdk-url` pattern
3. **Keep HTTP calls isolated** - don't test network I/O directly
4. **Use static XML samples** - captured from real JetBrains responses
5. **Test error cases** - malformed XML, missing tags, etc.

**Example test structure:**
```clojure
(def sample-maven-metadata-snapshot
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <metadata>
     <versioning>
       <latest>253.20558.43-EAP-SNAPSHOT</latest>
     </versioning>
   </metadata>")

(deftest test-parse-latest-version
  (testing "extracts snapshot version from XML"
    (is (= "253.20558.43-EAP-SNAPSHOT"
           (ensure/parse-latest-version sample-maven-metadata-snapshot)))))
```

---

## Agent 4: Version Mapping Specification

### Full Metadata URLs

**Snapshots Repository (EAP builds):**
```
https://www.jetbrains.com/intellij-repository/snapshots/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml
```

**Releases Repository (Official releases):**
```
https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml
```

### Short Version Format Examples

**EAP/Snapshot Short Versions:**
- `"latest-eap"` - Latest EAP snapshot
- `"253-eap"` - Latest build in branch 253
- `"2025.3-eap"` - Latest build in branch 253 (marketing version format)

**Release Short Versions:**
- `"latest"` - Latest official release
- `"2024.3"` - Latest patch in 2024.3.x series
- `"2024.3.1"` - Exact version 2024.3.1

### Branch Number to Marketing Version Mapping

**Pattern:** `XYZ` → `20Y.Z`

Where:
- `X` = Always 2 (for versions 2024 onwards)
- `Y` = Last digit of the year (4 for 2024, 5 for 2025)
- `Z` = Minor version number

**Examples:**
- Branch `253` → Marketing `2025.3`
- Branch `252` → Marketing `2025.2`
- Branch `251` → Marketing `2025.1`
- Branch `243` → Marketing `2024.3`

### Version Matching Algorithm

**For EAP/Snapshot Versions (ends with `-eap`):**

1. Fetch `snapshots/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml`
2. Parse XML to extract `<versions>` list
3. Special case - "latest-eap": Return the `<latest>` tag value directly
4. Extract branch number (convert marketing version if needed)
5. Filter versions matching the branch
6. Sort and select the latest

**For Release Versions (no `-eap` suffix):**

1. Fetch `releases/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml`
2. Parse XML to extract `<versions>` list
3. Special case - "latest": Return the `<release>` or `<latest>` tag value
4. Check for exact match first
5. Find latest with matching prefix

### Test Cases

**Snapshot/EAP Resolution:**
| Short Version | Branch | Full Version |
|---------------|--------|--------------|
| `"latest-eap"` | N/A | `"253.25908.13-EAP-SNAPSHOT"` |
| `"253-eap"` | 253 | `"253.25908.13-EAP-SNAPSHOT"` |
| `"2025.3-eap"` | 253 | `"253.25908.13-EAP-SNAPSHOT"` |

**Release Resolution:**
| Short Version | Full Version |
|---------------|--------------|
| `"latest"` | `"2025.2.3"` |
| `"2024.3"` | `"2024.3.7"` |
| `"2024.3.1"` | `"2024.3.1"` |
