# Aught One Normalize

[![Maven Central](https://img.shields.io/maven-central/v/io.github.aughtone.normalize/common.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.aughtone.normalize/common)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Kotlin Multiplatform normalization suite — deterministic, byte-stable canonical forms for hashing and matching identifiers and text.

The point is one property: **the same input produces the same canonical bytes, on any platform, at any time.** If you hash a value into a token and throw the original away — blind tokenization, contact discovery, breach-safe matching — a one-byte difference between two platforms is not a degraded match, it is an undetectable miss. This library exists to make that impossible.

It works just as well for ordinary normalization (search keys, dedupe, display) through the same interface.

> [!WARNING]
> **This suite is alpha (`0.0.x`), and its shape is still being worked out.** Policy ids, constant names and module boundaries may change between releases, and some changes will be breaking. Each one is listed in the [changelog](CHANGELOG.md). Once a release is published, a policy's canonical bytes never change in place. What may change during alpha is which policies exist and what they are called. Before you store a token derived under a policy, check the changelog for the release you depend on, and do not treat an id from an unreleased design as final.

## 📦 Modules

Every normalizer in the roster is built: email, credit-card/PAN, IBAN, IPv4, IPv6 and networks, MAC addresses, UUIDs and usernames in `:quodlibet`; configurable text normalization in `:unicode`; hostnames, domains and URLs in `:ubilibet`; UTS-39 skeletons in `:confusables`; and phone numbers in `:phone`.

| Module | Coordinate | What it does |
|---|---|---|
| `:quodlibet` | `io.github.aughtone.normalize:quodlibet` | every normalizer that needs no lookup table and no external dependency: email and its subaddress, credit-card/PAN, IBAN, IPv4, IPv6 and networks, MAC addresses, UUIDs and usernames, each with named frozen policies and typed, value-free errors |
| `:unicode` | `io.github.aughtone.normalize:unicode` | configurable text normalization — trim, spaces, case, case folding, NFC/NFD/NFKC/NFKD — over ASCII or against tables frozen from a pinned Unicode release, never the platform's; its Android artifact bundles a lint check for rules written out of order |
| `:ubilibet` | `io.github.aughtone.normalize:ubilibet` | every hostname and domain, ASCII included, under UTS-46 with Punycode, URLs, and validating ToUnicode for display — the full IDNA conformance suite passes on every target |
| `:confusables` | `io.github.aughtone.normalize:confusables` | UTS-39 skeletons for spoof detection, including the bidirectional algorithm the standard defines them through |
| `:phone` | `io.github.aughtone.normalize:phone` | phone numbers to E.164, with the region on the policy so a country code is never guessed |
| `:common` | `io.github.aughtone.normalize:common` | the shared `Normalized` contract, the policy identity grammar, and resolution of a stored id back to its policy |

## 📥 Installation

Each module is its own coordinate: depend on the normalizers you use and you carry nothing else. Every module exposes `:common` transitively, and `:ubilibet` and `:confusables` bring `:unicode` with them, so you never name those yourself.

The example below installs `:quodlibet`, which is the table-free bundle — email, PAN, IBAN, IP addresses and networks, MAC addresses, UUIDs and usernames. Swap or add coordinates from the table above for the rest.

**Moving from `0.0.1`?** The email normalizer was published as `io.github.aughtone.normalize:email:0.0.1` and now lives in `:quodlibet`. Change the coordinate; nothing else moves. The package, every type name, the canonical output and the policy versions are unchanged, so no stored value is affected. The relaxed email policy has since been renamed twice: `EmailPolicy.Lenient` (`email.lenient`) became `ByteStableV1Lenient` (`email.byte-stable+lenient`) in `0.0.2`, and in `0.0.3` it is `ByteStableV1Subaddressed` (`email.byte-stable+subaddressed`), because it keeps the `+`-subaddress rather than relaxing a rule. Its bytes never changed; only the name and id did. `email:0.0.1` stays on Maven Central.

**Moving from `0.0.3`?** **Every policy id changes**, and no canonical bytes do. Links now join with `:` instead of `+`, names carry no hyphens, and a link that acts on the value reads `<subject>.<what was done>`: `text.u17+trim+casefold+nfc` becomes `text.u17:space.trimmed:case.folded:nfc`, and `ipv4.dotted-quad+block-24` becomes `ipv4.quad.dotted:block.24`. Email is the one behaviour change: the base now **keeps** the `+`-subaddress, and removing it is the option `email:subaddress.removed`. A stored `0.0.3` id no longer resolves, deliberately — it fails rather than quietly resolving to something else. The [changelog](CHANGELOG.md) lists every rename.

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.aughtone.normalize:quodlibet:0.0.3")
        }
    }
}
```

Or with a version catalog:

```toml
# gradle/libs.versions.toml
[versions]
aughtone-normalize = "0.0.3"

