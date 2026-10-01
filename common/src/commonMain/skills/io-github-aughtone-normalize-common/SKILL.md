---
name: io-github-aughtone-normalize-common
description: >-
  Turn a policy id and version stored beside a hash back into the rules that
  produced those bytes, so a token derived years ago can be re-derived today,
  and decide whether two normalized values may be compared at all. Read the
  canonical text and its policy identity off any normalized result; resolve a
  stored id and version from a database column or a configuration file;
  combine the resolvers of the modules a program depends on; ask whether a
  value normalized under one policy may be matched against one normalized
  under another, or must not be; parse, validate and order a policy id; opt
  into a comparable form; carry an id through a slot that refuses `:` and
  convert it back; compose one module's transform into another module's rules.
  This is the identity and resolution layer only: it normalizes nothing
  itself. Every normalizer in the suite - for text, and for each kind of
  identifier - is a separate coordinate under
  io.github.aughtone.normalize.
license: Apache-2.0
metadata:
  version: "0.0.5"
  repository: https://github.com/aughtone/aughtone-normalize
---

# Aught One Normalize — Common

## What it solves

You normalized a value, hashed it, stored the hash, and threw the input away. Later you have to add a new value to that store and match it against the old ones — which means normalizing it by **the same rules the old rows were derived with**. Those rules are not in your code; they are in the two columns beside the hash. This module is what turns them back into something you can call.

It also answers the question that comes immediately after: **may these two values be compared at all?** Bytes agreeing is often a coincidence, so matching here is scoped by policy identity, and a cross-policy comparison is either explicitly permitted or refused.

Everything else here exists to keep those two answers trustworthy: the grammar a policy id is written in, the rule that one policy has exactly one id, and the refusal to ever return a policy that is nearly right.

## How it is meant to be used

**Every normalizer in the suite returns a `Normalized`, and you store all three of its fields.** `canonical` is what you hash or match on; `policyId` and `policyVersion` are the only record of which rules wrote it.

```kotlin
result.onSuccess { store(hash(it.canonical), it.policyId, it.policyVersion) }
```

**Build one resolver from exactly the modules the program depends on, and resolve a stored id through it.** There is no registry and nothing registers at startup:

```kotlin
val policies = QuodlibetPolicies + PhonePolicies          // PolicyResolver.plus
val policy = policies.resolve(storedId, storedVersion).getOrThrow()
```

**Ask before matching across identities.** `comparability` answers from the two stored identities alone:

```kotlin
when (policies.comparability(idA, versionA, idB, versionB).getOrThrow()) {
    Comparability.SamePolicy -> compare(a, b)
    is Comparability.InForm -> compare(a, b)     // .form names the declaration allowing it
    Comparability.NotComparable -> {}            // do not compare these
}
```

## Invariants and traps

**A resolver only knows the modules you combined into it.** `resolve` is not a lookup in a merged table — each module answers for its own ids, and a chain that spans modules needs every one of them present. `QuodlibetPolicies.resolve("username.basic:skeleton.u17", 1)` **fails**; `(QuodlibetPolicies + ConfusablesPolicies).resolve(…)` succeeds. **Symptom:** `PolicyIdentityError.UnknownLink` or `UnknownPolicy` for an id you know is real, naming the link whose module is missing from the combination.

**Resolution never approximates.** An unknown id, an unknown link, or a version this build does not carry is a typed failure — never the nearest match, never the newest version, never a default. A withdrawn id from an earlier release does not resolve to its successor. **Symptom:** resolution fails loudly after an upgrade. That is the designed behaviour, not a bug to work around: an almost-right policy derives bytes that silently match nothing already stored.

**One policy has exactly one id, so an id is refused rather than repaired.** Links in the wrong order, a duplicate link, a form link that is not last, or a spelling the owning module would not render are all refused. Nothing is sorted into place. **Symptom:** `OutOfOrder`, `DuplicateLink` or `NotCanonical` from an id you assembled or edited by hand — the fix is to get the id from the policy, not to adjust the string.

**`PolicyIdentityError` messages quote the id, and that is deliberate.** Every other error type in this suite is value-free because it describes a value being normalized. A policy id is public identity: it lives in configuration files and plain columns, and a resolution failure that will not name the link it could not resolve is not debuggable. **Symptom:** if you strip ids out of these messages to "sanitise" them, every resolution failure becomes unactionable.

**Not everything here fails through `Outcome`.** `PolicyResolver.resolve`, `PolicyId.of`, `PolicyId.parse` and `comparability` return an `Outcome`. But `PolicyLink`'s constructor, `ComparableForm`'s constructor and `Policy.withForms` throw `IllegalArgumentException` — they are programming errors, caught the first time the class loads rather than the first time someone stores an id. **Symptom:** an uncaught `IllegalArgumentException` from a `withForms` call naming a form the policy only declares, or does not have at all.

