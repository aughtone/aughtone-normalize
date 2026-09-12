# Aught One Normalize

[![Maven Central](https://img.shields.io/maven-central/v/io.github.aughtone.normalize/email.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.aughtone.normalize/email)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Kotlin Multiplatform normalization suite — deterministic, byte-stable canonical forms for hashing and matching identifiers and text.

The point is one property: **the same input produces the same canonical bytes, on any platform, at any time.** If you hash a value into a token and throw the original away — blind tokenization, contact discovery, breach-safe matching — a one-byte difference between two platforms is not a degraded match, it is an undetectable miss. This library exists to make that impossible.

It works just as well for ordinary normalization (search keys, dedupe, display) through the same interface.

## 📦 Modules

Every normalizer in the roster is built: email, credit-card/PAN, IBAN, IPv4, IPv6 and usernames in `:quodlibet`; the four Unicode forms in `:unicode`; hostnames, domains and URLs in `:ubilibet`; UTS-39 skeletons in `:confusables`; and phone numbers in `:phone`.

| Module | Coordinate | What it does |
|---|---|---|
| `:quodlibet` | `io.github.aughtone.normalize:quodlibet` | every normalizer that needs no lookup table and no external dependency: email, credit-card/PAN, IBAN, IPv4, IPv6 and usernames, each with named frozen policies and typed, value-free errors |
| `:unicode` | `io.github.aughtone.normalize:unicode` | NFC, NFD, NFKC and NFKD against tables frozen from a pinned Unicode release, never the platform's |
| `:ubilibet` | `io.github.aughtone.normalize:ubilibet` | every hostname and domain, ASCII included, under UTS-46 with Punycode — the full IDNA conformance suite passes on every target |
| `:confusables` | `io.github.aughtone.normalize:confusables` | UTS-39 skeletons for spoof detection, including the bidirectional algorithm the standard defines them through |
| `:phone` | `io.github.aughtone.normalize:phone` | phone numbers to E.164, with the region on the policy so a country code is never guessed |
| `:common` | `io.github.aughtone.normalize:common` | the shared `Normalized` contract, the policy identity grammar, and resolution of a stored id back to its policy |

## 📥 Installation

`:quodlibet` exposes `:common` transitively, so depending on it alone is enough.

**Moving from `0.0.1`?** The email normalizer was published as `io.github.aughtone.normalize:email:0.0.1` and now lives in `:quodlibet`. Change the coordinate; nothing else moves. The package, every type name, the canonical output and the policy versions are unchanged, so no stored value is affected. The one rename is `EmailPolicy.Lenient`, now `EmailPolicy.ByteStableV1Lenient`, whose `id` became `email.byte-stable+lenient`. `email:0.0.1` stays on Maven Central.

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.aughtone.normalize:quodlibet:<version>")
        }
    }
}
```

Or with a version catalog:

```toml
# gradle/libs.versions.toml
[versions]
aughtone-normalize = "<version>"

[libraries]
aughtone-normalize-quodlibet = { module = "io.github.aughtone.normalize:quodlibet", version.ref = "aughtone-normalize" }
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
- 📌 [Reference](docs/knowledge/reference/): settled facts kept close, including what this suite has decided not to build.
- 📜 [Changelog](CHANGELOG.md): history of changes and release notes.

Work is tracked as issues rather than documents — see [WORKFLOW.md](WORKFLOW.md).

## 🚀 Quick Usage

### Email Normalization (`:quodlibet`)
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

### Unicode Normalization (`:unicode`)
```kotlin
import io.github.aughtone.normalize.unicode.TextPolicy
import io.github.aughtone.normalize.unicode.normalizeText

when (val outcome = normalizeText(value, TextPolicy.NfcU17)) {
    is Outcome.Success -> outcome.data.canonical    // identical on every platform, forever
    is Outcome.Failure -> outcome.exception         // a typed, value-free TextNormalizationError
}
```

`NfcU17` and `NfdU17` are canonical and lossless. `NfkcU17` and `NfkdU17` are compatibility forms and deliberately lossy — a ligature becomes its letters and cannot be turned back — so they are useful for search and wrong for a token you expect to round-trip. The tables are frozen against Unicode 17.0.0 and shipped with the library, so a new OS release cannot change what your application produces; a new Unicode release is a new policy, `NfcU18`, never a changed `NfcU17`.