[libraries]
aughtone-normalize-quodlibet = { module = "io.github.aughtone.normalize:quodlibet", version.ref = "aughtone-normalize" }
```

**Targets:** JVM, Android, iOS (`arm64`, `x64`, `simulatorArm64`), JS (browser), wasmJs (browser), Linux x64.

## ⚖️ What is stable, and what is not

These are deliberately different promises, and the distinction matters more here than in most libraries:

- **A published policy's output is frozen forever.** `EmailPolicy.Address` (id `email`, version 1) will produce the same bytes for the same input in every future release. A rules change mints a *new* policy version; it is never an in-place improvement, because that would orphan every token already derived under the old one. Store `policyId` and `policyVersion` beside anything you derive.
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
normalizeEmail(value, EmailPolicy.Address)
    .onSuccess { normalized ->
        // the hash is stable across platforms and builds; keep the policy identity beside it
        store(hash(normalized.canonical), normalized.policyId, normalized.policyVersion)
    }
    .onFailure { failure -> log(failure.exception) }   // a typed, value-free EmailNormalizationError

// or, where a failure needs no handling of its own
val canonical: String? = normalizeEmail(value, EmailPolicy.Address).dataOrNull()?.canonical
```

**The base keeps the `+`-subaddress**, because some mail systems treat it as part of an account and no domain can be asked which behaviour it has. `EmailPolicy.SubaddressRemoved` (`email:subaddress.removed`) removes it, for a caller who knows the provider treats it as a tag.

To match on both, read them from one parse: `normalizeEmailWithSubaddress(value, EmailSubaddressPolicy.V1)` returns the mailbox, byte-identical to `normalizeEmail` under `SubaddressRemoved`, and the subaddress (`tag` for `user+tag@example.com`, `null` without a `+`). The subaddress carries its own id, `email.subaddress`, so a stored tag token records what it is.

**For every piece at once, `normalizeEmailParts(value, emailPolicy, domainPolicy)`** returns the mailbox, the local part (`email.local`), the domain and the subaddress from one reading. Use it instead of splitting the canonical string by hand: a hand-split piece records no policy, so nothing says which reading produced the token. The local part keeps its subaddress whatever the policy does with it.

**The domain is a domain.** It comes back normalized by `normalizeDomain` under the `DomainPolicy` you pass, carrying `domain.ascii.u17` — the same identity and the same bytes as a domain read from a URL or a block list, so the tokens match. `user@Bücher.Example` and `user@XN--BCHER-KVA.example` both give `xn--bcher-kva.example`. There is deliberately no email-flavoured domain identity: two readings of one concept would agree on every ASCII domain and diverge on the rest, which is a silent mismatch rather than a choice.

It is an `Outcome`, because an address can be valid while its domain is not one — `user@[192.0.2.1]` carries an address literal, and a label can fail a UTS-46 check the address carried happily. The address still reads; the domain says why it has no token.

Provider rules — collapsing dots, removing a tag only on domains known to support them — stay with you; this hands you the pieces to apply them to.

### Text Normalization (`:unicode`)
```kotlin
import io.github.aughtone.normalize.unicode.TextPolicy
import io.github.aughtone.normalize.unicode.UnicodeRelease
import io.github.aughtone.normalize.unicode.normalizeText

// ASCII rules only: text:space.trimmed:case.lower. Names no Unicode release, so it never goes stale.
val field = TextPolicy { ascii { trim(); lowercase() } }

// Unicode rules against frozen Unicode 17 data: text.u17:space.trimmed:case.folded:nfc
val caseless = TextPolicy(UnicodeRelease.U17) { unicode { trim(); casefold(); nfc() } }

normalizeText(value, caseless)
    .onSuccess { normalized -> store(normalized.canonical, normalized.policyId, normalized.policyVersion) }
    .onFailure { failure -> log(failure.exception) }          // a typed, value-free TextNormalizationError
```

