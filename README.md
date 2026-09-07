# Aughtone Normalize

Kotlin Multiplatform normalization suite — deterministic, byte-stable canonical forms for hashing and matching identifiers and text (email, phone, domain/URL, Unicode).

## 📦 Core Modules

- **`:common`** (`io.github.aughtone.normalize:common`): the shared `Normalized` contract — the canonical string plus the identity of the policy that produced it — reported by every normalizer's result.
- **`:email`** (`io.github.aughtone.normalize:email`): the byte-stable email normalizer, with named frozen policies and typed, value-free errors.

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
    is Outcome.Error -> {
        val reason = outcome.exception         // a typed, value-free EmailNormalizationError
    }
}
```

## 🛠️ Contributing

Read [Getting Started](docs/knowledge/guides/GUIDE-0001-getting-started.md) first, then [AGENTS.md](AGENTS.md) if you are an agent. The one rule that matters more than the rest: **a published policy's canonical output never changes in place** — see [Normalization Suite Structure](docs/knowledge/specifications/SPEC-0001-normalization-suite.md).
