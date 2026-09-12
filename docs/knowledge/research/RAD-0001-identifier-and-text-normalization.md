# Normalizing for blind matching

RAD-0001 · 2026-09-07 · status: recommended
Keywords: same email hashes differently on iOS and Android, blind tokenization, breach-safe token, why not NFC, why not java.text.Normalizer, Gmail dots and plus addressing, IDNA ToASCII, Unicode version drift, canonical email, hash mismatch across app versions, why not libphonenumber for this
Measured against: Kotlin 2.4.0, `io.github.aughtone:types` 3.4.0, targets jvm / android / iosX64 / iosArm64 / iosSimulatorArm64 / js / wasmJs / linuxX64, 2026-09-08. The email module's 17 tests run green on jvm, js, wasmJs, iosSimulatorArm64 and linuxX64 — 85 executions of the same suite across five runtimes. `iosX64` is compiled and linked but not executed, for want of an Intel runner.

The settled rules that came out of this are written up as [Normalization Suite Structure](../specifications/DOC-0001-normalization-suite.md); this record is the reasoning behind them and the questions still open.

## Question

How do you normalize an identifier so that hashing it produces a usable match token — when the hashing happens on several platforms, in application builds years apart, and the original value is discarded immediately afterwards?

The founding case was email for a blind tokenizer. Two consumers need it: one doing blind tokenization server-side, and one doing contact discovery that hashes address books **on the client** — Android, iOS and web. Both must produce the *same* bytes for the same address, or the match silently fails and there is nothing left to debug with.

The wider question is whether that constraint generalizes: phone, domain and URL, general Unicode forms, and ordinary normalization needs like search and dedupe.

## Trail

**Platform Unicode normalization was ruled out first, and it is the decision everything else hangs off.** `java.text.Normalizer`, `NSString.precomposedStringWithCanonicalMapping` and `String.prototype.normalize` all read the *operating system's* Unicode tables. Those differ by OS Unicode version, so the same address normalized on a 2023 Android build and a 2026 iOS build can differ by a byte — and because the caller has already discarded the original, the mismatch is undetectable and unrecoverable. This is what forced frozen, self-contained data for anything table-dependent, and it is why the shipped email policy consults no table at all.

**Provider-specific rules were considered and rejected.** Gmail treats dots in the local part as insignificant, and folding them would genuinely improve matching for that provider. Three things killed it. It is non-standard, so nothing obliges the provider to keep behaving that way. It is unknowable in general — there is no way to enumerate which of the world's mail hosts do what. And a frozen provider list could never *grow*: adding a provider later changes the canonical bytes for every address at that domain, splitting historical tokens from new ones. The line that survived is **a universal standard, yes; a single-provider behaviour, no** — which is why RFC 5233 `+`-subaddressing is stripped and dots are not.

**Non-ASCII case folding was rejected for the same reason.** Lowercasing beyond `A`–`Z` requires a Unicode case table, which reintroduces exactly the drift the design exists to avoid. Accented local parts pass through untouched, and `Ä@example.com` does not fold to `ä@example.com`. This is a deliberate under-matching: the alternative is a match that works until someone's OS updates.

**Unpaired surrogates were the one remaining cross-platform byte ambiguity**, so they became an explicit failure rather than something to encode past. A well-formed address never contains one, and encoding a broken half-character to bytes is not consistent across platforms.

**A single module was considered and rejected in favour of a family.** The Unicode tables are large and most callers never need them; a monolith would make every consumer of the email normalizer carry them. The family shape lets `:email` ship permanently table-free while `:unicode` carries the weight only for callers who opt in.

**Supporting multiple Unicode epochs looked like it would cost a full table per version.** It does not, because the Unicode stability policy makes version-to-version changes purely additive — so a base snapshot plus additive deltas gives the same total bytes with selective loading, and a consumer pinned to an old epoch does not drag later versions' data into a wasmJs or iOS bundle. That consumer — the byte-stability caller pinning an old version — is precisely the one who would have been hurt worst by whole-table packaging.

**Composition across modules nearly became a dependency cycle.** Email may want to punycode its domain, which needs `:unicode`. Sealed types cannot be extended across module boundaries, so the extension point had to be an open interface in `:common`, with the caller composing the two modules rather than `:email` depending on `:unicode`.

## Findings

- **The constraint generalizes.** Every identifier normalizer wants the same shape: a named policy, a canonical string, and the policy identity travelling with anything derived from it. That shape became the `:common` contract.
- **Byte-stability and ordinary normalization are the same interface with different policies**, not two systems. `ByteStableV1` and `ByteStableV1Lenient` differ only in their rules.
- **A no-table canonical form is achievable for email and is genuinely permanent.** ASCII trim, ASCII-lowercase, RFC 5233 subaddress stripping and a surrogate check need no Unicode data, so the policy cannot drift when Unicode ships a new version. Measured: 17 tests green across jvm, js, wasmJs and iOS simulator.
- **Determinism has to be designed for, not tested for.** Every rejected option above would have passed a single-platform test suite. The failures only appear across an OS upgrade or an old app build, which is to say in production and without a signal.
- **Some things that look adjacent are a different concern.** Geo encodings (Open Location Code, geohash, GeoJson) encode coordinates rather than normalizing identity, and stay in their own repositories. Application-coupled formatters stay with their application; one of them is a port of Google's libaddressinput and is a plausible future extraction, but on its own terms rather than as part of this suite.