`normalizeText` is one configurable normalizer for general text fields. The rules are `stripControl`, `trim`, `collapseSpace`, `removeSpace`, `lowercase`, `uppercase`, `casefold`, the four normalization forms and `nonEmpty`, and each runs over the character set of the block it sits in: `ascii { }` touches only ASCII, `unicode { }` uses tables frozen from the named Unicode release and shipped with the library, never the platform's. A new OS release cannot change what your application produces, and a new Unicode release is a new policy (`text.u18…`), never a changed one.

**Rules always run in one fixed order** — strip control characters, trim, spaces, case, normalization form, then the non-empty check — however you write them, so the same rules always produce the same bytes and one configuration has one id. Writing them in a different order still works, and is reported two ways: at runtime through `policy.warnings` and `TextPolicy.warningHandler` (printed by default, replaceable or silenceable), and in the editor by the `TextPolicyRuleOrder` lint check that ships inside the `:unicode` Android artifact. The lint check needs no setup in a build with an Android target, where Android Studio underlines the builder and `./gradlew lint` reports it; JVM-only, iOS and web builds get the runtime warning.

The id states the Unicode release once and only when a rule uses it: `text:space.trimmed:case.lower` is all ASCII, `text.u17:space.trimmed:case.lower.ascii` marks the one ASCII rule inside a Unicode policy. `NfkcU17`, `NfkdU17` and the `nfkc`/`nfkd` rules are deliberately lossy — a ligature becomes its letters and cannot be turned back — so they are for search, not for a token you expect to round-trip. Named presets such as `TextPolicy.NfcU17`, `TrimLowercase` and `CaselessU17` are conveniences for common configurations, nothing more.

This is not an identifier normalizer: emails, domains, phone numbers and handles have their own.

### IP Addresses and Networks (`:quodlibet`)
```kotlin
import io.github.aughtone.normalize.ipv4.Ipv4Policy
import io.github.aughtone.normalize.ipv4.block
import io.github.aughtone.normalize.ipv4.cidr
import io.github.aughtone.normalize.ipv4.normalizeIpv4Block
import io.github.aughtone.normalize.ipv4.normalizeIpv4Blocks
import io.github.aughtone.normalize.ipv4.normalizeIpv4Cidr

normalizeIpv4Block("192.0.2.57", Ipv4Policy.DottedQuad.block(24))       // "192.0.2.0/24"
normalizeIpv4Blocks("192.0.2.57", Ipv4Policy.DottedQuad, listOf(24, 16)) // "192.0.2.0/24", "192.0.0.0/16"
normalizeIpv4Cidr("192.0.2.0/24", Ipv4Policy.DottedQuad.cidr())         // "192.0.2.0/24"; host bits set is refused
```

Block derivation buckets an address into the network it falls in, and every prefix is its own identity (`ipv4.quad.dotted:block.24`). **To match an address against a range from a list, run both through the same block policy**: the range's network address, which CIDR input exposes, and the incoming address, at the same prefix. CIDR input comes strict (`cidr`, refusing host bits) or masked (`cidrMasked`, clearing them), and both write a network exactly as block derivation does.

IPv6 works the same way, and has modes for systems that need a different reading, each named in the id: `unmap()` writes an IPv4-mapped address as IPv4 so it matches the IPv4 spelling of the same host and reads `ipv4.mapped` in the id, `nat64()` does the same for `64:ff9b::/96` as `ipv4.nat64`, and `zone()` keeps a zone identifier as `zone.kept`. A block under `unmap` or `nat64` carries both prefixes: `Ipv6Policy.Rfc5952.unmap().block(24, 64)`.

