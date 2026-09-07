# Publishing a Release

GUIDE-0003 · 2026-09-07
Keywords: publish to maven central, cut a release, release secrets, signing key, version bump, publishToMavenLocal, why did the publish workflow not run, GPG

How a version gets from `develop` to Maven Central.

## Prerequisites, one time

The publish workflow needs five repository secrets. Without them it fails at the publish step:

| Secret | Used for |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Sonatype / Central portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype / Central portal token password |
| `SIGNING_KEY_ID` | GPG key id |
| `SIGNING_PASSWORD` | GPG key passphrase |
| `GPG_KEY_CONTENTS` | the armoured private key |

These are credentials and are set by someone with repository admin access, in the repository's own settings — never committed, never pasted into an issue or a conversation. `gh secret list -R aughtone/aughtone-normalize` shows which are present without revealing values.

## Cutting a release

1. **Bump the version** in `gradle/libs.versions.toml` — `versionName` under `[versions]`. Both modules read it, and the release workflow greps this exact line for the tag name, so nothing else needs editing.
2. **Move the `CHANGELOG.md` entries** from `## [Unreleased]` into a new dated version heading.
3. **Merge `develop` into `master`.** The push to `master` is what triggers publication.

The workflow then tags `v<versionName>`, creates a GitHub release with generated notes, and runs `./gradlew publishToMavenCentral` with `automaticRelease = true` — so the staging repository closes and releases without a manual step in the Central portal.

`.github/workflows/publish.yml` triggers on **`master`**. This repository has no `main` branch; if one is ever created, the trigger has to be revisited rather than assumed.

## Verifying before you push

A full publish can be rehearsed locally without credentials:

```bash
./gradlew publishToMavenLocal -Pskip-signing
```

The `skip-signing` property is checked in each module's `mavenPublishing` block and skips `signAllPublications()`. The artifacts land in `~/.m2/repository/io/github/aughtone/normalize/` where a consuming project can resolve them via `mavenLocal()` — which is the honest way to check that a coordinate, a POM and an artifact set are what you meant them to be.

## After publishing

Tell the consumers the final coordinate, policy name and policy id. A consumer that has been developing against a draft id will have the wrong value compiled in, and the mismatch does not fail loudly — it just stops matching. [RAD-0001](../research/RAD-0001-identifier-and-text-normalization.md) records who needs telling and what they were previously given.
