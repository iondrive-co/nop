# Release and upgrade process

- Deliver application fixes through published GitHub releases. Wait for the release and its artifacts to become available; the user upgrades through their usual release process.
- Do not manually install or stage a release for the user, repoint their installation symlink, replace installed JARs, or substitute a local build for their release installation. This applies even to artifacts downloaded from a published GitHub release.
- Use isolated configuration and data directories when testing local builds. Do not launch local builds against the user's live configuration or ask them to restart into a manually staged release.
