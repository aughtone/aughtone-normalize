---
name: io-github-aughtone-normalize-confusables
description: >-
  Detect a lookalike: reduce text to its UTS-39 confusable skeleton so a
  homoglyph spoof collides with the name it imitates - `раypal` written with
  Cyrillic letters folds onto `paypal`. Use it to flag a spoofed username,
  display name, domain label or brand at sign-up or review, to find
  visually identical strings in data you already hold, or to screen a name
  before showing it to a person. Catches Cyrillic, Greek and other
  script-mixing homoglyphs, digit-for-letter substitutions and the many
  Unicode characters that render alike, against confusable tables frozen into
  the library from a pinned Unicode release rather than the platform's data.
  A skeleton is deliberately many-to-one: it is a check, never an identity, an
  account key or a unique index. Not for canonicalising a value you intend to
  store - text and Unicode forms are in
  io.github.aughtone.normalize:unicode, identifiers such as email addresses
  and usernames in io.github.aughtone.normalize:quodlibet, host names in
  io.github.aughtone.normalize:ubilibet.
license: Apache-2.0
metadata:
  version: "0.0.4"
  repository: https://github.com/aughtone/aughtone-normalize
---

# Aught One Normalize — Confusables

## What it solves

Someone registers `раypal` with a Cyrillic `р` and `а`. It is a different string from `paypal` by every byte comparison, and identical to it on screen. The same trick works on a display name, a domain label, a repository name, or anything else a person reads and trusts.

This module reduces text to its **UTS-39 skeleton**: the form where everything that only differs in appearance collapses together, so the spoof and its target come out equal and you can see the collision. The confusable data is compiled into the library from a pinned Unicode release, so the same pair collides on every platform and in every build.

It implements the full `skeleton`, not the simpler `internalSkeleton`: the standard defines it as `bidiSkeleton(LTR, X)`, so the text is laid out by the Unicode bidirectional algorithm before it is reduced.

## How it is meant to be used

**Derive your identity from the plain value, compute the skeleton beside it, and compare skeletons to find a collision:**

```kotlin
val candidate = normalizeSkeleton(newName, ConfusablePolicy.SkeletonU17).getOrThrow().canonical
if (existingSkeletons.contains(candidate)) flagForReview(newName)
```

**Or compose it into a table-free normalizer as a step,** which produces a chained identity distinct from the plain one — `username.basic:skeleton.u17` never matches `username.basic`:

```kotlin
normalizeUsername(value, UsernamePolicy.Basic, listOf(ConfusablePolicy.SkeletonU17))
```

## Invariants and traps

**A skeleton is never an identity.** It is many-to-one **on purpose** — that is what makes a lookalike collide with its target — so two genuinely different people can share one. Store it as an account key, a token or a unique index and a system merges their accounts, or refuses a legitimate registration as a duplicate. **Symptom:** two unrelated users resolving to one record, or a valid sign-up rejected as already taken. Use the skeleton to *flag* a collision for a human or a policy engine; derive the identity from the plain value.

**This is the one normalizer in the suite that does not preserve distinctions.** Every other policy here keeps distinct values distinct so two parties can match on them. If you are reaching for this module to canonicalise something you will store, you want a different module.

**A stored skeleton has to be recomputed when the data version changes.** UTS-39 states that confusable mappings may change between Unicode releases and that stored skeletons must be recomputed. `skeleton.u17` keeps producing what it produces forever, which is why each release is a new constant — but comparing a skeleton computed under `skeleton.u17` against one computed under a later release is a comparison with no meaning. **Symptom:** a spoof that stops being detected, or a false collision, after the suite's Unicode release moves; nothing reports it, because both values are valid skeletons.

**A skeleton declares no comparable form, deliberately.** `ConfusablePolicy.forms` is empty, so `PolicyResolver.comparability` will never report a skeleton as comparable with anything. That is the identity layer refusing to let a check be used as a match key.

**A skeleton collision is evidence, not a verdict.** The fold is aggressive by design, so unrelated strings do collide. Treat a match as a reason to look, not as proof of intent.

**Mixed-script and restriction-level detection are not here.** UTS-39 also defines identifier restriction levels and mixed-script confusability; this module implements the skeleton. Screening a name for script mixing is a separate decision, and this gives you the skeleton a display or a policy engine can use.

**An unpaired surrogate is refused, not folded.** Half a character has no valid UTF-8 form and no display form either, so `ConfusableNormalizationError.UnpairedSurrogate` comes back — the only failure this module has.

**Resolving a chained id needs every module in the chain.** `ConfusablesPolicies` alone resolves `skeleton.u17`. A composed id such as `username.basic:skeleton.u17` needs the resolvers combined — `QuodlibetPolicies + ConfusablesPolicies` — and one that also carries a text policy needs `UnicodePolicies` too. **Symptom:** `PolicyIdentityError.UnknownLink` naming the base, for an id this module helped write.

**The public surface is five declarations.** `normalizeSkeleton`, `ConfusablePolicy` (with `SkeletonU17`), `NormalizedSkeleton`, `ConfusableNormalizationError` and `ConfusablesPolicies`. There is no `isConfusable(a, b)`, no `areConfusable`, no similarity score, and no `normalizeSkeletonOrNull`: comparing two skeletons for equality is the comparison, and it is yours to make.

## What moved, and what it used to be called

**This module arrived in `0.0.2` and its policy id has never changed.** `skeleton.u17` is what it was called then and what it is called now, and its bytes have never moved.

**What did change is how a composed chain names it.** `NormalizationStep` grew from `link: PolicyLink` to `links: List<PolicyLink>` in `0.0.3` — a skeleton is still a group of one, since it takes no qualifiers. And in `0.0.4` chain links join with `:` instead of `+`, so a composed identity that read `username.basic+skeleton.u17` now reads `username.basic:skeleton.u17`, and the `0.0.3` spelling is refused rather than aliased.

**The shape an agent will guess at, and does not exist here.** There is no `Confusables.isConfusable(a, b)`, no `SpoofChecker`, no ICU-style checker object with settable restriction levels, and no default policy argument: `normalizeSkeleton` takes a policy because a caller must name the Unicode release the fold ran against, and comparing skeletons is the caller's own equality test.

**The `Outcome` accessors were renamed to match `kotlin.Result` in this release,** following the `io.github.aughtone:types` library every result here is built on: `dataOrNull()` is `getOrNull()`, `dataOrThrow()` is `getOrThrow()`, `dataOrElse { }` is `getOrElse { }`, and the failure callbacks receive the `Throwable` rather than the `Outcome.Failure` wrapper. The old names are gone rather than deprecated. **What the compiler will not catch:** `Outcome.Failure.message` is a non-null `String` and `Throwable.message` is `String?`, so a `getOrElse { }` interpolating `it.message` keeps compiling and starts writing `null`.

## Called from Kotlin

**Kotlin only.** There is no Swift or JavaScript consumer surface: nothing in this library is annotated `@JsExport`, and no XCFramework, Swift package or podspec is published. The iOS framework and JavaScript targets in the build prove the code compiles for those platforms; they do not export an API to Swift or JavaScript callers. Every declaration lives in `commonMain` — there is no `expect`/`actual` anywhere in the suite — so the confusable tables and the bidirectional algorithm are the same compiled code on every target.

## What it is not for

*This section needs the maintainer and is unwritten.*