## Open questions

- ~~Whether `:unicode` **vendors** a frozen NFC/UTS-46 implementation or **generates its own** tables from the public UCD.~~ **Settled** — generate from the UCD, see [ADR-0002](../decisions/ADR-0002-generating-the-unicode-tables.md). The delta packaging and the material-change check both depend on it.
- Whether NFKC/NFKD ship in the first `:unicode` cut or a follow-up. They are lossy compatibility forms — ligatures, width, superscripts — and the risk is someone reaching for NFKC as though it were canonical, so the lossiness needs labelling loudly wherever they appear.
- ~~Major versus major.minor Unicode version naming (`V17` against `V170`).~~ **Settled** — major only, as `U17` in a constant and `u17` in an id, with the minor appended only when it is not zero (`u15-1`). `U` is the data version and `V` the policy version, which is why they no longer share a letter.

The phone region question that came out of building the roster is also settled — two policies, `E164` and `e164ForRegion(region)`, neither ever defaulting: [ADR-0001](../decisions/ADR-0001-supplying-a-region-to-the-phone-normalizer.md).

An ADR is owed on each remaining question above once it settles.

## Recommendation

Build the suite as the family of modules described in [DOC-0001](../specifications/DOC-0001-normalization-suite.md), in this order:

1. ~~**Publish `0.0.1`**~~ — **done, 2026-09-08.** `io.github.aughtone.normalize:email:0.0.1` and `:common:0.0.1` are on Maven Central.
2. **Build domain normalization** — the next module consumers need — in three steps: the Unicode table generator, then NFC in `:unicode`, then hostnames, domains and punycode in `:ubilibet`. The generator is where [ADR-0002](../decisions/ADR-0002-generating-the-unicode-tables.md) and the delta-table design get realized, and it is the largest single piece of work remaining. The modules are bundled by the data each one carries, not one per normalizer: [ADR-0003](../decisions/ADR-0003-bundling-modules-by-weight.md).
3. **Build `:phone`** on `aughtone-phonenumber`, which is already published.
4. **Move email into `:quodlibet` and add the remaining normalizers** — the full list, with the issue tracking each, is the roster in [DOC-0001](../specifications/DOC-0001-normalization-suite.md#normalizer-roster). The slug question raised here has since been settled the other way: it is a display artifact rather than an identity, and it is [out of scope](../reference/out-of-scope/slug-normalization.md).

**What would change the answer:** a Unicode release that modifies rather than adds a *normalization* mapping would break the additive-delta assumption for `:unicode` and force a rethink of the packaging. Confusable mappings already carry no such guarantee, which is why `:confusables` cannot assume it. A standards-track specification for provider-level address equivalence would reopen the provider-rules question — but only a standard would, not a provider's own documentation.

## Current state

`:common` and `:email` were **published at `0.0.1`** (2026-09-08) — `io.github.aughtone.normalize:email:0.0.1` and `io.github.aughtone.normalize:common:0.0.1`, both on Maven Central, tagged `v0.0.1`. The email normalizer has since moved into `:quodlibet` per [ADR-0003](../decisions/ADR-0003-bundling-modules-by-weight.md), keeping its package and its canonical output; `email:0.0.1` remains on Central and is not republished.

Releases are cut by pushing to `master`, which triggers the publish workflow; see [Publishing a Release](../guides/DOC-0005-publishing.md). The Maven Central and signing credentials are **organization** secrets on the GitHub org rather than repository secrets, and on a Free plan those resolve only for **public** repositories — which is why this repository had to be made public before the first publish would work.

The suite depends on `io.github.aughtone:types` `3.4.0`, which exposes `Outcome.Success` and `Outcome.Failure(exception: Throwable)`, built via `runOutcome { }` (throw to fail). `Outcome.Error` survives there only as a deprecated typealias to `Failure`; this suite uses `Failure` throughout and should not reintroduce the old name.

**Coordinate with consumers before ever changing the canonical form.** Both known consumers need identical bytes, and the settled contract is `ByteStableV1`, id `email.byte-stable`. An earlier draft used the id `email.canonical`; it changed before publication. **Both known consumers were carrying the draft id and were corrected at publish** — neither had minted tokens under it, so nothing was orphaned, but it was caught by asking rather than by anything failing. Any future guidance to a consumer must name the policy constant and its id explicitly, never just "the canonical form".
