---
name: io-github-aughtone-normalize-unicode
description: >-
  Normalise a free-text field for matching, searching or deduplication against
  Unicode tables frozen into the library, so the same text produces the same
  bytes on every platform and in every build - use instead of `trim()`,
  `lowercase()` or a platform Unicode normaliser, whose results change with the
  OS. Apply NFC, NFD, NFKC or NFKD; case-fold text for caseless matching;
  lowercase or uppercase; trim leading and trailing whitespace; collapse runs
  of whitespace to one space, or remove whitespace entirely; strip control
  characters; refuse an empty result; choose whether each rule runs over ASCII
  only or over all of Unicode; get the frozen identity of the rules that
  produced the text; or hand the configured rules to another normaliser as a
  composable step. Not for identifiers, which have their own rules: email,
  card numbers, IBANs, IP and MAC addresses, UUIDs and usernames in
  io.github.aughtone.normalize:quodlibet, host names and URLs in
  io.github.aughtone.normalize:ubilibet.
license: Apache-2.0
metadata:
  version: "0.0.4"
  repository: https://github.com/aughtone/aughtone-normalize
---

# Aught One Normalize — Unicode

## What it solves

You want two pieces of text that a person would call the same to compare equal — `"  Café "` and `"café"` and `"CAFÉ"` — so you reach for `trim()`, `lowercase()` and the platform's Unicode normalizer. That works until the same code runs somewhere else: the platform's Unicode data is whatever version the OS shipped with, so an Android device from three years ago, a current iPhone and a browser can disagree about the same string. If you stored a hash of the result, the disagreement is not a degraded match; it is an invisible miss.

This module does the same work against **tables compiled into the library from a pinned Unicode release**. Nothing reads the platform's Unicode data. A new OS release cannot change what your application produces, and a new Unicode release is a **new policy** with a new id rather than a change to an existing one.

It also answers the question that follows: which rules produced this text? Every result carries the identity of its configuration, so text canonicalized today can be canonicalized the same way in five years.

## How it is meant to be used

**Configure the rules you want, then normalize with them.** There is no default policy — the choice between a canonical and a compatibility form is not one to make on a caller's behalf:

```kotlin
val caseless = TextPolicy(UnicodeRelease.U17) { unicode { trim(); casefold(); nfc() } }

normalizeText(value, caseless)
    .onSuccess { store(it.canonical, it.policyId, it.policyVersion) }
    .onFailure { error -> log(error) }
```

**Keep a policy that needs no Unicode data ASCII, and its id never names a release.** `TextPolicy { ascii { trim(); lowercase() } }` is the byte-stable replacement for `trim().lowercase()`, and renders `text:space.trimmed:case.lower` — no release in the id, so a new Unicode release cannot give the same bytes a second identity:

```kotlin
TextPolicy { ascii { trim(); lowercase() } }                     // text:space.trimmed:case.lower
TextPolicy(UnicodeRelease.U17) { unicode { trim() }; ascii { lowercase() } }
                                        // text.u17:space.trimmed:case.lower.ascii
```

**A `TextPolicy` is also a `NormalizationStep`,** so a module that ships no Unicode data can take one from a caller and compose it whole, carrying its rules into the composed id:

```kotlin
normalizeUsername(value, UsernamePolicy.Basic, listOf(TextPolicy.NfcU17))
                                        // username.basic:text.u17:nfc
```

## Invariants and traps

**Building a policy prints to standard output by default.** `TextPolicy.warningHandler` reports a policy whose rules were written in a different order from the one they run in, and its default implementation is `println`. **Symptom:** unexplained `aughtone-normalize: text…: rules written as […] run as […]` lines in your console or logs. Replace the handler to route them into your own logging, or set it to `{}` to silence them; a warning never changes a policy's id or its output.

**Rules run in one fixed order however you write them** — strip control characters, trim, collapse or remove spaces, case, normalization form, then the non-empty check. `ascii { }` and `unicode { }` choose the character set a rule runs over, never when it runs. So `TextPolicy { ascii { lowercase(); trim() } }` behaves exactly like the trim-then-lowercase spelling and renders the same id. **Symptom:** code that reads as one order and runs as another — which is what the warning above is telling you.

**Building a policy throws; it does not return an `Outcome`.** `TextPolicyError` is an `IllegalArgumentException`, thrown when the policy is created and never while normalizing: the same rule twice (`RepeatedRule`), two rules that are alternatives — `lowercase()` with `casefold()`, or two normalization forms (`ContradictoryRules`) — or a `UnicodeRelease` passed to a policy whose every rule is ASCII (`UnusedRelease`). **Symptom:** an uncaught exception at class-initialization time, because policies are usually built into a `val`.

**`normalizeText` can fail with an error that is not a `TextNormalizationError`.** Composing a step frozen against a different Unicode release fails with `TextPolicyError.MismatchedRelease` — one chain runs against one release's data, so `text.u17:…:skeleton.u18` is refused rather than mixed. **Symptom:** a `when` over `TextNormalizationError` subclasses that does not cover the failure you actually get.

