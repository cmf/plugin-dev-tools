# Make a 0.0.3 release with recent changes
**Status:** Done
**Agent PID:** 49833

## Original Todo
- Make a 0.0.3 release with recent changes

## Description
Creating a 0.0.3 release to publish recent improvements including marketing version resolution and marketplace plugin dependency support. The release will be tagged and pushed to origin for consumption via deps.edn git dependencies.

Changes since 0.0.2:
- Marketing version resolution (automatically resolve "2025.3-eap" to full versions)
- Marketplace plugin dependency support (download and configure external plugins)
- Improved test coverage and code organization

*Read [analysis.md](./analysis.md) in full for detailed codebase research and context*

## Implementation Plan
- [x] Verify working directory is clean and all changes are committed
- [x] Create annotated git tag 0.0.3 with release notes
- [x] Push tag to origin
- [x] Verify tag appears on GitHub/remote repository

## Notes
**Credential Issue Resolved:**
The initial push failed because Git Credential Manager Core had cached credentials for the "cursive-ghost" user. Cleared cached credentials using:
```bash
git credential-manager-core erase <<EOF
protocol=https
host=github.com
EOF
```
This allowed git to authenticate correctly as the "cmf" user.
