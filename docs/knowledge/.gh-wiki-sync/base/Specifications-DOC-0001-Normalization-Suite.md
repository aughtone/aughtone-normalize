# Normalization Suite Structure

DOC-0001 · 2026-09-07
Keywords: canonical form, byte stability, blind matching, hash the same value twice, module layout, which module do I depend on, policy id and version, what breaks a stored hash, adding a normalizer, Unicode table versions

How the suite is put together and what each part promises. The reasoning behind these rules, the options rejected on the way, and the questions still open are in [Normalizing identifiers for blind matching](Research-RAD-0001-Identifier-And-Text-Normalization).

## The governing property

**The same input produces the same canonical bytes, on any platform, at any time.** Everything else here follows from that. A consumer hashes the canonical form and discards the original, so a one-byte difference is not a degraded match — it is an undetectable, unrecoverable miss.

Two rules enforce it:

- **Determinism across platforms and time.** A normalizer must produce identical bytes on JVM, Android, iOS, wasmJs and JS, and on an application built years ago. Nothing may read platform Unicode tables, which differ by operating-system Unicode version; any table the suite needs is frozen and self-contained.
- **No silent redefinition.** A published policy's output never changes in place. Any rule change is a new, separately-identified version, and callers store the policy identity beside whatever they derived from the output.

## Modules

Group `io.github.aughtone.normalize`. A shared base plus functional modules: **functional modules depend on the base, never the reverse, and never on each other in a cycle.** **A module boundary sits where what a caller must carry changes** — a table or an external dependency — not between normalizers. A caller never carries data it does not use, and the table-free normalizers share one bundle. The reasoning and the layouts rejected are in [ADR-0003](Decisions-ADR-0003-Bundling-Modules-By-Weight).

| Module | Coordinate | Contains | Depends on | Ships a table? |
| :--- | :--- | :--- | :--- | :--- |
| `:common` | `io.github.aughtone.normalize:common` | the shared contract — `Normalized`, `NormalizationStep`, policy + version base | `aughtone-types` | No |
| `:quodlibet` | `io.github.aughtone.normalize:quodlibet` | every normalizer needing no table and no external dependency — email, credit-card/PAN, IBAN, IPv4, IPv6, the username base | `api(:common)` | No — permanent, zero data |
| `:phone` | `io.github.aughtone.normalize:phone` | phone → E.164 | `api(:common)` + `aughtone-phonenumber` | No (region metadata, not Unicode) |
| `:unicode` | `io.github.aughtone.normalize:unicode` | Unicode normalization forms — NFC, NFD, NFKC, NFKD — and any character property more than one module needs | `api(:common)` | Yes — normalization tables, delta-packaged, plus shared properties |
| `:confusables` | `io.github.aughtone.normalize:confusables` | UTS-39 skeletons | `api(:unicode)` | Yes — confusable mappings, mirroring, paired brackets |
| `:ubilibet` | `io.github.aughtone.normalize:ubilibet` | every hostname and domain, ASCII included (UTS-46, Punycode), and URL | `api(:unicode)` | Yes — IDNA mapping, joining type |

`:common` is pure foundation with no concrete normalizer of its own, which is why it is `common` and not `core`. A future usable-standalone convenience module would be the one named `:core`.

The two Latin names are a pair: `quodlibet`, "whatever you please", is the grab bag of table-free normalizers, and `ubilibet`, "wherever you please", holds the names that locate things.

**Every module in this table is built.** The email normalizer was published as `io.github.aughtone.normalize:email` at `0.0.1` and moved into `:quodlibet` before the next release; that coordinate stays on Maven Central and is not republished.

A normalizer belongs in this suite when it is **general, reusable, and fits the versioned-policy contract**. Application-coupled code stays where it is.

## Normalizer roster

What this suite intends to normalize. **This table is the canonical list** — a normalizer that is not here is not planned, and every planned entry carries the issue tracking it, so the roster and the backlog cannot quietly diverge. Adding an entry means filing its issue at the same time.

