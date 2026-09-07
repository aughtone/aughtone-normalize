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

**Publishing is deliberate, not a side effect of merging.** The automatic push-to-`master` trigger in `.github/workflows/publish.yml` is commented out, and the workflow runs on a **published GitHub release** instead.

1. **Bump the version** in `gradle/libs.versions.toml` — `versionName` under `[versions]`. Both modules read it, so nothing else needs editing.
2. **Move the `CHANGELOG.md` entries** from `## [Unreleased]` into a new dated version heading.
3. **Merge `develop` into `master`** and push. Nothing publishes at this point — this is just getting the release commit onto the release branch.
4. **Create the release.** Tag the merge commit `v<versionName>` and publish a GitHub release from it, which is what triggers the workflow:

```bash
gh release create v0.0.1 --title "Release 0.0.1" --generate-notes
```

The workflow's publish job then runs `./gradlew publishToMavenCentral` with `automaticRelease = true`, so the staging repository closes and releases without a manual step in the Central portal.

## Why it is wired this way

The automatic trigger creates the tag and a public, non-draft GitHub release **before** it attempts the upload. With the secrets missing that leaves a `v0.0.1` tag and a release advertising a version that never reached Central, and recovering means deleting both and re-cutting. Requiring an explicit release means the tag is only ever created by someone who meant to create it.

To restore the automatic behaviour once the secrets are in place, uncomment the `push: branches: [ "master" ]` trigger. The `create-release` job exists only for that path and is skipped on a release event.

This repository has no `main` branch. If one is ever created, the trigger has to be revisited rather than assumed.

## Verifying before you push

A full publish can be rehearsed locally without credentials:

```bash
./gradlew publishToMavenLocal -Pskip-signing
```

The `skip-signing` property is checked in each module's `mavenPublishing` block and skips `signAllPublications()`. The artifacts land in `~/.m2/repository/io/github/aughtone/normalize/` where a consuming project can resolve them via `mavenLocal()` — which is the honest way to check that a coordinate, a POM and an artifact set are what you meant them to be.

## After publishing

Tell the consumers the final coordinate, policy name and policy id. A consumer that has been developing against a draft id will have the wrong value compiled in, and the mismatch does not fail loudly — it just stops matching. [RAD-0001](../research/RAD-0001-identifier-and-text-normalization.md) records who needs telling and what they were previously given.
