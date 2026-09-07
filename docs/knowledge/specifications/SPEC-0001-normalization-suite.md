# Normalization Suite Structure

SPEC-0001 · 2026-09-07
Keywords: canonical form, byte stability, blind matching, hash the same value twice, module layout, which module do I depend on, policy id and version, what breaks a stored hash, adding a normalizer, Unicode table versions

How the suite is put together and what each part promises. The reasoning behind these rules, the options rejected on the way, and the questions still open are in [Normalizing identifiers for blind matching](../research/RAD-0001-identifier-and-text-normalization.md).

## The governing property

**The same input produces the same canonical bytes, on any platform, at any time.** Everything else here follows from that. A consumer hashes the canonical form and discards the original, so a one-byte difference is not a degraded match — it is an undetectable, unrecoverable miss.

Two rules enforce it:

- **Determinism across platforms and time.** A normalizer must produce identical bytes on JVM, Android, iOS, wasmJs and JS, and on an application built years ago. Nothing may read platform Unicode tables, which differ by operating-system Unicode version; any table the suite needs is frozen and self-contained.
- **No silent redefinition.** A published policy's output never changes in place. Any rule change is a new, separately-identified version, and callers store the policy identity beside whatever they derived from the output.

## Modules

Group `io.github.aughtone.normalize`. A shared base plus functional modules: **functional modules depend on the base, never the reverse, and never on each other in a cycle.** Each opt-in module carries only its own weight, which is what keeps the base tiny.

| Module | Coordinate | Contains | Depends on | Ships a table? |
| :--- | :--- | :--- | :--- | :--- |
| `:common` | `io.github.aughtone.normalize:common` | the shared contract — `Normalized`, `NormalizationStep`, policy + version base | `aughtone-types` | No |
| `:email` | `io.github.aughtone.normalize:email` | byte-stable email normalizer, and over time other no-table identifier normalizers | `api(:common)` | No — permanent, zero data |
| `:unicode` | `io.github.aughtone.normalize:unicode` | Unicode string forms (NFC/NFD/NFKC/NFKD), domain/URL/punycode, confusables | `api(:common)` + frozen Unicode tables | Yes — pinned, delta-packaged |
| `:phone` | `io.github.aughtone.normalize:phone` | phone → E.164 | `api(:common)` + `aughtone-phonenumber` | No (region metadata, not Unicode) |

`:common` is pure foundation with no concrete normalizer of its own, which is why it is `common` and not `core`. A future usable-standalone convenience module would be the one named `:core`.

**Only `:common` and `:email` are built today.** The rest of the table is the intended shape, not shipped code.

A normalizer belongs in this suite when it is **general, reusable, and fits the versioned-policy contract**. Application-coupled code stays where it is.

## The common contract

Every normalizer shares one shape, defined in `:common`, so the API reads uniformly across email, phone, domain and the rest:

- `normalizeX(value, policy): Outcome<NormalizedX>` — returns the `aughtone-types` `Outcome`. **There is no default policy**: the caller must name one, so output is never produced under rules nobody chose.
- The success value implements `Normalized` — `canonical`, `policyId`, `policyVersion`. Store all three beside anything derived from the canonical string.
- Failure is **explicit and value-free**: a typed error that never echoes the input, so a rejected identifier cannot leak into a log. Never a best-effort result.

## Policies and versioning

A **policy** is a frozen, named rule-set with a stable `id` and `version` — the byte-stability epoch. That identity travels with anything derived from the output.

- **A version is minted only on a *material* change** — when the canonical bytes could actually differ for some input — never per upstream release. A consumer pinned to a version never sees a spurious bump, and a bump always means the canonical form genuinely changed.
- **Unicode-dependent policies are named for the Unicode version they were frozen from** (`NfcPolicyV17`), with the full version recorded in the `id` for audit.
- The four Unicode forms **version independently**: a release may add a compatibility mapping (new epoch for NFKC/NFKD) without any new canonical decomposition (NFC/NFD unchanged).

### Detecting a material change

A build-time check, not a judgement call:

1. Regenerate the tables from the new Unicode UCD and **diff against the frozen baseline**. The Unicode stability policy forbids changing existing mappings, so the diff can only be additions — a modification appearing is a hard stop, to be investigated rather than accepted.
2. **Empty diff → no new epoch. Additions → new epoch**, and the diff *is* the delta.
3. Validate against the new version's official `NormalizationTest.txt`: the new base+delta normalizer must pass 100%, and the old frozen normalizer must pass everything *except* the new-character cases — which proves the delta is exactly the additions and nothing else moved.

### Delta-packaged tables

Because version-to-version changes are purely additive, tables ship as a **base snapshot plus additive deltas**, each separately loadable. `NfcPolicyV17` loads the base; `NfcPolicyV18` loads base + the 17→18 delta. A consumer pinned to an old version therefore does not carry later versions' data — unused deltas are dead-code-eliminated on JS and wasm, and unreferenced on native and JVM. Total shipped bytes are roughly equal to whole-table packaging; the win is selective loading, and it grows as versions accumulate.

## Composition across modules

A normalizer in one module may need a step that lives in another — email optionally punycoding its domain, which needs the Unicode table. The dependency must not become a cycle:

- `:common` defines an **open `interface NormalizationStep`**. Sealed types cannot be extended across module boundaries, so extensibility comes from an interface.
- `:unicode` provides implementations (`NfcPolicyV17`, `PunycodeV17`) and depends on `:common` via `api(...)`.
- `:email` accepts steps **by the `:common` interface**, so it never depends on `:unicode`. A caller wanting email + punycode depends on both and composes: `EmailPolicy.byteStableWith([NfcPolicyV17, PunycodeV17])`.
- The composed policy derives a **deterministic id from the ordered step ids** (`email.byte-stable+nfc.v17+punycode.v17`), so it is self-identifying and reproducible. There is no anonymous composition.
- **Order is validated.** Each step declares a phase (map → normalize → encode) and the builder enforces canonical order. There is one correct order; reordering is degenerate rather than useful (punycode→NFC applies NFC over ASCII, a no-op).

## Email policies as shipped

`ByteStableV1` (id `email.byte-stable`, version 1) is **the** shared canonical form for blind tokenization. It applies only ASCII-level, Unicode-version-independent operations, so it never drifts, expires, or fails when Unicode changes:

- reject unpaired surrogates — an explicit failure, and a permanent well-formed-UTF-8 floor;
- trim ASCII whitespace;
- ASCII-lowercase `A`–`Z` only, leaving non-ASCII untouched so accented local parts are preserved rather than folded;
- strip the `+`-subaddress (RFC 5233);
- domain ASCII-lowercased, raw bytes, no `ToASCII`.

It collapses **no** Unicode variants and encodes **no** provider-specific behaviour — notably not Gmail's treatment of dots as insignificant. The line is: **a universal standard, yes; a single-provider behaviour, no.**

`Lenient` (id `email.lenient`, version 1) is trim + ASCII-lowercase only, for callers who want a reasonable key for display or dedupe and have no byte-stability requirement.

Errors are `MissingAtSign`, `EmptyLocalPart`, `EmptyDomain`, `UnpairedSurrogate` — all value-free.

## Changing any of this

Adding NFC, IDNA/`ToASCII`, or any provider rule to a published policy changes the canonical bytes and orphans every token already derived under it. Such a change is **always** a new policy version and **never** an in-place edit, and it needs an [Architecture Decision Record](../decisions/) before it is written.
