# Identifier & Text Normalization — Design

Status: working design (not yet an ADR). Captures the decisions made while designing the normalization suite so the whole shape is visible before building. A **Current state, consumers & next steps** section at the end is the handoff starting point for whoever picks this up.

## Purpose

A public, Kotlin Multiplatform suite for **normalizing identifiers and text to a canonical form**, primarily so a value can be hashed into a breach-safe token for **blind matching** — where the same input must produce the *same bytes* on every platform and over time, or the match silently fails. It also serves ordinary normalization needs (search, dedupe, display) through the same uniform interface.

The founding case is email normalization for a blind tokenizer; the design generalizes to phone, domain/URL, and other identifiers, plus general Unicode normalization.

## The governing property

Everything serves one property: **the same input produces the same canonical bytes, on any platform, at any time.** A blind tokenizer hashes the canonical form and discards the original, so a one-byte difference is an undetectable, unrecoverable miss.

Two consequences drive the whole design:
- **Determinism across platforms and time.** A normalizer must produce identical bytes on JVM, Android, iOS, wasmJs, and JS, and on an app build from years ago. This rules out anything that reads platform Unicode tables (they differ by OS Unicode version) and mandates frozen, self-contained data.
- **No silent redefinition.** A published policy's output must never change in place; any rule change is a new, separately-identified version. A caller stores the policy identity beside the token so the exact rules can be reproduced forever.

## Module structure

The suite is the repo `aughtone-normalize`, group `io.github.aughtone.normalize`. It is organized as a family of modules (like the format suite): a shared base plus functional modules. The rule is strict: **functional modules depend on the base, never the reverse, and never on each other in a cycle.** Each opt-in module pulls only its own weight, keeping the base tiny.

| Module | Coordinate | Contains | Depends on | Ships a table? |
| :--- | :--- | :--- | :--- | :--- |
| `:common` | `io.github.aughtone.normalize:common` | the shared contract — `Normalized`, `NormalizationStep`, policy + version base | `aughtone-types` | No |
| `:email` | `io.github.aughtone.normalize:email` | byte-stable email normalizer (and, over time, other no-table identifier normalizers) | `api(:common)` | No — permanent, zero data |
| `:unicode` | `io.github.aughtone.normalize:unicode` | Unicode string forms (NFC/NFD/NFKC/NFKD) + domain/URL/punycode + confusables | `api(:common)` + frozen Unicode tables | Yes — pinned, delta-packaged |
| `:phone` | `io.github.aughtone.normalize:phone` | phone → E.164 | `api(:common)` + `aughtone-phonenumber` | No (region metadata, not Unicode) |

A future usable-standalone convenience form would be named `:core`/`:basic`/`:simple` and depend on `:common` — `:common` is pure foundation with no concrete normalizer of its own, which is why it is `common` and not `core`.

Inclusion criterion for a normalizer: **general, reusable, and it fits the versioned-policy contract.** App-coupled or different-concern code stays where it is.

## The common contract (`:common`)

Every normalizer in the suite shares one shape, defined in `:common`, so the API reads uniformly across email, phone, domain, and the rest:

- `normalizeX(value, policy): Outcome<NormalizedX>` — returns the aughtone-types `Outcome`; no default policy (the caller must name one, so output is never produced under rules nobody chose).
- The success value implements **`Normalized`** — the canonical string plus the policy `id` + `version`, to store beside any derived hash.
- Failure is **explicit and value-free**: a typed error that never echoes the input (so a rejected identifier cannot leak into a log), never a best-effort result.

## Policies and versioning

A **policy** is a frozen, named rule-set with a stable `id` and `version` — the byte-stability epoch. The identity travels with anything derived from the output. Published output never changes in place.

- **A version is minted only on *material* change** — when the canonical bytes could actually differ for some input — never per upstream release. A consumer pinned to a version therefore never sees a spurious bump, and a bump always means "the canonical form genuinely changed; treat it as a new epoch."
- **Unicode-dependent policies are named after the Unicode version they were frozen from** — `NfcPolicyV17` — with the full version recorded in the `id` for audit. If a later Unicode release carries a consequential normalization change it is a new epoch (`V171`/`V18`); a release with no normalization-relevant change mints nothing.
- The four Unicode forms **version independently**: a release may add a compatibility mapping (new epoch for NFKC/NFKD) without any new canonical decomposition (NFC/NFD unchanged).

### Detecting a material change

Whether a new Unicode version needs a new epoch is a build-time check, not a judgement call:

1. Regenerate the tables from the new Unicode UCD and **diff against the current frozen baseline.** The stability policy forbids changing existing mappings, so the diff can only be *additions*. (A modification appearing is a hard stop — investigate.)
2. **Empty diff → no new epoch.** **Additions → new epoch**, and the diff *is* the delta.
3. Validate against the new version's official `NormalizationTest.txt`: the new (base+delta) normalizer must pass 100%; the old frozen normalizer must pass everything *except* the new-character cases — proving the delta is exactly the additions and nothing else moved.

### Delta-packaged tables

Supporting multiple Unicode epochs must not cost a full table per version. Because the stability policy makes version-to-version changes purely additive, tables are packaged as a **base snapshot plus additive deltas**, each delta a separately-loadable unit:

- `NfcPolicyV17` loads the base; `NfcPolicyV18` loads base + the 17→18 delta.
- A consumer pinned to an old version therefore does **not** carry later versions' data — the unused deltas are dead-code-eliminated on JS/wasm and unreferenced on native/JVM. This matters most for exactly the byte-stability caller who pins an old version and would otherwise drag every future version's data into a wasmJs/iOS bundle.
- Total shipped bytes are ~equal to a whole-table approach; the win is selective loading, and it grows as versions accumulate.

## Composition and dependency inversion

The email normalizer lives in `:email`, but may optionally apply steps that need the Unicode table (e.g. punycode the domain), which live in `:unicode`. To let email use an extension without a dependency cycle:

- `:common` defines an **open `interface NormalizationStep`** (the extension point). Sealed types cannot be extended across modules, so the extensibility comes from an interface, not from adding sealed subtypes.
- `:unicode` provides implementations — `NfcPolicyV17`, `PunycodeV17`, … — and depends on `:common` via `api(...)`.
- `:email` accepts steps by the `:common` interface, so a caller who wants email+punycode depends on both `:email` and `:unicode` and composes via `EmailPolicy.byteStableWith([NfcPolicyV17, PunycodeV17])`. `:email` never depends on `:unicode`.
- The composed policy derives a **deterministic id from the ordered step ids** (e.g. `email.byte-stable+nfc.v17+punycode.v17`), so it is self-identifying and reproducible — no anonymous composition.
- **Order is validated.** Each step declares a phase (map → normalize → encode); `byteStableWith(...)` enforces canonical order, because there is one correct order and reordering is not useful (punycode→NFC is degenerate — NFC over ASCII is a no-op). If a genuinely useful custom-order case ever appears, an explicit `unsafeOrdered(...)` escape hatch is added then, not preemptively.
- A few named defaults are provided for the common combinations; the builder is the general path.

## The email normalizer (built, in `:email`)

The shared byte-stable canonical form (`EmailPolicy.ByteStableV1`, id `email.byte-stable`) and a looser `EmailPolicy.Lenient`.

`ByteStableV1` applies only ASCII-level, Unicode-version-independent operations, so it never drifts, expires, or fails when Unicode changes, and is byte-identical everywhere:

- reject unpaired surrogates (explicit failure — a permanent well-formed-UTF-8 floor, the only remaining cross-platform byte ambiguity);
- trim ASCII whitespace;
- ASCII-lowercase (`A`–`Z` only; non-ASCII left untouched, so accented local parts are preserved, not folded);
- strip the `+`-subaddress (RFC 5233 — a standard, applied uniformly);
- domain ASCII-lowercased, raw bytes, no `ToASCII`;
- the canonical string is what the caller hashes.

It collapses **no** Unicode variants and encodes **no** provider-specific behaviour (e.g. Gmail treating dots as insignificant): that is non-standard and unknowable in general, and a frozen provider list could never grow without splitting historical tokens. The line is **a universal standard, yes; a single-provider behaviour, no.** Adding NFC or IDNA/ToASCII later is a new policy version, never an in-place change.

`Lenient` is trim + ASCII-lowercase only — for callers wanting a reasonable key (display, dedupe) with no byte-stability requirement.

## Normalizer roster

| Normalizer | Module | Needs table | Status |
| :--- | :--- | :--- | :--- |
| Email | `:email` | no | built |
| Phone → E.164 | `:phone` | no (region metadata) | planned (on `aughtone-phonenumber`) |
| Domain / punycode (IDN) | `:unicode` | yes | planned |
| URL | `:unicode` | yes (host is IDN) | planned — reuse the URL types released in `aughtone-types` |
| NFC / NFD / NFKC / NFKD | `:unicode` | yes | planned |
| Confusables / skeleton (UTS-39) | `:unicode` | yes | planned — anti-spoofing; reuses the vendored table |
| Credit-card / PAN (strip separators, Luhn) | a no-table module | no | planned — same blind-tokenization pattern |
| Username / handle | base + optional unicode confusable-fold | optional | planned |
| IBAN / bank account | a no-table module | no | planned |
| IPv6 / hostname (zero-compression canonical) | a no-table module | no | planned |
| Slug | a no-table module | no | planned — adopt an existing implementation; note slugging often transliterates (lossy), so treat as its own policy family |