| Normalizer | Module | Needs a table | Tracked by |
| :--- | :--- | :--- | :--- |
| Email | `:quodlibet` | no | **built** — published as `:email` at `0.0.1`, moved in [#12](https://github.com/aughtone/aughtone-normalize/issues/12) |
| Phone → E.164 | `:phone` | no (region metadata, not Unicode) | **built** — [#1](https://github.com/aughtone/aughtone-normalize/issues/1) |
| NFC / NFD / NFKC / NFKD | `:unicode` | yes | **built** — [#2](https://github.com/aughtone/aughtone-normalize/issues/2) |
| Hostname / domain / punycode (UTS-46) | `:ubilibet` | yes | **built** — [#10](https://github.com/aughtone/aughtone-normalize/issues/10) |
| URL | `:ubilibet` | yes (the host is a hostname) | **built** — [#7](https://github.com/aughtone/aughtone-normalize/issues/7) |
| Confusables / skeleton (UTS-39) | `:confusables` | yes | **built** — [#11](https://github.com/aughtone/aughtone-normalize/issues/11) |
| Credit-card / PAN | `:quodlibet` | no | **built** — [#3](https://github.com/aughtone/aughtone-normalize/issues/3) |
| IBAN / bank account | `:quodlibet` | no | **built** — [#3](https://github.com/aughtone/aughtone-normalize/issues/3) |
| IPv4 | `:quodlibet` | no | **built** — [#14](https://github.com/aughtone/aughtone-normalize/issues/14) |
| IPv6 | `:quodlibet` | no | **built** — [#4](https://github.com/aughtone/aughtone-normalize/issues/4) |
| Username / handle | `:quodlibet` base + optional confusable fold from `:confusables` | optional | **built** — [#8](https://github.com/aughtone/aughtone-normalize/issues/8) |

**Slug was considered and declined** — it is a display artifact rather than an identity, and transliterating one requires editorial choices this suite would have to freeze forever. The reasoning is kept in [Slug Normalization](Reference-Out-Of-Scope-Slug-Normalization). **URL** was the other contested entry and is in: it stays narrowly scoped to transforms that cannot change which resource is addressed, which is recorded on its issue.

The no-table normalizers share `:quodlibet`; a table-driven normalizer lives in the module that carries its table. **Unicode data is produced only by the table generator ([ADR-0002](Decisions-ADR-0002-Generating-The-Unicode-Tables), built under [#9](https://github.com/aughtone/aughtone-normalize/issues/9)), and ships in the module that needs it** — and a table needed by more than one module lives in `:unicode`, so it exists once.

## The common contract

Every normalizer shares one shape, defined in `:common`, so the API reads uniformly across email, phone, domain and the rest:

- `normalizeX(value, policy): Outcome<NormalizedX>` — returns the `aughtone-types` `Outcome`. **There is no default policy**: the caller must name one, so output is never produced under rules nobody chose.
- The success value implements `Normalized` — `canonical`, `policyId`, `policyVersion`. Store all three beside anything derived from the canonical string.
- Failure is **explicit and value-free**: a typed error that never echoes the input, so a rejected identifier cannot leak into a log. Never a best-effort result.
- **Matching is scoped by policy identity.** Two values normalized under different policies never match, even where their canonical strings happen to agree. Parties that must match each other have to agree on the same policy constant, not merely on a similar rule. This holds for every pair of policies, not for any one of them.

## Policies and versioning

A **policy** is a frozen, named rule-set with a stable `id` and `version` — the byte-stability epoch. That identity travels with anything derived from the output.

- **A policy whose chain ends in `lenient` relaxes one or more of its base's default rules and keeps every suite guarantee**: frozen bytes, identical output on every platform, errors that never echo the input value, refusal of input that cannot be parsed at all, and no guessing. It is a more permissive rule, never a weaker contract. It may accept what the default refuses; it may never invent data the input did not contain.
- **A version is minted only on a *material* change** — when the canonical bytes could actually differ for some input — never per upstream release. A consumer pinned to a version never sees a spurious bump, and a bump always means the canonical form genuinely changed.
- **A policy identity is an ordered chain of links, and the `id` is that chain rendered.** Links join with `+`, and a link's own name may contain `.`: `phone.e164+region-ca+lenient`, `domain.ascii.u17+lenient`, `email.byte-stable+nfc.u17+punycode.u17`, or a bare base like `email.byte-stable`. Building a policy joins links and reading one splits them, so the written and parsed forms cannot drift apart.
- **Three kinds of link, in one canonical order.** A **base** is a normalizer's rule set (`phone.e164`, `domain.ascii.u17`). A **qualifier** belongs to that base — a parameter such as `region-ca`, or a relaxation such as `lenient`. A **step** is a transform contributed by another module (`nfc.u17`, `punycode.u17`). The order is base, then qualifiers with parameters before relaxations, then steps in phase order (map → normalize → encode). Any other order is refused rather than reordered, so one policy has exactly one valid id.
- **A constant names a canonical chain**, ordered the same way: `E164Lenient` is `phone.e164+lenient`, `AsciiU17Lenient` is `domain.ascii.u17+lenient`, `e164ForRegionLenient("ca")` is `phone.e164+region-ca+lenient`. A relaxation is last among a base's own links, and therefore last outright for any policy that adds no steps.
- **`U` is the data version; `V` is the policy version.** `NfcU17` is frozen against Unicode 17, matching the `u17` link in its id. `ByteStableV1` is version 1 of a policy that carries no data. The two are different things and must not share a letter: a policy version appears in a constant's name only once it exceeds 1, so freezing a second version of `AsciiU17` would name it `AsciiU17V2` and leave the first name untouched.
- **Combinations are open; rule sets are closed.** A caller may assemble a chain from published links, because the result is completely described by its own id. A caller may never introduce a link: an unknown link fails the whole chain, so no rule set exists that nobody published.
- **The Unicode version segment is `u<major>`**, with the minor appended only when it is not zero — `u17` is Unicode 17.0.0, `u15-1` is 15.1.0. Every Unicode release since 5.1 has carried an update digit of zero, so the short form still names exactly one release.
- **Ids are lowercase ASCII.** A link matches `[a-z0-9-]+(\.[a-z0-9-]+)*`, which is why a region reads `region-ca` and not `region-CA`.
- **An id parses back to the policy that produced it.** The grammar above is API rather than decoration: a consumer stores `policyId` and `policyVersion`, and rebuilds that policy to normalize new input under the same rules — when adding a row to a store of existing values, or when a policy is named in configuration. `:common` owns the grammar and the resolution contract; each module exposes **one** resolver over the policies it publishes, and a caller combines the ones it depends on. There is no global registry: on a multiplatform target a registry filled at startup is an initialization-order trap and it defeats dead-code elimination, and explicit resolution states in code which modules a program trusts to name policies. Resolution never guesses — an unknown id, an unknown link, or a version this build does not carry is a typed failure, never the nearest match.
- **A later Unicode release is a new named constant**, never a version bump on a published one: the release cannot change the bytes an existing policy produces, so the existing policy is still correct.
- The four Unicode forms **version independently**: a release may add a compatibility mapping (new epoch for NFKC/NFKD) without any new canonical decomposition (NFC/NFD unchanged).

### Detecting a material change

A build-time check, not a judgement call. As written it applies to the normalization tables in `:unicode`, whose changes the Unicode stability policy guarantees are additive. The IDNA tables in `:ubilibet` may also change characters that were previously disallowed, and the confusable mappings in `:confusables` may change any mapping between versions, so those two modules adapt the check rather than inherit it ([ADR-0003](Decisions-ADR-0003-Bundling-Modules-By-Weight)):

1. Regenerate the tables from the new Unicode UCD and **diff against the frozen baseline**, which the generator does entry by entry, classifying every difference as an addition, a modification of an existing entry, or a removal. The Unicode stability policy forbids changing existing normalization mappings, so a modification or removal there is a hard stop: the generator refuses to write and the build fails, to be investigated rather than accepted.
2. **Empty diff → no new epoch. Additions → new epoch**, and the diff *is* the delta. Running it is [Regenerating the Unicode Tables](Guides-DOC-0006-Regenerating-The-Unicode-Tables).
3. Validate against the new version's official `NormalizationTest.txt`: the new base+delta normalizer must pass 100%, and the old frozen normalizer must pass everything *except* the new-character cases — which proves the delta is exactly the additions and nothing else moved.

### Delta-packaged tables

Because version-to-version changes are purely additive, tables ship as a **base snapshot plus additive deltas**, each separately loadable. `NfcU17` loads the base; `NfcU18` loads base + the 17→18 delta. A consumer pinned to an old version therefore does not carry later versions' data — unused deltas are dead-code-eliminated on JS and wasm, and unreferenced on native and JVM. Total shipped bytes are roughly equal to whole-table packaging; the win is selective loading, and it grows as versions accumulate.

## Composition across modules

A normalizer in one module may need a step that lives in another — email optionally punycoding its domain, which needs the IDNA tables. The dependency must not become a cycle:

- `:common` defines an **open `interface NormalizationStep`**. Sealed types cannot be extended across module boundaries, so extensibility comes from an interface.
- The table-carrying modules provide implementations — `NfcU17` from `:unicode`, `PunycodeU17` from `:ubilibet` — and each depends on `:common` via `api(...)`.
- `:quodlibet` accepts steps **by the `:common` interface**, so it never depends on a table-carrying module. A caller wanting email + punycode depends on both and composes: `EmailPolicy.byteStableWith([NfcU17, PunycodeU17])`.
- Composition is not a special case: a composed policy is the same **chain of links** as any other, with steps after the base and its qualifiers — `email.byte-stable+nfc.u17+punycode.u17`. The id describes the policy completely, which is what makes it auditable and resolvable later. There is no anonymous composition.
- **Order is validated** when the chain is built and again when an id is parsed. Each step declares a phase (map → normalize → encode) and the builder enforces canonical order. There is one correct order; reordering is degenerate rather than useful (punycode→NFC applies NFC over ASCII, a no-op).

## Email policies as shipped

`ByteStableV1` (id `email.byte-stable`, version 1) is **the** shared canonical form for blind tokenization. It applies only ASCII-level, Unicode-version-independent operations, so it never drifts, expires, or fails when Unicode changes:

- reject unpaired surrogates — an explicit failure, and a permanent well-formed-UTF-8 floor;
- trim ASCII whitespace;
- ASCII-lowercase `A`–`Z` only, leaving non-ASCII untouched so accented local parts are preserved rather than folded;
- strip the `+`-subaddress (RFC 5233);
- domain ASCII-lowercased, raw bytes, no `ToASCII`.

It collapses **no** Unicode variants and encodes **no** provider-specific behaviour — notably not Gmail's treatment of dots as insignificant. The line is: **a universal standard, yes; a single-provider behaviour, no.**

`ByteStableV1Lenient` (id `email.byte-stable+lenient`, version 1) is trim + ASCII-lowercase only, keeping the `+`-subaddress. It relaxes that one rule of `ByteStableV1` and keeps every other guarantee — its bytes are frozen and identical on every platform — so it is as usable for tokens as `ByteStableV1`, provided everyone matching uses it. Its id was `email.lenient` in `0.0.1`; it moved to the chain form while the suite was in alpha and nothing had been derived under it.

Errors are `MissingAtSign`, `EmptyLocalPart`, `EmptyDomain`, `UnpairedSurrogate` — all value-free.

## Changing any of this

Adding NFC, IDNA/`ToASCII`, or any provider rule to a published policy changes the canonical bytes and orphans every token already derived under it. Such a change is **always** a new policy version and **never** an in-place edit, and it needs an [Architecture Decision Record](Decisions) before it is written.
