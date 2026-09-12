# Normalization Suite Structure

DOC-0001 · 2026-09-07
Keywords: canonical form, byte stability, blind matching, hash the same value twice, module layout, which module do I depend on, policy id and version, what breaks a stored hash, adding a normalizer, Unicode table versions

How the suite is put together and what each part promises. The reasoning behind these rules, the options rejected on the way, and the questions still open are in [Normalizing identifiers for blind matching](../research/RAD-0001-identifier-and-text-normalization.md).

## The governing property

**The same input produces the same canonical bytes, on any platform, at any time.** Everything else here follows from that. A consumer hashes the canonical form and discards the original, so a one-byte difference is not a degraded match — it is an undetectable, unrecoverable miss.

Two rules enforce it:

- **Determinism across platforms and time.** A normalizer must produce identical bytes on JVM, Android, iOS, wasmJs and JS, and on an application built years ago. Nothing may read platform Unicode tables, which differ by operating-system Unicode version; any table the suite needs is frozen and self-contained.
- **No silent redefinition.** A published policy's output never changes in place. Any rule change is a new, separately-identified version, and callers store the policy identity beside whatever they derived from the output.

## Modules

Group `io.github.aughtone.normalize`. A shared base plus functional modules: **functional modules depend on the base, never the reverse, and never on each other in a cycle.** **A module boundary sits where what a caller must carry changes** — a table or an external dependency — not between normalizers. A caller never carries data it does not use, and the table-free normalizers share one bundle. The reasoning and the layouts rejected are in [ADR-0003](../decisions/ADR-0003-bundling-modules-by-weight.md).

| Module | Coordinate | Contains | Depends on | Ships a table? |
| :--- | :--- | :--- | :--- | :--- |
| `:common` | `io.github.aughtone.normalize:common` | the shared contract — `Normalized`, `NormalizationStep`, policy + version base | `aughtone-types` | No |
| `:quodlibet` | `io.github.aughtone.normalize:quodlibet` | every normalizer needing no table and no external dependency — email, credit-card/PAN, IBAN, IPv6, the username base | `api(:common)` | No — permanent, zero data |
| `:phone` | `io.github.aughtone.normalize:phone` | phone → E.164 | `api(:common)` + `aughtone-phonenumber` | No (region metadata, not Unicode) |
| `:unicode` | `io.github.aughtone.normalize:unicode` | Unicode normalization forms — NFC, NFD, NFKC, NFKD | `api(:common)` | Yes — normalization tables, delta-packaged |
| `:confusables` | `io.github.aughtone.normalize:confusables` | UTS-39 skeletons | `api(:unicode)` | Yes — confusable mappings |
| `:ubilibet` | `io.github.aughtone.normalize:ubilibet` | every hostname and domain, ASCII included (UTS-46, Punycode), and URL | `api(:unicode)` | Yes — IDNA mapping, bidi class, joining type |
| `:email` | `io.github.aughtone.normalize:email` | the email normalizer as published at `0.0.1`. It moves into `:quodlibet`, after which this coordinate is no longer published | `api(:common)` | No |

`:common` is pure foundation with no concrete normalizer of its own, which is why it is `common` and not `core`. A future usable-standalone convenience module would be the one named `:core`.

The two Latin names are a pair: `quodlibet`, "whatever you please", is the grab bag of table-free normalizers, and `ubilibet`, "wherever you please", holds the names that locate things.

**Only `:common` and `:email` are built today.** The rest of the table is the intended shape, not shipped code.

A normalizer belongs in this suite when it is **general, reusable, and fits the versioned-policy contract**. Application-coupled code stays where it is.

## Normalizer roster

What this suite intends to normalize. **This table is the canonical list** — a normalizer that is not here is not planned, and every planned entry carries the issue tracking it, so the roster and the backlog cannot quietly diverge. Adding an entry means filing its issue at the same time.