The no-table identifier normalizers can grow as their own suite modules (e.g. `:financial` for credit-card/IBAN, `:net` for IPv6/hostname, `:slug`), each depending only on `:common`.

## Scope boundaries

- **Geo encodings** (Open Location Code, geohash, GeoJson) are a different concern — coordinate *encoding*, not identity normalization. They keep their own homes (`aughtone-openlocationcode`, `aughtone-geohash`, the types GeoJson model); they are not pulled into this suite.
- **An application's own capability formatters** (address input, geo formatting) are app-coupled and stay put. The address-input one is a port of Google's libaddressinput and is a future extraction candidate (like `libphonenumber` → `aughtone-phonenumber`), but out of scope here.
- A deliberate inventory-and-consolidation pass over scattered formatters is worthwhile later, gated by the inclusion criterion above — not a boil-the-ocean sweep now.

## Open questions

- Whether `:unicode` **vendors** a frozen NFC/UTS-46 implementation (e.g. from kuri, MIT) or **generates its own** tables from the public UCD. Deferred to when that module is built; the module boundary makes it an opt-in decision. If vendoring, retain the source's licence + attribution (as the format repo does for CLDR).
- Whether NFKC/NFKD ship in the first `:unicode` cut or a follow-up. They are lossy compatibility forms (ligatures, width, superscripts) — offer all four for completeness, but label the lossiness loudly so no one uses NFKC as if it were canonical.
- Major-vs-major.minor Unicode version naming (`V17` vs `V170`). The full version is recorded in the `id` regardless.
- An ADR will be written once these settle.

## Current state, consumers & next steps (handoff)

**Built and committed** (repo `aughtone-normalize`, branch `develop`, version `0.0.1`):
- `:common` — the `Normalized` interface.
- `:email` — `normalizeEmail(value, policy): Outcome<NormalizedEmail>`, `EmailPolicy.ByteStableV1` (id `email.byte-stable`) and `EmailPolicy.Lenient`, `NormalizedEmail : Normalized`, typed value-free `EmailNormalizationError` (`MissingAtSign`/`EmptyLocalPart`/`EmptyDomain`/`UnpairedSurrogate`). 17 tests green on jvm/iOS/js/wasmJs.
- Depends on `io.github.aughtone:types:3.3.0`, which exposes `Outcome.Success` / `Outcome.Error(exception: Throwable)`, built via `runOutcome { }` (throw to fail). Types `3.4.0` renames `Error` → `Failure`; stay on `3.3.0`/`Error` until the dependency is bumped, then migrate. Watch Maven Central for `3.4.0` rather than waiting on a ping.
- Release/CI/convention infrastructure mirrors `aughtone-format`: `.github/workflows` (test on `develop`, publish on push to `master`), `CHANGELOG.md`, `AGENTS.md`/`CLAUDE.md`/`GEMINI.md`/`WORKFLOW.md`, `docs/` system.

**Not yet published.** To publish `0.0.1`: configure the repo's GitHub secrets (`MAVEN_CENTRAL_USERNAME`/`PASSWORD`, `SIGNING_KEY_ID`, `SIGNING_PASSWORD`, `GPG_KEY_CONTENTS`), then merge `develop` → a release branch → `master`; the workflow tags `v0.0.1`, creates the release, and runs `publishToMavenCentral`.

**Consumers (coordinate before ever changing the canonical form):**
- **A blind-tokenization consumer** — hashes the canonical email into a breach-safe token.
- **A client-side contact-discovery consumer** — hashes address books on the **client** (Android/iOS/web), which is why byte-identical output across platforms and app versions is non-negotiable (this ruled out platform NFC and any frozen provider list).
- Both need the *same* canonical bytes. The settled contract is `ByteStableV1` / id `email.byte-stable`. An earlier draft used the id `email.canonical`; it was changed to `email.byte-stable` before publication, so a consumer still holding the old id adopts the final one at publish. **After publishing, confirm the final coordinate `io.github.aughtone.normalize:email:0.0.1`, policy `ByteStableV1`, id `email.byte-stable` with every consumer.**

**Immediate next steps:**
1. Publish `0.0.1` and notify the consumers of the final coordinate/id.
2. Build `:unicode` — NFC/NFD/NFKC/NFKD + domain/punycode + confusables. This is where the vendor-vs-generate table decision and the delta-table design are realized.
3. Build `:phone` on `aughtone-phonenumber` (published as `io.github.aughtone:phonenumber`, epoch 9.0.38).
4. Add the remaining no-table normalizers per the roster.
