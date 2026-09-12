# Generating the Unicode tables

ADR-0002 · 2026-09-08 · Status: accepted · corrected 2026-09-11
Keywords: NFC implementation, where do the Unicode tables come from, why not use an existing normalization library, UCD, NormalizationTest.txt, delta packaging, Unicode version drift, bundle size wasmJs, ICU4J, kuri

## Context

The `:unicode` module needs NFC/NFD/NFKC/NFKD, and it cannot read the platform's Unicode tables: those differ by operating-system Unicode version, so the same input would normalize differently on a 2023 Android build and a 2026 iOS one. The data has to be frozen and shipped with the library. Two ways to get it — vendor a frozen implementation from an existing library, or generate the tables from the public Unicode Character Database.

[RAD-0001](../research/RAD-0001-identifier-and-text-normalization.md) left this open and recorded that an ADR was owed once it settled. The same record designed two things that turn out to depend on the answer: base-plus-additive-delta table packaging, and a build-time procedure for deciding whether a new Unicode release is a material change.

## Decision

**Generate the tables from the public UCD, as part of the build.**

The generator regenerates from a named Unicode release, diffs against the frozen baseline, and validates against that release's official `NormalizationTest.txt`. Per [DOC-0001](../specifications/DOC-0001-normalization-suite.md), the new base+delta normalizer must pass the suite completely, and the *old* frozen normalizer must pass everything except the new-character cases — which is what proves a delta is exactly the additions and nothing else moved.

**Rejected: vendoring a frozen implementation.** Faster to a shipping `:unicode` and less code to own, but it defeats both mechanisms above. A vendored normalizer is not structured as a base snapshot plus separately-loadable deltas, so a consumer pinned to an old epoch would carry every later version's data — and that consumer is precisely the wasmJs and iOS client this suite exists for. It also means inheriting someone else's correctness as an article of faith on the one property the library sells.

**Rejected: vendor now, generate later.** Any policy published on vendored tables is frozen forever, so the vendored engine could never be retired — it would have to be maintained alongside the generated one indefinitely. Shipping sooner buys a permanent second table strategy.

## Consequences

**Easier.** The material-change check becomes mechanical rather than a judgement call: regenerate, diff, and an empty diff means no new epoch while additions mean a new epoch and the diff *is* the delta. Delta packaging becomes possible at all, so a consumer pinned to `NfcU17` does not drag later versions into a wasm or iOS bundle.

*Corrected 2026-09-11:* that mechanical check holds for the **normalization** tables, whose existing mappings the Unicode stability policy freezes. It does not hold for every table this generator later produces. UTS-46 permits a previously disallowed character to change status or mapping, and UTS-39 permits any confusable mapping to change between releases, so those families define their own rule and their own packaging — see [ADR-0003](ADR-0003-bundling-modules-by-weight.md). The decision to generate rather than vendor is unaffected; the claim that one check covers every table was wrong. Correctness is verifiable against the standard's own conformance data rather than asserted.

**Harder, and this is the real cost.** This is substantially more work than vendoring, and it is the largest single piece of engineering left in the suite. It adds a build-time generator, a checked-in frozen baseline, and a conformance-test step to CI — none of which exists today. The generator and the first module built on it should be expected to take considerably longer to ship than any module that carries no data.

**What we gave up.** A quicker path to a working normalizer. If schedule pressure ever makes that tempting, the thing to re-read is the second rejection above: the shortcut is not a shortcut, because the vendored engine can never be removed once a policy has been published on it.

**Licensing note.** Generating from the UCD means the Unicode data licence applies to the generated tables and must be carried in `NOTICE.md`, as the format suite already does for CLDR data. Vendoring would have meant carrying the source library's licence and attribution instead; this does not avoid an attribution obligation, it changes whose.