**A comparable form is only usable when both policies say so.** `Policy.forms` is what a policy writes; `offeredForms` is what a caller may opt into by naming it in the id. Opting in changes the id and **not one byte** of the output. Two values are comparable in a form only if both of their resolved policies carry it. **Symptom:** `Comparability.NotComparable` between values whose canonical strings look comparable — which is the answer, not an obstacle.

**`SamePolicy` needs the same id *and* the same version.** Two values under one id at different versions are not the same policy; they fall through to the form check, because a version bump means the rules changed.

**`ComposedPolicy` is a record of what happened, not a normalizer.** A composite resolver returns one for a chained id such as `username.basic:skeleton.u17`. It has no `apply`: derive new values by handing its `base` and `steps` back to the normalizer that owns the base. Its `version` is the base's.

**Constructing a `PolicyLink` grants no authority.** You can build any well-formed link, and a chain resolves only if every link in it is one a module actually publishes — which is what stops an id naming a rule-set nobody wrote.

**The portable spelling is transport, never storage.** `PolicyId.toPortable` maps `:` to `_` for slots that refuse `:` — container tags, some label and metric systems. Store and compare the canonical `:` form and convert back on the way in. A value containing both separators is refused as `NotPortable` rather than accepted as already canonical.

**`@InternalNormalizeApi` carries no stability promise.** It marks data the suite's own modules share across a module boundary, such as the character properties `:ubilibet` reads from `:unicode`. Opting in means tracking the repository rather than the published contract; it may change shape in any release, where a policy's output may not.

## What moved, and what it used to be called

**Every policy id was respelled in `0.0.4`, and no canonical bytes changed with it.** Links join with `:` instead of `+`; a link name may no longer contain a hyphen (`[a-z0-9]+` segments joined by `.`, so a Unicode minor release is `u15.1`, not `u15-1`); and a link that acts on the value reads `<subject>.<what was done>`. A `0.0.3` id is refused rather than aliased. `PolicyId.toPortable` maps `:`→`_` where it used to map `+`→`_`, so a portable spelling produced by `0.0.3` converts back to an id that no longer resolves.

**`PublishedPolicies.resolve` became final in `0.0.3`.** A module that rebuilds policies from their ids overrides `resolveBase` instead, so opted-in comparable forms are handled once for every module rather than per module.

**The `Outcome` accessors were renamed to match `kotlin.Result` in `0.0.5`,** following the `io.github.aughtone:types` library every result type here is built on: `dataOrNull()` is now `getOrNull()`, `dataOrThrow()` is `getOrThrow()`, and `dataOrElse { }` is `getOrElse { }`. The old names are gone rather than deprecated. The failure callbacks — `onFailure`, `fold`'s second parameter, `recover`, `getOrElse` — now receive the `Throwable` itself rather than the `Outcome.Failure` wrapper. `Outcome.Failure.exception`, `Success.data` and `runOutcome` are unchanged. **One thing the compiler will not catch:** `Outcome.Failure.message` is a non-null `String` and `Throwable.message` is `String?`, so a `getOrElse { }` interpolating `it.message` keeps compiling and starts writing `null`; `it.message ?: it.toString()` is what `Failure.message` did.

## Called from Kotlin

**Kotlin only.** There is no Swift or JavaScript consumer surface: nothing in this library is annotated `@JsExport`, and no XCFramework, Swift package or podspec is published. The iOS framework and JavaScript targets in the build prove the code compiles for those platforms; they do not export an API to Swift or JavaScript callers. Every declaration lives in `commonMain` — there is no `expect`/`actual` anywhere in the suite — so a claim made here is true on every target.

## What it is not for

**It is not a normalizer.** Nothing here canonicalises a value: this module holds the identity grammar, the result shapes and the resolver contract, and no rules and no tables at all. A value is normalized by the module that owns its kind.

**It is not a registry, and resolution is not discovery.** No module registers itself anywhere, nothing is found at runtime, and there is no global resolver. A caller combines the resolvers of the modules it depends on, deliberately, with `+`.

**It is not a general-purpose result type.** `Outcome` belongs to `io.github.aughtone:types`; this module only returns it.

**It encodes universal standards, never one provider's behaviour.** A rule that cannot be true everywhere cannot be frozen, so a policy here never captures how one vendor, platform or mail host happens to behave. Where a caller needs that, it belongs above this suite, in the code that knows which provider it is talking to.
