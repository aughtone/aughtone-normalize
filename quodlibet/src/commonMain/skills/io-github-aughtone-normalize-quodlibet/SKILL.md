---
name: io-github-aughtone-normalize-quodlibet
description: >-
  Canonicalise an email address, credit-card number, IBAN, IP address, MAC
  address, UUID or username into byte-stable text you can hash, so one value
  has one token - instead of hand-rolling lowercase-and-trim before a hash.
  Normalise an email for blind tokenisation or deduplication, keep or remove a
  plus-subaddress, split an address into local part and domain, strip spaces
  and hyphens from a card number, check a Luhn digit, compact an IBAN and check
  its mod-97, canonicalise IPv4 and IPv6 in every notation, derive the /24 or
  /64 network an address falls in, canonicalise a CIDR range, fold a MAC
  address across colon, hyphen and dot notations, canonicalise a UUID from hex,
  URN, braces or a GUID byte order, or fold a username to a comparable handle.
  Every result carries the frozen policy identity that produced it, so a stored
  hash re-derives years later. Carries no lookup table. Not for text or Unicode
  forms (io.github.aughtone.normalize:unicode) or host names and URLs
  (io.github.aughtone.normalize:ubilibet).
license: Apache-2.0
metadata:
  version: "0.0.4"
  repository: https://github.com/aughtone/aughtone-normalize
---

# Aught One Normalize — Quodlibet

## What it solves

You have a value a person typed, and you need to decide whether it is the same value somebody typed somewhere else — usually by hashing it, often without keeping the original. `USER@Example.COM ` and `user@example.com` are one address; `4111 1111 1111 1111` and `4111-1111-1111-1111` are one card; `::ffff:192.0.2.1` and `192.0.2.1` are one host; `{919108f7-52D1-4320-9BAC-F847DB4148A8}` and `919108f7-52d1-4320-9bac-f847db4148a8` are one UUID. Hash them as typed and matching fails silently, for the small fraction of input that was written differently — which is exactly the fraction you cannot see once the input is gone.

This module canonicalises those values so that one thing has one spelling, and it does so **identically on every platform and in every build, forever**. It carries no lookup table of its own, so nothing here drifts when Unicode ships a release.

The part that matters as much as the bytes: **every result carries the identity of the rules that produced it**, so a hash stored today can be re-derived by the same rules in five years. That is what makes these values safe to store when the input is discarded.

## How it is meant to be used

**Normalize, then store the canonical text together with its policy identity.** Both fields, always — they are the only record of which rules produced those bytes.

```kotlin
normalizeEmail(value, EmailPolicy.Address)
    .onSuccess { store(hash(it.canonical), it.policyId, it.policyVersion) }
    .onFailure { error -> log(error) }   // a typed, value-free EmailNormalizationError
```

**Re-derive a policy from what you stored** when you add a value to a store of existing ones, so the new value is normalized by the same rules as the old:

```kotlin
val policy = QuodlibetPolicies.resolve(storedId, storedVersion).getOrThrow() as EmailPolicy
normalizeEmail(newValue, policy)
```

**Ask whether two stored identities may be compared** rather than assuming their strings agree. `comparability` answers from the identities alone — the same policy, comparable in a named form, or not comparable at all:

```kotlin
when (QuodlibetPolicies.comparability(idA, versionA, idB, versionB).getOrThrow()) {
    Comparability.SamePolicy -> compare(a, b)
    is Comparability.InForm -> compare(a, b)
    Comparability.NotComparable -> {}       // do not compare these
}
```

**Every entry point is `normalizeX(value, policy)` and returns an `Outcome`.** There is no default policy anywhere: a caller names the rules, so no value is ever produced under rules nobody chose. Malformed input is a typed failure, never a best-effort result.

## Invariants and traps

**A policy is frozen, and its identity is how you get back to it.** `EmailPolicy.Address` version 1 will write the same bytes for the same input in every future release; a rule change arrives as a new constant or a new version, never as an edit. So storing the canonical text without `policyId` and `policyVersion` is the one mistake that cannot be repaired later — the bytes are unattributable, and you cannot tell whether a stored hash and a fresh one used the same rules. **Symptom:** a value stops matching after an upgrade and there is no way to find out why.

