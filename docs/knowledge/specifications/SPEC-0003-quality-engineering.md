# Quality Engineering

SPEC-0003 · 2026-09-07
Keywords: eight pillars, what tests does a change need, edge case matrix, how do we know the bytes did not change, regression corpus, does this need a security review, performance testing a library

The quality bar a change has to clear, expressed as the eight pillars and read against what this project actually is: a headless multiplatform library whose output is hashed by other people. Not every pillar carries the same weight here, and the ones that do not are marked rather than quietly dropped.

## The pillars

**1. Living acceptance criteria.** Every change has acceptance criteria, and they live on the tracker story that carries the work — not in a file in this repository. The AC checklist *is* the scope of a story. Write them Given/When/Then. (This replaces an earlier convention that kept them in `docs/ACs/`; a file cannot track state, and the two drifted.)

**2. Permutation coverage.** For a normalizer, the permutations *are* the specification: empty local part, empty domain, missing separator, leading and trailing whitespace, mixed case, non-ASCII, well-formed surrogate pairs, unpaired surrogates in both directions, and the subaddress edge where stripping empties the local part. A new normalizer covers the equivalent matrix for its own identifier type before it is considered done.

**3. Determinism across targets.** The pillar this project lives or dies on, and the one a single-platform test suite will never exercise. Tests run on every target — jvm, js, wasmJs, iOS, linux — and a normalizer's canonical output must be identical on all of them. `./gradlew check` runs the full set; a change that only passes on JVM has not been tested.

**4. Regression.** A published policy's output is frozen. Once a policy version ships, its canonical forms are a regression corpus: any change that alters the bytes for an already-valid input is a defect unless it is a deliberate new policy version. Idempotence belongs here too — normalizing an already-canonical value must return it unchanged.

**5. Unit and integration tests.** Unit coverage for every public entry point, including the failure paths and their typed error identities, not just the happy path. Integration in this context means the module boundary: a consumer depending only on the published coordinates can do what the README says it can.

**6. SOLID and API surface.** For a library the relevant discipline is what is `public`. Anything not deliberately part of the contract is `internal` or `private` — `EmailPolicy`'s constructor is `internal` precisely so policies cannot be invented outside the module. Adding a public symbol is a promise; review it as one.

**7. Performance.** Low weight here, deliberately. These are short string operations with no allocation pressure worth auditing, and no measurement has been needed. It becomes real when `:unicode` lands: table lookup, delta loading and bundle size on wasmJs and iOS are the things to measure, and a finding there wants a RAD with a `Measured against:` line.

**8. Security.** Two concrete rules, both already enforced in `:email`. Errors are **value-free** — an error message never echoes the input, so a rejected identifier cannot reach a log. And the library never logs, stores or transmits a value it was given. Beyond that, the security property this library provides is downstream: consumers hash the canonical form, and a canonical form that silently changes is the failure that matters.

## What a change needs before it merges

- The permutation matrix for what it touches, extended rather than replaced.
- `./gradlew check` green on every target, not just the one on the developer's machine.
- No alteration to any published policy's bytes — or, if that is the intent, a new policy version and an [ADR](../decisions/) recording it.
- Public API additions reviewed as contract additions.
