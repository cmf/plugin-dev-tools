# SDK Version Lookup Enhancement
**Status:** Done
**Agent PID:** 71789

## Original Todo
- Use short version (e.g. 2025.3-eap) from plugin.edn, and lookup full version from repo
  - If the version ends with `-eap`, use https://www.jetbrains.com/intellij-repository/snapshots/
  - Otherwise, use https://www.jetbrains.com/intellij-repository/releases/
  - Download metadata from <repo>/com/jetbrains/intellij/idea/ideaIU/maven-metadata.xml

## Description

Currently, users must specify the full IntelliJ IDEA version in `plugin.edn` (e.g., `"253.20558.43-EAP-SNAPSHOT"`). This enhancement will allow users to specify marketing versions instead:
- Release versions: `"2025.2"` (resolves to exact version or latest point release like `"2025.2.3"`)
- EAP versions: `"2025.3-eap"` (resolves to latest snapshot like `"253.25908.13-EAP-SNAPSHOT"`)

The implementation will:
- Fetch maven-metadata.xml from JetBrains repositories to resolve versions
- For releases: find the exact version or the most recent point release in that series
- For EAP: convert marketing version to branch number (2025.3 → 253) and find latest snapshot
- Determine repository (releases vs snapshots) based on `-eap` suffix
- Use built-in `clojure.xml` for parsing (no new dependencies)

*Read [analysis.md](./analysis.md) in full for detailed codebase research and context*

## Implementation Plan

- [x] Add XML parsing functions to ensure.clj
  - `maven-metadata-url` - construct metadata URL for releases or snapshots repo
  - `parse-maven-metadata` - parse XML and extract version list
  - `marketing-version->branch` - convert "2025.3" to "253"
- [x] Add version resolution functions to ensure.clj
  - `resolve-release-version` - find exact match or latest point release (e.g., "2025.2" → "2025.2.3")
  - `resolve-eap-version` - convert to branch and find latest snapshot (e.g., "2025.3-eap" → "253.25908.13-EAP-SNAPSHOT")
  - `resolve-idea-version` - dispatch to appropriate resolver based on `-eap` suffix
- [x] Modify download-sdk to use version resolution (src/plugin_dev_tools/ensure.clj:158-187)
  - Call `resolve-idea-version` before downloading
  - Use resolved full version for all SDK operations
- [x] Automated tests: Add test cases to test/plugin_dev_tools/ensure_test.clj
  - Test `marketing-version->branch` conversion (2025.3 → 253, 2024.3 → 243)
  - Test `parse-maven-metadata` with sample XML fixtures
  - Test `resolve-release-version` with mock version list
  - Test `resolve-eap-version` with mock version list
  - All tests passing (21 tests, 76 assertions, 0 failures)
- [x] User test: Create test plugin.edn and verify resolution
  - Test release version resolution (e.g., 2025.2)
  - Test EAP version resolution (e.g., 2025.3-eap)
  - Verified with live JetBrains repositories:
    - 2025.3-eap → 253.25908.13-EAP-SNAPSHOT ✓
    - 2025.2-eap → 252.26830.24-EAP-SNAPSHOT ✓
    - 2024.3 → 2024.3.4.1 ✓
    - 2025.1 → 2025.1.5.1 ✓

## Notes

### Implementation Summary

**Files Modified:**
- `src/plugin_dev_tools/ensure.clj` (lines 1-9, 30-127, 158-187)
  - Added `clojure.xml` to requires for XML parsing
  - Added `maven-metadata-url` function to construct repository metadata URLs
  - Added `marketing-version->branch` to convert marketing versions (2025.3) to branch numbers (253)
  - Added `parse-maven-metadata` to parse XML and extract version lists
  - Added `version-compare` for semantic version sorting
  - Added `resolve-release-version` to find latest point releases
  - Added `resolve-eap-version` to find latest EAP snapshots
  - Added `resolve-idea-version` as main entry point that fetches metadata and dispatches
  - Modified `download-sdk` to call `resolve-idea-version` and determine correct repository

- `test/plugin_dev_tools/ensure_test.clj` (lines 101-228)
  - Added comprehensive tests for all new functions
  - Includes XML fixtures for both releases and snapshots
  - Tests error handling for malformed XML
  - All tests passing

**Key Design Decisions:**
- Used built-in `clojure.xml` (no new dependencies required)
- Graceful fallback: if resolution fails, uses version as-is
- Repository selection now based on resolved version content (SNAPSHOT check)
- Removed try/fallback approach in download-sdk (now knows correct repo upfront)
- Semantic version sorting for release point releases
- Excludes CANDIDATE snapshots from EAP resolution

**Breaking Changes:**
- None - full backward compatibility maintained
- Existing full version strings continue to work unchanged
- New short versions are opt-in

**Additional Fixes:**
- Fixed lazy evaluation bug in core.clj (for → doseq) that prevented deps.edn updates
- Modified download-sdk to return resolved version
- Modified ensure-sdk to resolve version early and use it consistently
- Same lazy evaluation fix applied to ensure-kotlin
