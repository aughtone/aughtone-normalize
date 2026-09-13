# Aught One Normalize

[![Maven Central](https://img.shields.io/maven-central/v/io.github.aughtone.normalize/common.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.aughtone.normalize/common)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Kotlin Multiplatform normalization suite — deterministic, byte-stable canonical forms for hashing and matching identifiers and text.

The point is one property: **the same input produces the same canonical bytes, on any platform, at any time.** If you hash a value into a token and throw the original away — blind tokenization, contact discovery, breach-safe matching — a one-byte difference between two platforms is not a degraded match, it is an undetectable miss. This library exists to make that impossible.

It works just as well for ordinary normalization (search keys, dedupe, display) through the same interface.

> [!WARNING]
> **This suite is alpha (`0.0.x`), and its shape is still being worked out.** Policy ids, constant names and module boundaries may change between releases, and some changes will be breaking. Each one is listed in the [changelog](CHANGELOG.md). Once a release is published, a policy's canonical bytes never change in place. What may change during alpha is which policies exist and what they are called. Before you store a token derived under a policy, check the changelog for the release you depend on, and do not treat an id from an unreleased design as final.

## 📦 Modules

Every normalizer in the roster is built: email, credit-card/PAN, IBAN, IPv4, IPv6 and usernames in `:quodlibet`; configurable text normalization in `:unicode`; hostnames, domains and URLs in `:ubilibet`; UTS-39 skeletons in `:confusables`; and phone numbers in `:phone`.

| Module | Coordinate | What it does |
|---|---|---|
| `:quodlibet` | `io.github.aughtone.normalize:quodlibet` | every normalizer that needs no lookup table and no external dependency: email, credit-card/PAN, IBAN, IPv4, IPv6 and usernames, each with named frozen policies and typed, value-free errors |
| `:unicode` | `io.github.aughtone.normalize:unicode` | configurable text normalization — trim, spaces, case, case folding, NFC/NFD/NFKC/NFKD — over ASCII or against tables frozen from a pinned Unicode release, never the platform's |
| `:ubilibet` | `io.github.aughtone.normalize:ubilibet` | every hostname and domain, ASCII included, under UTS-46 with Punycode, and URLs — the full IDNA conformance suite passes on every target |
| `:confusables` | `io.github.aughtone.normalize:confusables` | UTS-39 skeletons for spoof detection, including the bidirectional algorithm the standard defines them through |
| `:phone` | `io.github.aughtone.normalize:phone` | phone numbers to E.164, with the region on the policy so a country code is never guessed |
| `:common` | `io.github.aughtone.normalize:common` | the shared `Normalized` contract, the policy identity grammar, and resolution of a stored id back to its policy |

## 📥 Installation

Each module is its own coordinate: depend on the normalizers you use and you carry nothing else. Every module exposes `:common` transitively, and `:ubilibet` and `:confusables` bring `:unicode` with them, so you never name those yourself.

The example below installs `:quodlibet`, which is the table-free bundle — email, PAN, IBAN, IPv4, IPv6 and usernames. Swap or add coordinates from the table above for the rest.

**Moving from `0.0.1`?** The email normalizer was published as `io.github.aughtone.normalize:email:0.0.1` and now lives in `:quodlibet`. Change the coordinate; nothing else moves. The package, every type name, the canonical output and the policy versions are unchanged, so no stored value is affected. The one rename is `EmailPolicy.Lenient`, now `EmailPolicy.ByteStableV1Lenient`, whose `id` became `email.byte-stable+lenient`. `email:0.0.1` stays on Maven Central.

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.aughtone.normalize:quodlibet:0.0.2")
        }
    }
}
```

Or with a version catalog:

```toml
# gradle/libs.versions.toml
[versions]
aughtone-normalize = "0.0.2"

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
normalizeEmail(value, EmailPolicy.ByteStableV1)
    .onSuccess { normalized ->
        // the hash is stable across platforms and builds; keep the policy identity beside it
        store(hash(normalized.canonical), normalized.policyId, normalized.policyVersion)
    }
    .onFailure { failure -> log(failure.exception) }   // a typed, value-free EmailNormalizationError

// or, where a failure needs no handling of its own
val canonical: String? = normalizeEmail(value, EmailPolicy.ByteStableV1).dataOrNull()?.canonical
```

### Text Normalization (`:unicode`)
```kotlin
import io.github.aughtone.normalize.unicode.TextPolicy
import io.github.aughtone.normalize.unicode.UnicodeRelease
import io.github.aughtone.normalize.unicode.normalizeText

// ASCII rules only: text+trim+lower. Names no Unicode release, so it never goes stale.
val field = TextPolicy { ascii { trim(); lowercase() } }

// Unicode rules against frozen Unicode 17 data: text.u17+trim+casefold+nfc
val caseless = TextPolicy(UnicodeRelease.U17) { unicode { trim(); casefold(); nfc() } }

