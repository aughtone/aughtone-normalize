# Identifier & Text Normalization — Design

Status: working design (not yet an ADR). Captures the decisions made while designing the normalization modules so we can see the whole shape before building.

## Purpose

A public, Kotlin Multiplatform suite for **normalizing identifiers and text to a canonical form**, primarily so a value can be hashed into a breach-safe token for **blind matching** — where the same input must produce the *same bytes* on every platform and over time, or the match silently fails. It also serves ordinary normalization needs (search, dedupe, display) through the same uniform interface.

The founding case is email normalization for a blind tokenizer; the design generalizes to phone, domain/URL, and other identifiers, plus general Unicode normalization.

## The governing property

Everything serves one property: **the same input produces the same canonical bytes, on any platform, at any time.** A blind tokenizer hashes the canonical form and discards the original, so a one-byte difference is an undetectable, unrecoverable miss.

Two consequences drive the whole design:
- **Determinism across platforms and time.** A normalizer must produce identical bytes on JVM, Android, iOS, wasmJs, and JS, and on an app build from years ago. This rules out anything that reads platform Unicode tables (they differ by OS Unicode version) and mandates frozen, self-contained data.
- **No silent redefinition.** A published policy's output must never change in place; any rule change is a new, separately-identified version. A caller stores the policy identity beside the token so the exact rules can be reproduced forever.

## Module structure

Three modules, split so the lean core carries no heavy dependency and each satellite is opt-in. The rule is strict: **satellites depend on the core, never the reverse, and never on each other in a cycle.**

| Module | Contains | Depends on | Ships a table? |
| :--- | :--- | :--- | :--- |
| `:normalize` | the common contract + byte-stable **identifier** normalizers (email, and other no-table normalizers) | `aughtone-types` only | No — permanent, zero data |
| `:normalize-unicode` | Unicode string forms (NFC/NFD/NFKC/NFKD) + domain/URL/punycode + confusables | `api(:normalize)` + frozen Unicode tables | Yes — pinned, delta-packaged |
| `:normalize-phone` | phone → E.164 | `api(:normalize)` + `aughtone-phonenumber` | No (region metadata, not Unicode) |

Inclusion criterion for a normalizer: **general, reusable, and it fits the versioned-policy contract.** App-coupled or different-concern code stays where it is.

## The common contract

Every normalizer in the suite shares one shape, defined in `:normalize`, so the API reads uniformly across email, phone, domain, and the rest:

- `normalizeX(value, policy): Outcome<NormalizedX>` — returns the aughtone-types `Outcome`; no default policy (the caller must name one, so output is never produced under rules nobody chose).
- The success value carries the **canonical string plus the policy `id` + `version`** to store beside any derived hash.
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

The email normalizer lives in `:normalize`, but may optionally apply steps that need the Unicode table (e.g. punycode the domain), which live in `:normalize-unicode`. To let the core use an extension without depending on it:

- `:normalize` defines an **open `interface NormalizationStep`** (the extension point). Sealed types cannot be extended across modules, so the extensibility comes from an interface, not from adding sealed subtypes.
- `:normalize-unicode` provides implementations — `NfcPolicyV17`, `PunycodeV17`, … — and depends on `:normalize` via `api(...)`.
- Policies compose via `EmailPolicy.byteStableWith([NfcPolicyV17, PunycodeV17])` rather than a combinatorial explosion of named constants. The composed policy derives a **deterministic id from the ordered step ids** (e.g. `email.byte-stable+nfc.v17+punycode.v17`), so it is self-identifying and reproducible — no anonymous composition.
- **Order is validated.** Each step declares a phase (map → normalize → encode); `byteStableWith(...)` enforces canonical order, because there is one correct order and reordering is not useful (punycode→NFC is degenerate — NFC over ASCII is a no-op). If a genuinely useful custom-order case ever appears, an explicit `unsafeOrdered(...)` escape hatch is added then, not preemptively.
- A few named defaults are provided for the common combinations; the builder is the general path.

## The email normalizer (built, in `:normalize`)

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
| Email | `:normalize` | no | built |
| Phone → E.164 | `:normalize-phone` | no (region metadata) | planned (on `aughtone-phonenumber`) |
| Domain / punycode (IDN) | `:normalize-unicode` | yes | planned |
| URL | `:normalize-unicode` | yes (host is IDN) | planned — reuse the URL types just released in `aughtone-types` |
| NFC / NFD / NFKC / NFKD | `:normalize-unicode` | yes | planned |
| Confusables / skeleton (UTS-39) | `:normalize-unicode` | yes | planned — anti-spoofing; reuses the vendored table |
| Credit-card / PAN (strip separators, Luhn) | `:normalize` | no | planned — same blind-tokenization pattern |
| Username / handle | `:normalize` (+ optional unicode confusable-fold) | optional | planned |
| IBAN / bank account | `:normalize` | no | planned |
| IPv6 / hostname (zero-compression canonical) | `:normalize` | no | planned |
| Slug | `:normalize` | no | planned — adopt an existing implementation; note slugging often transliterates (lossy), so treat as its own policy family |

## Scope boundaries

- **Geo encodings** (Open Location Code, geohash, GeoJson) are a different concern — coordinate *encoding*, not identity normalization. They keep their own homes (`aughtone-openlocationcode`, `aughtone-geohash`, the types GeoJson model); they are not forced into `:normalize`.
- **An application's own capability formatters** (address input, geo formatting) are app-coupled and stay put. The address-input one is a port of Google's libaddressinput and is a future extraction candidate (like `libphonenumber` → `aughtone-phonenumber`), but out of scope here.
- A deliberate inventory-and-consolidation pass over scattered formatters is worthwhile later, gated by the inclusion criterion above — not a boil-the-ocean sweep now.

## Open questions

- Whether `:normalize-unicode` **vendors** a frozen NFC/UTS-46 implementation (e.g. from kuri, MIT) or **generates its own** tables from the public UCD. Deferred to when that module is built; the module boundary makes it an easy, opt-in decision.
- An ADR will be written once these questions are fully settled.
