# Aughtone Normalize

Kotlin Multiplatform normalization suite — deterministic, byte-stable canonical forms for hashing and matching identifiers and text (email, phone, domain/URL, Unicode).

## 📦 Core Modules

- **`:common`** (`io.github.aughtone.normalize:common`): the shared `Normalized` contract — the canonical string plus the identity of the policy that produced it — reported by every normalizer's result.
- **`:email`** (`io.github.aughtone.normalize:email`): the byte-stable email normalizer, with named frozen policies and typed, value-free errors.

This project follows a specialized 5-sector documentation hierarchy.

## 📚 Documentation Sectors
- 📐 [Architecture](docs/ARCH.md): Engineering rules and design patterns.
- 🧠 [Functional Specifications](docs/SPEC.md): Business logic and domain constraints.
- 🎨 [Design & UI](docs/DESIGN.md): Presentation layer and user stories.
- 📋 [Acceptance Criteria](docs/ACs/README.md): Success outcomes and verification.
- 📖 [Developer Guide](docs/DEVELOPER.md): Environment setup and onboarding.
- 📜 [Changelog](CHANGELOG.md): History of changes and release notes.

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
