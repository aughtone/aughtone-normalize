# Publishing a Release

DOC-0005 · 2026-09-07
Keywords: publish to maven central, cut a release, release secrets, signing key, version bump, publishToMavenLocal, why did the publish workflow not run, GPG

How a version gets from `develop` to Maven Central.

## Prerequisites, one time

The publish workflow needs five secrets. Without them it fails at the publish step:

| Secret | Used for |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Sonatype / Central portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype / Central portal token password |
| `SIGNING_KEY_ID` | GPG key id |
| `SIGNING_PASSWORD` | GPG key passphrase |
| `GPG_KEY_CONTENTS` | the armoured private key |

These live as **organization** secrets on the GitHub org, not as repository secrets — they are shared across every `aughtone-*` library rather than set per repo. They are credentials: never committed, never pasted into an issue or a conversation.

**`gh secret list -R aughtone/aughtone-normalize` will show nothing, and that is not a problem.** It lists only repository-level secrets. To see the org secrets shared with this repo:

```bash
gh api /repos/aughtone/aughtone-normalize/actions/organization-secrets --jq '.secrets[].name'
```

**The org is on the GitHub Free plan, where organization secrets resolve only for PUBLIC repositories.** A private repo sees them listed by that API and still gets empty values at runtime, with no error — the step just behaves as though the secret were unset. So a repository in this org must be public before it can publish.

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

**The credentials must actually resolve, which for this org means the repository must be public.** The workflow creates the tag and a public, non-draft GitHub release *before* it attempts the upload. If the credentials come back empty the upload fails but the tag and release remain, advertising a version that never reached Central — recovering means deleting both and re-cutting. See the prerequisites above for how to check, and why the obvious check misleads.

**The release branch gets no CI.** `test.yml` runs on `develop` only, matching the other projects in the family, so nothing validates a release branch between leaving `develop` and publishing from `master`. Run `./gradlew check` locally before merging, or add `release/**` to the test workflow's triggers.

## After publishing

Tell the consumers the final coordinate, policy name and policy id. A consumer that has been developing against a draft id will have the wrong value compiled in, and the mismatch does not fail loudly — it just stops matching. [RAD-0001](Research-RAD-0001-Identifier-And-Text-Normalization) records who needs telling and what they were previously given.
