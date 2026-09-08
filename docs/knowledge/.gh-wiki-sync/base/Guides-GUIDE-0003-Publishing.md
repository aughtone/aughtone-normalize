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

**Pushing to `master` is what publishes.** Nothing else does. That push triggers `.github/workflows/publish.yml`, which tags the commit, creates a GitHub release, and uploads to Maven Central — so `master` should only ever receive a finished, version-bumped release.

1. **Start the release branch** from `develop` — `release/v<version>`.
2. **Bump the version** in `gradle/libs.versions.toml` — `versionName` under `[versions]`. Both modules read it. **Do this before `master` sees the merge**: the workflow greps that exact line to derive the tag name, so whatever the file says is what gets tagged and published.
3. **Move the `CHANGELOG.md` entries** from `## [Unreleased]` into a new dated version heading, and update the link definitions at the bottom of the file.
4. **Merge the release branch into `master`**, and back into `develop` so the version bump and changelog are not stranded on a branch.
5. **Push `master`.** That push is the trigger. Push `develop` and any tags too.

The workflow then creates the GitHub release with generated notes and runs `./gradlew publishToMavenCentral` with `automaticRelease = true`, so the staging repository closes and releases without a manual step in the Central portal.

Publishing a GitHub release by hand also works and runs the publish job on its own — useful for re-running a failed upload without another merge.

## Before you merge to master

**The secrets must already exist.** The workflow creates the tag and a public, non-draft GitHub release *before* it attempts the upload. If the credentials are missing the upload fails but the tag and release remain, advertising a version that never reached Central — recovering means deleting both and re-cutting. `gh secret list -R aughtone/aughtone-normalize` confirms them without revealing values.

**The release branch gets no CI.** `test.yml` runs on `develop` only, matching the other projects in the family, so nothing validates a release branch between leaving `develop` and publishing from `master`. Run `./gradlew check` locally before merging, or add `release/**` to the test workflow's triggers.

## After publishing

Tell the consumers the final coordinate, policy name and policy id. A consumer that has been developing against a draft id will have the wrong value compiled in, and the mismatch does not fail loudly — it just stops matching. [RAD-0001](Research-RAD-0001-Identifier-And-Text-Normalization) records who needs telling and what they were previously given.
