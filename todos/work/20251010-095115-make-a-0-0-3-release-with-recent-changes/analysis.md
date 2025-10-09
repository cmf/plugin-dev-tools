# Release Process Analysis

## Current State

**Existing Releases:**
- 0.0.1 (initial release)
- 0.0.2 (commit ec31f70 - "Organise code into sub-namespaces. Add function for maintaining Kotlin versions")

**Commits Since 0.0.2:**
1. `b4f6d83` - Add comprehensive tests and improve code organization
2. `e8a67dd` - Add marketing version resolution for IntelliJ SDK downloads
3. `93ffaee` - Add marketplace plugin dependency support to plugin.edn

## Release Infrastructure

**From CLAUDE.md:**
> This project is usually consumed using deps.edn git deps, so releases need a tag to be pushed to origin.

**Observations:**
- No CHANGELOG file exists
- No version file exists
- Simple tagging approach - just git tags
- Tags are pushed to origin for consumption via deps.edn

## Release Process

Based on the existing tags and project documentation, the release process is:

1. Create an annotated git tag with version number (e.g., `0.0.3`)
2. Push the tag to origin

## Version Numbering

Following semantic versioning pattern observed:
- 0.0.1 - Initial release
- 0.0.2 - Added Kotlin version management
- 0.0.3 - Marketing version resolution + marketplace plugin support (next)

The project is in early development (0.0.x), incrementing the patch version for each release.

## What's New in 0.0.3

**Major Features:**
1. **Marketing Version Resolution** - Automatically resolve marketing versions (e.g., "2025.3-eap") to full versions by fetching maven-metadata.xml from JetBrains repository
2. **Marketplace Plugin Support** - Allow projects to specify IntelliJ marketplace plugins in plugin.edn with automatic download and configuration

**Testing Improvements:**
- Comprehensive test suite added
- Better code organization

These are significant features that warrant a new release.