normalizeText(value, caseless)
    .onSuccess { normalized -> store(normalized.canonical, normalized.policyId, normalized.policyVersion) }
    .onFailure { failure -> log(failure.exception) }          // a typed, value-free TextNormalizationError
```

`normalizeText` is one configurable normalizer for general text fields. The rules are `stripControl`, `trim`, `collapseSpace`, `removeSpace`, `lowercase`, `uppercase`, `casefold`, the four normalization forms and `nonEmpty`, and each runs over the character set of the block it sits in: `ascii { }` touches only ASCII, `unicode { }` uses tables frozen from the named Unicode release and shipped with the library, never the platform's. A new OS release cannot change what your application produces, and a new Unicode release is a new policy (`text.u18…`), never a changed one.

**Rules always run in one fixed order** — strip control characters, trim, spaces, case, normalization form, then the non-empty check — however you write them, so the same rules always produce the same bytes and one configuration has one id. Writing them in a different order still works, and is reported through `policy.warnings` and `TextPolicy.warningHandler` (printed by default, replaceable or silenceable).

The id states the Unicode release once and only when a rule uses it: `text+trim+lower` is all ASCII, `text.u17+trim+lower.ascii` marks the one ASCII rule inside a Unicode policy. `NfkcU17`, `NfkdU17` and the `nfkc`/`nfkd` rules are deliberately lossy — a ligature becomes its letters and cannot be turned back — so they are for search, not for a token you expect to round-trip. Named presets such as `TextPolicy.NfcU17`, `TrimLowercase` and `CaselessU17` are conveniences for common configurations, nothing more.

This is not an identifier normalizer: emails, domains, phone numbers and handles have their own.

### Hostname and Domain Normalization (`:ubilibet`)
```kotlin
import io.github.aughtone.normalize.ubilibet.DomainPolicy
import io.github.aughtone.normalize.ubilibet.normalizeDomain

normalizeDomain(value, DomainPolicy.AsciiU17)
    .onSuccess { normalized -> store(normalized.canonical) }   // "café.fr" -> "xn--caf-dma.fr"
    .onFailure { failure -> log(failure.exception) }          // a typed, value-free DomainNormalizationError
```

Every hostname goes through the same function, ASCII included: a second, simpler rule for ASCII names would produce identical bytes under a different policy identity, which is a mismatch waiting to happen. `AsciiU17` applies every UTS-46 check; `AsciiU17Lenient` relaxes hyphen placement, the STD3 character restriction and DNS length, and keeps the bidi and joiner rules — those exist to stop a name that displays as one thing and resolves as another, which is not something leniency should buy.

### Phone Numbers (`:phone`)
```kotlin
import io.github.aughtone.normalize.phone.PhonePolicy
import io.github.aughtone.normalize.phone.normalizePhone

normalizePhone("+1 (212) 555-0123", PhonePolicy.E164).dataOrNull()?.canonical               // "+12125550123"
normalizePhone("(212) 555-0123", PhonePolicy.e164ForRegion("us")).dataOrNull()?.canonical   // "+12125550123"
```

**The region travels on the policy, and nothing is ever guessed.** `E164` accepts only input carrying its own country code; `e164ForRegion` reads national-format input against a region you named. A guessed country code does not fail loudly — it produces a valid-looking token for a *different number*, and by then the input is gone. One consequence worth knowing: `phone.e164` and `phone.e164+region-ca` produce identical bytes for input already in E.164 form and are still **different identities**, so systems that must match each other have to agree on the same constant.

### Spoof Detection (`:confusables`)
```kotlin
import io.github.aughtone.normalize.confusables.ConfusablePolicy
import io.github.aughtone.normalize.confusables.normalizeSkeleton

val a = normalizeSkeleton("paypal", ConfusablePolicy.SkeletonU17).dataOrNull()?.canonical
val b = normalizeSkeleton("раypal", ConfusablePolicy.SkeletonU17).dataOrNull()?.canonical   // Cyrillic р and а
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

// policyId and policyVersion were stored next to the hash when the first value was normalized
val policy = QuodlibetPolicies.resolve(policyId, policyVersion).dataOrThrow() as EmailPolicy
normalizeEmail(newValue, policy)
```

Resolution is explicit: combine the resolvers of the modules you depend on with `+`. There is no global registry and no startup registration, and an unknown id, an unknown link or a version this build does not carry fails loudly rather than resolving to something close — a nearly-right policy silently derives bytes that match nothing already stored.

## 🛠️ Contributing

Read [Getting Started](docs/knowledge/guides/DOC-0003-getting-started.md) first, then [AGENTS.md](AGENTS.md) if you are an agent. The one rule that matters more than the rest: **a published policy's canonical output never changes in place** — see [Normalization Suite Structure](docs/knowledge/specifications/DOC-0001-normalization-suite.md).

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