| Normalizer | Module | Needs a table | Tracked by |
| :--- | :--- | :--- | :--- |
| Email | `:email`, moving to `:quodlibet` | no | **built — published `0.0.1`**; the move is [#12](https://github.com/aughtone/aughtone-normalize/issues/12) |
| Phone → E.164 | `:phone` | no (region metadata, not Unicode) | [#1](https://github.com/aughtone/aughtone-normalize/issues/1) |
| NFC / NFD / NFKC / NFKD | `:unicode` | yes | [#2](https://github.com/aughtone/aughtone-normalize/issues/2) |
| Hostname / domain / punycode (UTS-46) | `:ubilibet` | yes | [#10](https://github.com/aughtone/aughtone-normalize/issues/10) |
| URL | `:ubilibet` | yes (the host is a hostname) | [#7](https://github.com/aughtone/aughtone-normalize/issues/7) |
| Confusables / skeleton (UTS-39) | `:confusables` | yes | [#11](https://github.com/aughtone/aughtone-normalize/issues/11) |
| Credit-card / PAN | `:quodlibet` | no | [#3](https://github.com/aughtone/aughtone-normalize/issues/3) |
| IBAN / bank account | `:quodlibet` | no | [#3](https://github.com/aughtone/aughtone-normalize/issues/3) |
| IPv6 | `:quodlibet` | no | [#4](https://github.com/aughtone/aughtone-normalize/issues/4) |
| Username / handle | `:quodlibet` base + optional confusable fold from `:confusables` | optional | [#8](https://github.com/aughtone/aughtone-normalize/issues/8) |
| Slug | undecided — see the issue | **contested** | [#5](https://github.com/aughtone/aughtone-normalize/issues/5) |

Two entries carry a question about whether they belong here at all, and both are recorded on their issues rather than settled here. **Slug** is deliberately lossy and transliterating, which is table-driven — so it is neither clearly no-table nor clearly an identity. **URL** has more optional structure than an identity normally does, and some plausible normalizations of it change meaning rather than spelling.

The no-table normalizers share `:quodlibet`; a table-driven normalizer lives in the module that carries its table. **Unicode data is produced only by the table generator ([ADR-0002](../decisions/ADR-0002-generating-the-unicode-tables.md), built under [#9](https://github.com/aughtone/aughtone-normalize/issues/9)), and ships in the module that needs it.**

## The common contract

Every normalizer shares one shape, defined in `:common`, so the API reads uniformly across email, phone, domain and the rest:

- `normalizeX(value, policy): Outcome<NormalizedX>` — returns the `aughtone-types` `Outcome`. **There is no default policy**: the caller must name one, so output is never produced under rules nobody chose.
- The success value implements `Normalized` — `canonical`, `policyId`, `policyVersion`. Store all three beside anything derived from the canonical string.
- Failure is **explicit and value-free**: a typed error that never echoes the input, so a rejected identifier cannot leak into a log. Never a best-effort result.
- **Matching is scoped by policy identity.** Two values normalized under different policies never match, even where their canonical strings happen to agree. Parties that must match each other have to agree on the same policy constant, not merely on a similar rule. This holds for every pair of policies, not for any one of them.

## Policies and versioning

A **policy** is a frozen, named rule-set with a stable `id` and `version` — the byte-stability epoch. That identity travels with anything derived from the output.

- **A policy named `Lenient` relaxes one or more of its module's default rules and keeps every suite guarantee**: frozen bytes, identical output on every platform, errors that never echo the input value, refusal of input that cannot be parsed at all, and no guessing. It is a more permissive rule, never a weaker contract. It may accept what the default refuses; it may never invent data the input did not contain.
- **A version is minted only on a *material* change** — when the canonical bytes could actually differ for some input — never per upstream release. A consumer pinned to a version never sees a spurious bump, and a bump always means the canonical form genuinely changed.
- **Unicode-dependent policies are named for the Unicode version they were frozen from** (`NfcPolicyV17`), with the full version recorded in the `id` for audit.
- The four Unicode forms **version independently**: a release may add a compatibility mapping (new epoch for NFKC/NFKD) without any new canonical decomposition (NFC/NFD unchanged).

### Detecting a material change

A build-time check, not a judgement call. As written it applies to the normalization tables in `:unicode`, whose changes the Unicode stability policy guarantees are additive. The IDNA tables in `:ubilibet` may also change characters that were previously disallowed, and the confusable mappings in `:confusables` may change any mapping between versions, so those two modules adapt the check rather than inherit it ([ADR-0003](../decisions/ADR-0003-bundling-modules-by-weight.md)):

1. Regenerate the tables from the new Unicode UCD and **diff against the frozen baseline**. The Unicode stability policy forbids changing existing mappings, so the diff can only be additions — a modification appearing is a hard stop, to be investigated rather than accepted.
2. **Empty diff → no new epoch. Additions → new epoch**, and the diff *is* the delta.
3. Validate against the new version's official `NormalizationTest.txt`: the new base+delta normalizer must pass 100%, and the old frozen normalizer must pass everything *except* the new-character cases — which proves the delta is exactly the additions and nothing else moved.

### Delta-packaged tables

Because version-to-version changes are purely additive, tables ship as a **base snapshot plus additive deltas**, each separately loadable. `NfcPolicyV17` loads the base; `NfcPolicyV18` loads base + the 17→18 delta. A consumer pinned to an old version therefore does not carry later versions' data — unused deltas are dead-code-eliminated on JS and wasm, and unreferenced on native and JVM. Total shipped bytes are roughly equal to whole-table packaging; the win is selective loading, and it grows as versions accumulate.

## Composition across modules

A normalizer in one module may need a step that lives in another — email optionally punycoding its domain, which needs the IDNA tables. The dependency must not become a cycle:

- `:common` defines an **open `interface NormalizationStep`**. Sealed types cannot be extended across module boundaries, so extensibility comes from an interface.
- The table-carrying modules provide implementations — `NfcPolicyV17` from `:unicode`, `PunycodeV17` from `:ubilibet` — and each depends on `:common` via `api(...)`.
- `:quodlibet` accepts steps **by the `:common` interface**, so it never depends on a table-carrying module. A caller wanting email + punycode depends on both and composes: `EmailPolicy.byteStableWith([NfcPolicyV17, PunycodeV17])`.
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

`Lenient` (id `email.lenient`, version 1) is trim + ASCII-lowercase only, keeping the `+`-subaddress. It relaxes that one rule of `ByteStableV1` and keeps every other guarantee — its bytes are frozen and identical on every platform — so it is as usable for tokens as `ByteStableV1`, provided everyone matching uses it.

Errors are `MissingAtSign`, `EmptyLocalPart`, `EmptyDomain`, `UnpairedSurrogate` — all value-free.

## Changing any of this

Adding NFC, IDNA/`ToASCII`, or any provider rule to a published policy changes the canonical bytes and orphans every token already derived under it. Such a change is **always** a new policy version and **never** an in-place edit, and it needs an [Architecture Decision Record](../decisions/README.md) before it is written.
