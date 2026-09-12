# Regenerating the Unicode Tables

DOC-0006 · 2026-09-11
Keywords: new Unicode version, update Unicode data, regenerate tables, UCD checksum failed, material change, ucd-generator, verifyUnicodeTables failed, how do I move to Unicode 18

How to move the suite to a new Unicode release, and what to do when the generator refuses. The decision behind all of this — generate from the public data rather than vendor someone's implementation — is [ADR-0002](Decisions-ADR-0002-Generating-The-Unicode-Tables).

## What is checked in, and why

`ucd/<version>/` holds the Unicode data files this release was frozen from, their `SHA256SUMS`, and a `baseline/` directory with each generated table in its encoded text form. The tables themselves are generated Kotlin under `unicode/`, and the conformance corpus is generated into that module's test sources.

Everything is checked in on purpose. A build that downloads the data it freezes is not reproducible, and a table quietly generated from different input would change canonical bytes with nothing in the diff to explain it. The generator verifies every checksum before it parses a line.

## Moving to a new Unicode release

1. **Add the data.** Create `ucd/<new-version>/`, download that release's `UnicodeData.txt`, `CompositionExclusions.txt`, `DerivedNormalizationProps.txt` and `NormalizationTest.txt` from `https://www.unicode.org/Public/<new-version>/ucd/`, and write `SHA256SUMS` for them (`shasum -a 256 *.txt > SHA256SUMS`).
2. **Copy the previous baselines** into `ucd/<new-version>/baseline/`. This is what the regeneration is compared against, and it is how a modified entry is noticed at all — without it every entry looks new.
3. **Point the generator at the new directory** in `tools/ucd-generator/build.gradle.kts`.
4. **Regenerate:**

   ```bash
   ./gradlew :tools:ucd-generator:generateUnicodeTables
   ```

5. **Read the material-change report** it prints, then run `./gradlew check`.

## Reading the report

The generator classifies every difference between the previous baseline and the new data as an **addition**, a **modification** of an existing entry, or a **removal**, per table.

- **Additions only** is the ordinary outcome. New characters got decompositions; nothing already normalized changed. The diff is the delta.
- **A modification or removal in a normalization table stops the build**, and that is the point. The Unicode stability policy forbids changing an existing decomposition or combining class, so if one appears to have changed, either the input is not what it claims to be or an assumption this suite is built on has failed. Investigate it; never regenerate past it. A genuine upstream change of this kind is a new policy, not a rewritten table — every value already derived under the old data would otherwise silently stop matching.
- **Other table families report modifications without failing.** UTS-46 permits a previously disallowed character to change, and UTS-39 permits any confusable mapping to change, so their modules decide what a change means for their policies rather than the generator refusing on their behalf.

## When `verifyUnicodeTables` fails

`./gradlew check` runs it, and it fails when a checked-in generated file is not what the pinned data produces. There are exactly two causes:

- **A generated file was edited by hand.** Undo the edit. Those files carry a "DO NOT EDIT" header for the reason above: an edit changes canonical bytes with no record of why.
- **The data moved without a regeneration.** Run the generate task and commit what it produces.

Never make the check pass by editing a baseline. The baseline is the record of what the previous release said; editing it hides exactly the change the check exists to catch.

## Adding a table set

The generator knows about table *sets* — which files feed one, how it is encoded, which conformance data belongs with it — and nothing about NFC, UTS-46 or UTS-39. A new set means adding its parsing, its `Family` (which fixes its stability rule), and its emitted output. It does not mean touching the existing sets, and no module should ever hand-write or vendor a table instead.