These policies declare comparable forms (`IpForms`), so the matches they exist for are explicit rather than coincidental: an IPv4 address is comparable with its `ipv4.mapped` or `ipv4.nat64` spelling in `ipv4.address`, and a derived block with a CIDR range in `ipv4.network` or `ipv6.network`. `InetAton` only offers its forms, because its reading of `010` as 8 is an interpretation: opt in with `Ipv4Policy.InetAton.withForms(setOf(IpForms.Ipv4Address))`, which stores `ipv4.inet.aton:form.ipv4.address`.

### MAC Addresses (`:quodlibet`)
```kotlin
import io.github.aughtone.normalize.mac.MacNotation
import io.github.aughtone.normalize.mac.MacPolicy
import io.github.aughtone.normalize.mac.formatMac
import io.github.aughtone.normalize.mac.normalizeMac

normalizeMac("00-00-5E-00-53-01", MacPolicy.Eui48)   // "00:00:5e:00:53:01"
normalizeMac("0000.5e00.5301", MacPolicy.Eui48)      // "00:00:5e:00:53:01"

val address = normalizeMac("0:0:5e:0:53:1", MacPolicy.Eui48).dataOrThrow()
formatMac(address, MacNotation.Ieee)                  // "00-00-5E-00-53-01", for display only
```

Every spelling of one address - colon, hyphen, Cisco dotted, bare, any case, leading zeros omitted - normalizes to lowercase colon pairs. The IEEE registry's notation is accepted but never produced, because two canonical notations could never match each other; `formatMac` renders any notation for display, and its output is not an identity to store. EUI-48 and EUI-64 are separate policies and neither is widened into the other.

### UUIDs (`:quodlibet`)
```kotlin
import io.github.aughtone.normalize.uuid.UuidNotation
import io.github.aughtone.normalize.uuid.UuidPolicy
import io.github.aughtone.normalize.uuid.formatUuid
import io.github.aughtone.normalize.uuid.normalizeUuid

normalizeUuid("{919108F7-52D1-4320-9BAC-F847DB4148A8}", UuidPolicy.Hex)         // "919108f7-52d1-4320-9bac-f847db4148a8"
normalizeUuid("urn:uuid:919108f7-52d1-4320-9bac-f847db4148a8", UuidPolicy.Rfc9562)
normalizeUuid("f7089191d15220439bacf847db4148a8", UuidPolicy.Hex.guidBytes())   // a Windows GUID byte dump
```

Any case, braces, a `urn:uuid:` prefix and the bare 32-digit form all normalize to lowercase hyphenated text. `UuidPolicy.Hex` accepts any 128-bit value; `UuidPolicy.Rfc9562` also requires RFC 9562's variant and version bits, and both write the comparable form `uuid`.

**Windows GUIDs store their first three fields little-endian**, so a GUID read from raw bytes and hex-encoded looks like a different UUID, and nothing in the text says which reading is meant. The `guidBytes()` mode is how a caller says the input is a byte dump: it swaps those fields back, and refuses braces and `urn:uuid:`, which only string forms carry. Because the result is only right if the caller is, the mode offers the `uuid` form rather than declaring it: opt in with `withForms(setOf(UuidForms.Uuid))`. `formatUuid` renders braces, a URN, uppercase, bare or GUID byte order for display.

### Hostname and Domain Normalization (`:ubilibet`)
```kotlin
import io.github.aughtone.normalize.ubilibet.DomainPolicy
import io.github.aughtone.normalize.ubilibet.normalizeDomain

normalizeDomain(value, DomainPolicy.AsciiU17)
    .onSuccess { normalized -> store(normalized.canonical) }   // "café.fr" -> "xn--caf-dma.fr"
    .onFailure { failure -> log(failure.exception) }          // a typed, value-free DomainNormalizationError
```

Every hostname goes through the same function, ASCII included: a second, simpler rule for ASCII names would produce identical bytes under a different policy identity, which is a mismatch waiting to happen. `AsciiU17` applies every UTS-46 check; `AsciiU17Lenient` relaxes hyphen placement, the STD3 character restriction and DNS length, and keeps the bidi and joiner rules — those exist to stop a name that displays as one thing and resolves as another, which is not something leniency should buy.

To show a domain to a person, `toUnicodeDomain(value, policy)` converts it to U-labels under the same checks and reports per label which failed, so a display can show `café.fr` where a label validates and its A-label where it does not. The result is for display, not identity: it carries no policy id, and the canonical form to store and match is always the A-label from `normalizeDomain`.