**A policy carrying `nonEmpty()` throws out of `apply` when used as a step.** As a `NormalizationStep` it reports nothing: `TextNormalizationError.Empty` is thrown from `apply`, which the *host* normalizer's builder turns into its failure. **Symptom:** `normalizeUsername` returns `Outcome.Failure` holding a `TextNormalizationError`, not a `UsernameNormalizationError` — an exhaustive `when` over the host module's error type does not cover it.

**`nfkc` and `nfkd` are lossy, deliberately.** They fold ligatures, superscripts and full-width characters into plain ones: right for search and loose matching, wrong for a token you expect to round-trip. `nfc` and `nfd` are lossless. **Symptom:** a value that cannot be turned back into what the user typed, discovered after the input was discarded.

**Each release is its own identity, and a release the build does not carry does not resolve.** A policy with any Unicode rule names exactly one release, once, and every Unicode rule in it uses that release's tables. A later release is a new base (`text.u18:…`), never a change to what `text.u17:…` produces. **Symptom:** `PolicyIdentityError.UnknownLink` for a stored `text.u18:…` id on a build whose only release is `u17`.

**`case.lower` over ASCII is `A`–`Z` and nothing else.** An `ascii { lowercase() }` policy leaves `É` exactly as it arrived — which is the point, since it cannot then drift — and `unicode { casefold() }` is the rule for caseless matching across scripts. Full case folding is Unicode-only and has no ASCII form. **Symptom:** non-ASCII text that did not fold, under a policy that never promised to fold it.

**An unpaired surrogate is refused, not normalized.** Half a character has no valid UTF-8 form and encodes inconsistently across targets, so `TextNormalizationError.UnpairedSurrogate` comes back rather than bytes that differ by platform.

**Resolution rebuilds a text policy from its id and refuses any other spelling.** `UnicodePolicies` does not look an id up in a list — it parses the release and rules, builds the policy through the same builder a caller uses, and refuses unless it renders back to exactly the id given (`PolicyIdentityError.NotCanonical`). **Symptom:** a hand-written or hand-edited text id is refused; take the id from `policy.id` instead.

**This is not an identifier normalizer.** An email address, a domain, a phone number or a handle has structure these rules do not respect, and each has its own normalizer in the suite. `TextPolicy.all` and the named presets are conveniences for common configurations, nothing more.

**The Android artifact carries a lint check.** `TextPolicyRuleOrder` reports a builder whose rules are written out of application order, in the editor and in an Android lint run, in any build with an Android target. JVM-only, iOS and web builds get the runtime warning above instead.

## What moved, and what it used to be called

**The four normalization forms stopped being policies of their own in `0.0.3`.** `nfc.u17`, `nfd.u17`, `nfkc.u17` and `nfkd.u17` were ids in `0.0.2` and **no longer resolve**. The same forms are text policies: `text.u17:nfc` and its siblings, which is what `TextPolicy.NfcU17`, `NfdU17`, `NfkcU17` and `NfkdU17` now render. The constants kept their names; only their ids moved. Output bytes never changed.

**Every text policy id was respelled in `0.0.4`, and no canonical bytes changed with it.** Links join with `:` instead of `+`, and every rule link was renamed: `trim` → `space.trimmed`, `collapse-space` → `space.collapsed`, `remove-space` → `space.removed`, `strip-control` → `control.removed`, `lower` → `case.lower`, `upper` → `case.upper`, `casefold` → `case.folded`, `non-empty` → `empty.refused`. So `text.u17+trim+casefold+nfc` is now `text.u17:space.trimmed:case.folded:nfc`, and a `0.0.3` id is refused rather than aliased. A link name may no longer contain a hyphen, so a Unicode minor release would be spelled `u15.1`.

**`NormalizationStep` grew from one link to a group in `0.0.3`.** `link: PolicyLink` became `links: List<PolicyLink>`, which is what lets a configured text policy travel into another module's chain whole — `username.basic:text.u17:space.trimmed:nfc` — rather than as a single-link alias for one of its rules.

**The `Outcome` accessors were renamed to match `kotlin.Result` in this release,** following the `io.github.aughtone:types` library every result here is built on: `dataOrNull()` is `getOrNull()`, `dataOrThrow()` is `getOrThrow()`, `dataOrElse { }` is `getOrElse { }`, and the failure callbacks receive the `Throwable` rather than the `Outcome.Failure` wrapper. The old names are gone rather than deprecated. **What the compiler will not catch:** `Outcome.Failure.message` is a non-null `String` and `Throwable.message` is `String?`, so a `getOrElse { }` interpolating `it.message` keeps compiling and starts writing `null`.

## Called from Kotlin

**Kotlin only.** There is no Swift or JavaScript consumer surface: nothing in this library is annotated `@JsExport`, and no XCFramework, Swift package or podspec is published. The iOS framework and JavaScript targets in the build prove the code compiles for those platforms; they do not export an API to Swift or JavaScript callers. Every declaration lives in `commonMain` — there is no `expect`/`actual` anywhere in the suite — so a claim made here is true on every target, and the Unicode tables are the same compiled data on all of them.

## What it is not for

*This section needs the maintainer and is unwritten.*
