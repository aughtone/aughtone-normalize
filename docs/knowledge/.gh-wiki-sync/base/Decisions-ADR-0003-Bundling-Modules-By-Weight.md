# Bundling modules by weight

ADR-0003 · 2026-09-11 · Status: accepted
Keywords: which module do I depend on, one module per normalizer, why did email move, why is domain not in unicode, where is punycode, where are hostnames, quodlibet, ubilibet, confusables module, bundle size wasmJs iOS, IDNA table size, module layout

## Context

The first release shipped one normalizer per module, `:email`, and [DOC-0001](Specifications-DOC-0001-Normalization-Suite) planned the rest the same way by category: `:financial`, `:net`, and a single `:unicode` holding every table-driven normalizer. Domain normalization becoming the next module to build exposed two problems with that plan.

**A single `:unicode` makes most callers carry data they never use.** Measured against the Unicode 17.0.0 data files: NFC needs roughly 3,050 entries (2,081 canonical decompositions and 968 combining classes); NFKC and NFKD add 3,833; UTS-39 confusables are 6,565 mappings; UTS-46 domain processing adds 9,262 IDNA mapping ranges, 2,323 bidi-class ranges and 542 joining-type ranges. A caller who wants only NFC would ship about eight times the data it uses — and that caller is the wasmJs or iOS client [ADR-0002](Decisions-ADR-0002-Generating-The-Unicode-Tables) is written for. Entry counts are not bytes, and ranges pack far tighter than per-character maps, but the ordering holds.

**The tables carry different stability guarantees.** Normalization is covered by the Unicode stability policy — an existing mapping never changes — which is what makes ADR-0002's base-plus-additive-delta packaging sound. The UTS-46 IDNA mapping table promises backward compatibility with one class of exception: only characters that were disallowed may change status or mapping, so a later table may accept what an earlier one refused but never changes a character it already accepted (Unicode 15.1 made one exceptional change, as part of deprecating transitional processing). UTS-39 confusable data carries no guarantee at all: a later version may map a character to a different target, and stored skeletons must be recomputed when the data version changes.

At the other end, one module per no-table normalizer isolates nothing. Email, PAN, IBAN and IPv6 carry no data, so separate modules only multiply coordinates.

## Decision

**A module boundary sits where what a caller must carry changes — data or a dependency — not between normalizers.**

| Module | Holds | Depends on | Carries |
| :--- | :--- | :--- | :--- |
| `:common` | the shared contract | `aughtone-types` | nothing |
| `:quodlibet` | every normalizer needing no table and no external dependency: email, PAN, IBAN, IPv6, the username base, and slug if it does not transliterate | `:common` | nothing |
| `:phone` | E.164 | `:common` + `aughtone-phonenumber` | region metadata, via the dependency |
| `:unicode` | NFC, NFD, NFKC, NFKD, and any character property more than one module needs | `:common` | normalization tables, shared properties |
| `:confusables` | UTS-39 skeletons | `:unicode` | confusable mappings, mirroring, paired brackets |
| `:ubilibet` | hostnames and domains (UTS-46 nontransitional processing, Punycode per RFC 3492), and URL | `:unicode` | IDNA mapping, joining type |

- **Unicode data is produced only by the ADR-0002 generator, and ships in the module that needs it.** This replaces "nothing outside `:unicode` ships a Unicode table", which only held while every table lived in one module.
- **Every hostname is normalized in `:ubilibet`, ASCII included.** For ASCII input UTS-46 does little more than lowercase and validate, so a separate ASCII hostname rule in `:quodlibet` would give callers two policies producing identical bytes under different identities — the trap [ADR-0001](Decisions-ADR-0001-Supplying-A-Region-To-The-Phone-Normalizer) records for phone.
- **Email moves into `:quodlibet`**, keeping its package `io.github.aughtone.normalize.email`, every public type name and every policy id. No canonical bytes change. `io.github.aughtone.normalize:email:0.0.1` stays on Maven Central, and no later version of that coordinate is published.

The names are a deliberate pair of Latin: `quodlibet`, "whatever you please", is the grab bag of table-free normalizers; `ubilibet`, "wherever you please", holds the names that locate things.

**Rejected: one module per normalizer** — the shape `0.0.1` shipped. It keeps each coordinate narrow, but the no-table normalizers have no weight to isolate, so it multiplies coordinates, build files and release artifacts for nothing a caller would notice in a bundle.

**Rejected: a single `:unicode` holding every table.** The simplest dependency story, and it makes every NFC caller ship IDNA and confusables data. It would also put tables with a stability guarantee and a table with none under one packaging scheme.

**Rejected: ASCII hostnames in `:quodlibet`, internationalized domains in `:ubilibet`.** It kept a table-free hostname rule available, at the cost of two policy identities for identical bytes.

## Consequences

**Easier.** An NFC-only caller carries only normalization data. A domain caller never carries confusables, and a caller wanting a confusable fold never carries IDNA. A new no-table normalizer is a package in `:quodlibet`, not a new module. Each table's stability rules are handled inside the module that owns the table.

**Harder.** `:email` consumers must change one dependency coordinate before they can take any later email change. This is a breaking move, made deliberately at `0.0.1` while the suite is alpha and every known consumer can be told directly.

`:confusables` cannot use ADR-0002's additive-delta packaging as designed, because a later confusables table can change an existing mapping. A policy frozen at one version is still byte-stable — its data never changes — so that module ships each version's table whole instead.

*Corrected 2026-09-11:* an earlier version of this record had `:confusables` and `:ubilibet` each ship their own copy of the bidi-class table, on the grounds that the duplication was small. That was wrong on the case that matters — checking a domain for spoofing needs both modules at once, which is what registries and browsers do — so the rule is now: **a table needed by more than one module lives in `:unicode`; a table needed by exactly one lives in that one.** Bidi class moves to `:unicode`; mirroring and paired brackets stay in `:confusables`, joining type in `:ubilibet`.

That costs an NFC-only caller roughly 15KB of artifact and no memory, since an unreferenced table is never loaded on JVM and is removed by dead-code elimination on JS, wasm and native. Merging the two modules instead was rejected for the obvious reason: it would put 230KB of IDNA data in front of every caller who only wanted a skeleton. A separate shared module was rejected too — a published coordinate, with its own release and versioning surface, for one 15KB table.

A `:quodlibet` caller who wants only IBAN also compiles against the email and IPv6 code. It is code rather than data, and unreferenced code is removed by dead-code elimination on JS and wasm.

**What we gave up.** `:ubilibet` depends on all of `:unicode`, so it carries the NFKC/NFKD data even though UTS-46 needs only NFC — the IDNA mapping table already contains the compatibility mappings. That is the one place the layout breaks its own rule, accepted because splitting `:unicode` in two would add a fourth Unicode module to save a table smaller than the bidi and joining data `:ubilibet` ships regardless. And the module names are not guessable on sight; the module table in DOC-0001 is where a newcomer finds out what each one holds.