### Phone Numbers (`:phone`)
```kotlin
import io.github.aughtone.normalize.phone.PhonePolicy
import io.github.aughtone.normalize.phone.normalizePhone

normalizePhone("+1 (212) 555-0123", PhonePolicy.E164).dataOrNull()?.canonical               // "+12125550123"
normalizePhone("(212) 555-0123", PhonePolicy.e164ForRegion("us")).dataOrNull()?.canonical   // "+12125550123"
```

**An extension can be kept, or refused.** `normalizePhoneWithExtension(value, ExtensionPolicy.E164)` returns the E.164 number and the extension beside it, each with its own identity — the same shape as the email mailbox and its subaddress. Use it when an extension is data you want to keep; use `normalizePhone` when you want one canonical string and nothing else. The marker is the boundary: the number is whatever `normalizePhone` makes of the text before it, so the two calls agree about every number and about every refusal. A marker with nothing after it introduces nothing, so `+1 212 555 0123#` is that number with no extension.

**An extension is refused, not dropped.** `#`, `,` and `;` are refused under every policy, and a trailing group written with ordinary formatting — the `+43 1 58058-0` Durchwahl style — is refused when the number is already valid without it. Folding those digits into the subscriber number would produce a different, entirely plausible number, which is the one failure a token cannot survive.

**The region travels on the policy, and nothing is ever guessed.** `E164` accepts only input carrying its own country code; `e164ForRegion` reads national-format input against a region you named. A guessed country code does not fail loudly — it produces a valid-looking token for a *different number*, and by then the input is gone. The region is part of the identity, because it records how national input was read, so `phone.e164` and `phone.e164:region.ca` are different policies. Every phone policy writes the same E.164 number, though, and declares the comparable form `phone.e164` (`PhoneForms.E164`): systems that read numbers with different regions, or leniently, match through `comparability` rather than by trusting that their strings agree.

### Spoof Detection (`:confusables`)
```kotlin
import io.github.aughtone.normalize.confusables.ConfusablePolicy
import io.github.aughtone.normalize.confusables.normalizeSkeleton

val a = normalizeSkeleton("paypal", ConfusablePolicy.SkeletonU17).dataOrNull()?.canonical
val b = normalizeSkeleton("раypal", ConfusablePolicy.SkeletonU17).dataOrNull()?.canonical   // Cyrillic р and а
// equal canonical values: the second is a lookalike of the first
```

**A skeleton is a check, never an account key.** It is deliberately many-to-one — that is what makes a lookalike collide with its target — so two genuinely different users can share one. Derive your identity from the plain value, compute the skeleton beside it, and use a collision to *flag* something for review.

It is also a `NormalizationStep`, so a table-free normalizer can take it from a caller: `normalizeUsername(value, UsernamePolicy.Basic, listOf(ConfusablePolicy.SkeletonU17))` produces the policy identity `username.basic:skeleton.u17`, which never matches the plain `username.basic`.

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

**Matching across policies is explicit.** Values from different policies never match by coincidence, but policies can declare a *comparable form* they both write, and a caller can opt into one a policy offers by naming it in the id (`…:form.ipv4.address`). Ask before matching:

```kotlin
import io.github.aughtone.normalize.common.Comparability
import io.github.aughtone.normalize.common.comparability

when (val result = policies.comparability(idA, versionA, idB, versionB).dataOrThrow()) {
    Comparability.SamePolicy -> match()
    is Comparability.InForm -> match()          // result.form names the declaration that allows it
    Comparability.NotComparable -> skip()
}
```

Resolution is explicit: combine the resolvers of the modules you depend on with `+`. There is no global registry and no startup registration, and an unknown id, an unknown link or a version this build does not carry fails loudly rather than resolving to something close — a nearly-right policy silently derives bytes that match nothing already stored.

## 🛠️ Contributing

Read [Getting Started](docs/knowledge/guides/DOC-0003-getting-started.md) first, then [AGENTS.md](AGENTS.md) if you are an agent. The one rule that matters more than the rest: **a published policy's canonical output never changes in place** — see [Normalization Suite Structure](docs/knowledge/specifications/DOC-0001-normalization-suite.md).

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
