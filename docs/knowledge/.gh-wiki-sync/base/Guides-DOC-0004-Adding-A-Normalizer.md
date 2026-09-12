# Adding a Normalizer

DOC-0004 · 2026-09-07
Keywords: new normalizer, new module, phone normalizer, slug normalizer, how do I add to the suite, what does my result type need, naming a policy, new gradle module KMP

How to add a normalizer that fits the suite's contract. Read [Normalization Suite Structure](Specifications-DOC-0001-Normalization-Suite) first — this guide is the mechanics, that document is the rules.

## 1. Decide where it goes

Ask whether it needs a Unicode table.

- **No table** — it goes in `:quodlibet`, the bundle of normalizers that need no data and no external dependency. Email, credit-card/PAN, IBAN and IPv6 all belong there. Do not create a module for it: a module boundary exists only where what a caller must carry changes ([ADR-0003](Decisions-ADR-0003-Bundling-Modules-By-Weight)).
- **Needs a Unicode table** — the table comes from the generator ([ADR-0002](Decisions-ADR-0002-Generating-The-Unicode-Tables)) and ships in the module that needs it: normalization forms in `:unicode`, confusables in `:confusables`, IDNA data and every hostname in `:ubilibet`. Join the module whose data you use; a new module is justified only when you bring data no existing module carries. Never hand-write or vendor a table.
- **Needs region metadata but not Unicode** — like phone, which depends on `aughtone-phonenumber`. Its own module.

A normalizer belongs in this suite at all only if it is general, reusable, and fits the versioned-policy contract. An application-specific formatter does not.

## 2. Implement the contract

Four pieces, following the email normalizer in `:quodlibet` as the worked example:

- **`NormalizedX : Normalized`** — a data class carrying `canonical`, `policyId` and `policyVersion`. Nothing else unless the identifier genuinely needs it.
- **`XPolicy`** — a class with an **`internal` constructor** and named frozen instances in its companion. The internal constructor is the point: callers must not be able to invent a policy, because an unnamed rule-set cannot be reproduced later. Each instance carries a stable `id` and an integer `version`.
- **`XNormalizationError`** — a `sealed class` extending `Exception`, one subclass per failure reason. **Messages must never echo the input.** A rejected identifier that reaches a log is the failure mode these types exist to prevent.
- **`normalizeX(value, policy): Outcome<NormalizedX>`** — a top-level function with **no default policy**, built with `runOutcome { }` from `aughtone-types` (throw the typed error to fail). Add a `String.normalizeXOrNull(policy): String?` convenience if it makes sense, documented as *not* for tokenization.

The canonical string is what a caller hashes. If an operation would need a Unicode table and your module has none, it does not belong in the policy — take it as a `NormalizationStep` from `:common` instead and let the caller compose.

## 3. Add the module

A new module is only for a normalizer that brings its own data or an external dependency. When you do need one, copy `quodlibet/build.gradle.kts` and change:

- the `android { namespace }`,
- the iOS framework `baseName` (`AONormalize<Name>`) and its `bundleId` binary option,
- the `coordinates(...)` artifact id,
- the POM `name` and `description`.

Leave the target list, the `jvmToolchain(17)`, and the `metadata { compilations.all { ... } }` block alone — that last one is the KT-66568 workaround and every module needs it.

Then add `include(":yourmodule")` to `settings.gradle.kts`. Depend on `api(project(":common"))`; use `api`, not `implementation`, so consumers see the `Normalized` contract.

## 4. Test it

The permutation matrix is the specification. At minimum: each failure mode with its typed error identity, idempotence, and the fact that the same input yields byte-identical output. Use plain camelCase test names.

```bash
./gradlew check
```

Green on every target, not just JVM. A normalizer that passes only on JVM has not been tested for the one property the suite exists to provide.

## 5. Publish its policies

Add every policy to the module's resolver object, the single `PublishedPolicies` subclass that module exposes, along with the links the policies are built from. Build each policy's `id` by rendering a chain through `PolicyId` rather than writing the string out by hand — the written and parsed forms have to agree forever, and one grammar for both is what keeps them agreeing.

Then copy the round-trip test: every published policy must resolve from its own `(id, version)` and come back as the same instance. A policy missing from the resolver still normalizes, so nothing fails at build time — it simply cannot be re-derived from a stored id later, which is discovered by the consumer, years on, with the inputs gone.

## 6. Record it

Add a `CHANGELOG.md` entry under `## [Unreleased]`, and update [DOC-0001](Specifications-DOC-0001-Normalization-Suite)'s module table — it is a specification, so it is corrected in place to match reality.

## Never do this

**Do not change a published policy's output.** Adding a rule, however clearly an improvement, changes the canonical bytes and orphans every token already derived under that policy. It is always a new policy version, and it needs an [ADR](Decisions) first.