**The email base keeps the `+`-subaddress; it does not strip it.** `EmailPolicy.Address` (`email`) keeps `user+work@example.com` whole. `EmailPolicy.SubaddressRemoved` (`email:subaddress.removed`) is the one that removes the tag. Some mail systems treat the tag as part of an individual's account and no domain can be asked which behaviour it has, so keeping it is the assumption that never merges two people. **Symptom if you pick the wrong one:** two sign-ups that are one person are two rows, or two people are one row — and which of those you got depends on a choice made when the policy was named.

**`mailto:` is not stripped, and does not fail.** `normalizeEmail("mailto:user@example.com", EmailPolicy.Address)` **succeeds**, and the canonical is `mailto:user@example.com` with the scheme inside the local part. Strip the scheme before calling. **Symptom:** an address stored from a `mailto:` URI never matches the same address stored from a form field, and nothing reports it.

**Percent-encoding is not decoded.** `user%2Btag@example.com` canonicalises to `user%2btag@example.com`; no literal `+` is present, so no subaddress is recognised either. Decode before calling. **Symptom:** the same address in two encodings produces two tokens, and the subaddress you expected is missing.

**The email domain is raw bytes, not an IDNA form.** `user@Bücher.Example` canonicalises to `user@bücher.example`, never to `user@xn--bcher-kva.example`. This is deliberate — an address is not obliged to carry a host name — but it means an email domain and a domain normalized as a host **are not comparable**. For a domain token that matches one read from a URL or a block list, use `normalizeEmailParts`, which returns the domain under `domain.ascii.u17`. **Symptom:** a disposable-domain block list built from host names matches every ASCII domain and silently misses every internationalised one.

**`normalizeEmailParts` returns the domain as an `Outcome`, not a value.** An address can be perfectly valid while its domain is not a domain: `user@[192.0.2.1]` is an address literal, and a label can fail a UTS-46 check that the address carried happily. The address still reads; the domain reports why it has no token. **Symptom:** ignoring that failure gives you no domain and no explanation.

**`String.normalizeEmailOrNull(policy)` is not for tokenisation.** It returns the canonical text and throws the identity away, which is fine for a display value and wrong for anything you store. It is also the only such convenience in this module: there is no `normalizePanOrNull`, `normalizeUuidOrNull` or any other, so reach for the `Outcome` everywhere else. **Symptom:** a hash with no recorded policy — see the first trap.

**`QuodlibetPolicies` alone cannot resolve a chain that spans modules.** `normalizeUsername(value, policy, steps)` writes a chained id such as `username.basic:skeleton.u17`, and resolving it needs the resolver of every module the chain names — `QuodlibetPolicies + ConfusablesPolicies` here, plus `UnicodePolicies` where the chain also carries a text policy. **Symptom:** `PolicyIdentityError.UnknownLink` naming the step's link, for an id this module wrote itself.

**A withdrawn identity does not resolve, deliberately.** `QuodlibetPolicies.resolve` refuses an id from an earlier release rather than resolving it to something close, because a nearly-right policy silently derives bytes that match nothing already stored. **Symptom:** resolution fails loudly on upgrade — which is the intended behaviour, not a bug to work around.

**`pan.digits` and `iban.compact` remove presentation, and refuse anything else.** Spaces and hyphens in a card number, spaces in an IBAN, are formatting and go. A character that is neither a digit nor formatting is a typed refusal, not a silent removal — the Luhn and mod-97 checks run on what the standard says is canonical. **Symptom:** input you expected to be cleaned up is refused instead; that refusal is the library declining to guess.

**An IPv4-mapped IPv6 address is not the IPv4 address unless you ask.** `ipv6.rfc5952` writes `::ffff:192.0.2.1` as itself. `ipv4.mapped` writes it as `192.0.2.1`, and only then is it comparable with the IPv4 spelling of the same host, through the `ipv4.address` form. **Symptom:** the same machine appears as two hosts.

## What moved, and what it used to be called

**Behaviour changed silently in `0.0.4` in one place.** Whitespace is now trimmed from both ends of an email address and a username using a frozen list of Unicode whitespace **and invisible format characters** — `U+00A0`, `U+202F`, `U+2000`–`U+200A`, `U+3000`, and `U+200B`, `U+2060`, `U+FEFF` among them — where `0.0.3` trimmed only the ASCII set. An address carrying a leading no-break space or a byte-order mark used to keep it inside the canonical and now does not, so **its bytes changed**. Same signature, same policy id, different output for that input.

