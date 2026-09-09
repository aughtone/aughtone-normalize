# Aught One Normalize

[![Maven Central](https://img.shields.io/maven-central/v/io.github.aughtone.normalize/email.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.aughtone.normalize/email)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Kotlin Multiplatform normalization suite — deterministic, byte-stable canonical forms for hashing and matching identifiers and text.

The point is one property: **the same input produces the same canonical bytes, on any platform, at any time.** If you hash a value into a token and throw the original away — blind tokenization, contact discovery, breach-safe matching — a one-byte difference between two platforms is not a degraded match, it is an undetectable miss. This library exists to make that impossible.

It works just as well for ordinary normalization (search keys, dedupe, display) through the same interface.

## 📦 Modules

`0.0.1` ships email normalization. Phone, domain/URL and Unicode normalizers are designed but not yet built — see the [roadmap](docs/knowledge/research/RAD-0001-identifier-and-text-normalization.md#recommendation).

| Module | Coordinate | What it does |
|---|---|---|
| `:email` | `io.github.aughtone.normalize:email` | the byte-stable email normalizer, with named frozen policies and typed, value-free errors |
| `:common` | `io.github.aughtone.normalize:common` | the shared `Normalized` contract — the canonical string plus the identity of the policy that produced it |

## 📥 Installation

`:email` exposes `:common` transitively, so depending on it alone is enough.

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.aughtone.normalize:email:0.0.1")
        }
    }
}
```

Or with a version catalog:

```toml
# gradle/libs.versions.toml
[versions]
aughtone-normalize = "0.0.1"

[libraries]
aughtone-normalize-email = { module = "io.github.aughtone.normalize:email", version.ref = "aughtone-normalize" }
```

**Targets:** JVM, Android, iOS (`arm64`, `x64`, `simulatorArm64`), JS (browser), wasmJs (browser), Linux x64.

## ⚖️ What is stable, and what is not

These are deliberately different promises, and the distinction matters more here than in most libraries:

- **A published policy's output is frozen forever.** `EmailPolicy.ByteStableV1` (id `email.byte-stable`, version 1) will produce the same bytes for the same input in every future release. A rules change mints a *new* policy version; it is never an in-place improvement, because that would orphan every token already derived under the old one. Store `policyId` and `policyVersion` beside anything you derive.
- **The Kotlin API is not stable yet.** At `0.0.x` names and signatures may still move. Pin an exact version.

## 📚 Documentation

Start at [docs/README.md](docs/README.md), which explains how the documentation is organized. The knowledge base itself lives in [docs/knowledge/](docs/knowledge/):

- 📐 [Specifications](docs/knowledge/specifications/): how the suite is built and the standards it is held to.
- 🧭 [Architecture Decision Records](docs/knowledge/decisions/): hard-to-reverse choices and why the alternatives lost.
- 🔬 [Research](docs/knowledge/research/): investigations and designs still being worked out.
- 📖 [Developer Guides](docs/knowledge/guides/): building, extending and releasing this project.
- 📜 [Changelog](CHANGELOG.md): history of changes and release notes.

Work is tracked as issues rather than documents — see [WORKFLOW.md](WORKFLOW.md).

## 🚀 Quick Usage

### Email Normalization (`:email`)
```kotlin
import io.github.aughtone.normalize.email.normalizeEmail
import io.github.aughtone.normalize.email.EmailPolicy
import io.github.aughtone.types.outcome.Outcome

when (val outcome = normalizeEmail(value, EmailPolicy.ByteStableV1)) {
    is Outcome.Success -> {
        val normalized = outcome.data          // NormalizedEmail
        hash(normalized.canonical)             // stable across platforms and builds
        // persist normalized.policyId + normalized.policyVersion beside the hash
    }
    is Outcome.Failure -> {
        val reason = outcome.exception         // a typed, value-free EmailNormalizationError
    }
}
```

## 🛠️ Contributing

Read [Getting Started](docs/knowledge/guides/DOC-0003-getting-started.md) first, then [AGENTS.md](AGENTS.md) if you are an agent. The one rule that matters more than the rest: **a published policy's canonical output never changes in place** — see [Normalization Suite Structure](docs/knowledge/specifications/DOC-0001-normalization-suite.md).

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
