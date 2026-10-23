# DDS Manager — Official Multi-platform Release

This is **Public-only release infrastructure**. Develop and review it on
DDS-Manager-Dev, then transfer only approved source files to DDS-Manager
Public. Do **not** copy the private Dev `.github/workflows/release.yml`,
private Git history, or internal planning documents into Public.

## Trigger and separation

* Tag push `vX.Y.Z` to **Public**: test six Velocity API targets independently,
  build one Java 17 Shadow JAR, verify it, create one GitHub Release, and then
  run **Hangar, Modrinth and CurseForge independently**.
* Manual `workflow_dispatch`: choose an **existing immutable Tag** and
  `all` / `hangar` / `modrinth` / `curseforge`. It downloads the release
  artifact and SHA256 manifest; it **never recompiles, changes a Tag, or
  replaces a GitHub Release file**.
* Publishing jobs depend only on `prepare`, never on one another. A failed
  Hangar upload does **not** cancel Modrinth or CurseForge. Individual job
  statuses and the final summary expose failures rather than masking them.
  Retries may only target the affected platform.

Public repository secrets:

* `HANGAR_API_TOKEN`, `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN`

Public repository variables:

* `HANGAR_PROJECT=DDS-Manager`
* `MODRINTH_PROJECT_SLUG=dds-manager`
* `CURSEFORGE_PROJECT_ID=1697972`

**Do not** put API keys in variables, commits, printed commands or PR logs.
Rotate any API keys that were previously exposed as readable variables.

## Required approval gates

1. Merge only the reviewed Public-source changes from Dev. Keep the previous
   0.7.0 incremental-snapshot rule: no private ancestry, no force pushes,
   and no changes to old `v0.6.x` Tags or Releases.
2. Verify `LICENSE` and reconcile public platform license labels: the
   repository is GPL-3.0, while some platform descriptions may differ.
3. Review `scripts/release-platforms.json`. The Modrinth
   `minecraft_versions` list and CurseForge `curseforge_version_ids`
   list are intentionally **empty** until their compatibility claims are
   approved. Each platform fails its own preflight without affecting the
   other providers. CurseForge uses **numeric IDs**, validated against
   its version catalog; duplicate version names are not safe identifiers.
   Six Velocity API compilation targets do not prove every listed
   Minecraft/Velocity runtime combination. Hangar's Velocity versions
   also require release-owner approval before Tag creation.
4. Check that the official release notes exist at `docs/releases/X.Y.Z.md`;
   tag name equals the version in `gradle.properties`; the Tag commit
   belongs to Public main; official Shadow JAR is
   `DDS-Manager-X.Y.Z.jar`.
5. Run the offline unit tests and inspect CI. Test Hangar project permission,
   CurseForge game-version tags/approval, Modrinth loader/platform metadata
   and the relevant real-server acceptance scenarios separately. **Do not
   create an official Tag for a dry-run.**
6. After explicit release approval, create and push the Public `vX.Y.Z` Tag.
   GitHub Release is created only after all six build/test matrix entries pass.
   A platform upload failure leaves the successful platforms untouched.

## Local preflight (no upload)

```powershell
py -3 -m unittest discover -s scripts/tests -p "test_release_platform.py" -v
py -3 scripts/publish-platform.py --platform hangar --version 0.7.0 `
  --jar dist/DDS-Manager-0.7.0.jar `
  --sha256 dist/DDS-Manager-0.7.0.jar.sha256 `
  --changelog docs/releases/0.7.0.md --dry-run
```

The standalone publisher uses Hangar's authenticated version API, Modrinth's
`/v2/version` API, and CurseForge's `upload-file` endpoint. The request
construction is adapted from CDA's uploader, **but excludes Fabric/Carpet
dependencies**, multiple MC-target JARs, and Paper loader declarations.

## Repeatability / known limitations

* **Modrinth:** treats an existing version as verified only when its
  primary file has the same filename and SHA256 as the GitHub Release.
  It resolves the project slug to the canonical Modrinth project ID for
  new uploads.
* **Hangar:** an existing version without verified binary hash is a
  platform-local error requiring manual consistency review, never a
  silent success or overwrite.
* **CurseForge:** numeric version IDs are verified against the live
  catalog; an explicit duplicate rejection still fails closed because
  remote file integrity is not established. Its API is not a
  transactional cross-platform deployment.
* Missing token, unavailable provider, unauthorized key, unapproved game
  metadata and failed preflight are **errors for that platform**, not global
  cancellations. The workflow overall fails if any requested platform fails.
* Early released versions using the old Public layout may not contain the
  composite uploader action; the manual retry flow applies to official
  releases created **after** this workflow is deployed.

## Review observations (2026-10-23)

* Public `main` already contains the 0.7.0 source and release notes,
  but the latest verified public GitHub Release was 0.6.2. The earlier
  snapshot manifest's fixed HEADs have moved and are not a safe basis
  for replay. Prepare a **new, reviewed incremental Public PR** for
  the release infrastructure; do not blindly re-apply the old manifest.
* The previous global non-empty `minecraft_versions` gate accidentally
  blocked Hangar/CurseForge as well as Modrinth. Validation is now
  provider-scoped; **one provider's error never cancels its peers**.
  An invalid JSON document or mismatched official source/version can
  still block the common GitHub Release preparation, by design.
* The official release YAML is restricted to `DDS-Manager` Public
  and does not run upload tasks in Dev. Dev tests are offline only.
* The existing Modrinth project page advertises `LGPL-3.0-or-later`
  while the repository LICENSE and Hangar/CurseForge use GPL-3.0.
  This **external project metadata must be corrected or reviewed by the
  owner**, not silently changed in the uploader.
* No API tokens, remote upload results, runtime integration tests or
  actual user release approval can be inferred from offline CI.