**Every policy id was renamed in `0.0.4`, and no canonical bytes changed with it.** Links join with `:` instead of `+`, and no link name contains a hyphen. `email.byte-stable+subaddressed` is now `email`; `ipv4.dotted-quad+block-24` is now `ipv4.quad.dotted:block.24`. A `0.0.3` id **no longer resolves** — it fails rather than aliasing.

**The email policies changed which one is the default, and this is the rename most likely to be got wrong.** In `0.0.3`, `EmailPolicy.ByteStableV1` (`email.byte-stable`) **stripped** the subaddress and `ByteStableV1Subaddressed` (`email.byte-stable+subaddressed`) kept it. In `0.0.4` the keeping policy became the base:

- `EmailPolicy.ByteStableV1` → **`EmailPolicy.SubaddressRemoved`** (`email:subaddress.removed`). This is the byte-identical replacement.
- `EmailPolicy.ByteStableV1Subaddressed` → **`EmailPolicy.Address`** (`email`).

`ByteStableV1` → `Address` compiles and is **not** the same thing: it keeps tags that used to be stripped. Earlier still, the tag-keeping policy was `EmailPolicy.Lenient` (`email.lenient`) in `0.0.1` and `ByteStableV1Lenient` (`email.byte-stable+lenient`) in `0.0.2`; it was never lenient, it kept a subaddress. Its bytes never changed across any of those names.

**`EmailSubaddressPolicy.ByteStableV1` became `EmailSubaddressPolicy.V1`** in `0.0.4`. The subaddress id `email.subaddress` is unchanged.

**Other link renames in `0.0.4`:** `ipv4.dotted-quad` → `ipv4.quad.dotted`, `ipv4.inet-aton` → `ipv4.inet.aton`, `unmap` → `ipv4.mapped`, `nat64` → `ipv4.nat64`, `masked` → `host.zeroed`, `zone` → `zone.kept`, `guid-bytes` → `bytes.guid`, `block-24` → `block.24`, `block-v4-24`/`block-v6-64` → `block.v4.24`/`block.v6.64`.

**`email.domain` never shipped.** A domain identity of that name was built and withdrawn before release, because a domain taken out of an address is a domain: `normalizeEmailParts` returns it under `domain.ascii.u17`, the same identity a domain read anywhere else carries. If you are holding a reference to `email.domain`, it is from an unreleased build.

**Email moved coordinate once.** It was published as `io.github.aughtone.normalize:email` at `0.0.1` and has lived in this module since; the package `io.github.aughtone.normalize.email` and every type name are unchanged. `email:0.0.1` remains on Maven Central and is not republished.

**As of `0.0.4` this module depends on `io.github.aughtone.normalize:ubilibet`,** because `normalizeEmailParts` normalizes a domain as a domain and that needs the IDNA tables. A consumer that touches no domain ships none of them.

**The `Outcome` accessors were renamed to match `kotlin.Result` in this release,** following the `io.github.aughtone:types` library every result here is built on: `dataOrNull()` is `getOrNull()`, `dataOrThrow()` is `getOrThrow()`, `dataOrElse { }` is `getOrElse { }`, and the failure callbacks receive the `Throwable` rather than the `Outcome.Failure` wrapper. The old names are gone rather than deprecated. **What the compiler will not catch:** `Outcome.Failure.message` is a non-null `String` and `Throwable.message` is `String?`, so a `getOrElse { }` interpolating `it.message` keeps compiling and starts writing `null`.

## Called from Kotlin

**Kotlin only.** There is no Swift or JavaScript consumer surface: nothing in this library is annotated `@JsExport`, and no XCFramework, Swift package or podspec is published. The iOS framework and JavaScript targets in the build prove the code compiles for those platforms; they do not export an API to Swift or JavaScript callers. Every declaration lives in `commonMain` — there is no `expect`/`actual` anywhere in the suite — so a claim made here is true on every target.

## What it is not for

*This section needs the maintainer and is unwritten.*