### Hostname and Domain Normalization (`:ubilibet`)
```kotlin
import io.github.aughtone.normalize.ubilibet.DomainPolicy
import io.github.aughtone.normalize.ubilibet.normalizeDomain

when (val outcome = normalizeDomain(value, DomainPolicy.AsciiU17)) {
    is Outcome.Success -> outcome.data.canonical    // "café.fr" -> "xn--caf-dma.fr"
    is Outcome.Failure -> outcome.exception         // a typed, value-free DomainNormalizationError
}
```

Every hostname goes through the same function, ASCII included: a second, simpler rule for ASCII names would produce identical bytes under a different policy identity, which is a mismatch waiting to happen. `AsciiU17` applies every UTS-46 check; `AsciiU17Lenient` relaxes hyphen placement, the STD3 character restriction and DNS length, and keeps the bidi and joiner rules — those exist to stop a name that displays as one thing and resolves as another, which is not something leniency should buy.

### Phone Numbers (`:phone`)
```kotlin
import io.github.aughtone.normalize.phone.PhonePolicy
import io.github.aughtone.normalize.phone.normalizePhone

normalizePhone("+1 (212) 555-0123", PhonePolicy.E164)               // "+12125550123"
normalizePhone("(212) 555-0123", PhonePolicy.e164ForRegion("us"))   // "+12125550123"
```

**The region travels on the policy, and nothing is ever guessed.** `E164` accepts only input carrying its own country code; `e164ForRegion` reads national-format input against a region you named. A guessed country code does not fail loudly — it produces a valid-looking token for a *different number*, and by then the input is gone. One consequence worth knowing: `phone.e164` and `phone.e164+region-ca` produce identical bytes for input already in E.164 form and are still **different identities**, so systems that must match each other have to agree on the same constant.

### Spoof Detection (`:confusables`)
```kotlin
import io.github.aughtone.normalize.confusables.ConfusablePolicy
import io.github.aughtone.normalize.confusables.normalizeSkeleton

val a = normalizeSkeleton("paypal", ConfusablePolicy.SkeletonU17)
val b = normalizeSkeleton("раypal", ConfusablePolicy.SkeletonU17)   // Cyrillic р and а
// equal canonical values: the second is a lookalike of the first
```

**A skeleton is a check, never an account key.** It is deliberately many-to-one — that is what makes a lookalike collide with its target — so two genuinely different users can share one. Derive your identity from the plain value, compute the skeleton beside it, and use a collision to *flag* something for review.

It is also a `NormalizationStep`, so a table-free normalizer can take it from a caller: `normalizeUsername(value, UsernamePolicy.Basic, listOf(ConfusablePolicy.SkeletonU17))` produces the policy identity `username.basic+skeleton.u17`, which never matches the plain `username.basic`.

### Resolving a Stored Policy (`:common`)

The id and version stored beside a hash resolve back to the policy that produced them, so new values can be normalized the same way as the ones already in a store — and so a policy can be named in a configuration file instead of hardcoded.

```kotlin
import io.github.aughtone.normalize.email.EmailPolicy
import io.github.aughtone.normalize.email.normalizeEmail
import io.github.aughtone.normalize.quodlibet.QuodlibetPolicies
import io.github.aughtone.types.outcome.Outcome

// policyId and policyVersion were stored next to the hash when the first value was normalized
when (val outcome = QuodlibetPolicies.resolve(policyId, policyVersion)) {
    is Outcome.Success -> normalizeEmail(newValue, outcome.data as EmailPolicy)
    is Outcome.Failure -> error(outcome.exception.message ?: "unknown policy")
}
```

Resolution is explicit: combine the resolvers of the modules you depend on with `+`. There is no global registry and no startup registration, and an unknown id, an unknown link or a version this build does not carry fails loudly rather than resolving to something close — a nearly-right policy silently derives bytes that match nothing already stored.

## 🛠️ Contributing

Read [Getting Started](docs/knowledge/guides/DOC-0003-getting-started.md) first, then [AGENTS.md](AGENTS.md) if you are an agent. The one rule that matters more than the rest: **a published policy's canonical output never changes in place** — see [Normalization Suite Structure](docs/knowledge/specifications/DOC-0001-normalization-suite.md).

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
