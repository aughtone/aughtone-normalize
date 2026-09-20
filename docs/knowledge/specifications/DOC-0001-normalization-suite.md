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
| `:quodlibet` | `io.github.aughtone.normalize:quodlibet` | every normalizer carrying no table of its own — email and its pieces, credit-card/PAN, IBAN, IPv4, IPv6 and networks, MAC addresses, UUIDs, the username base | `api(:common)`, `api(:ubilibet)` | No — permanent, and it ships no data a caller who touches no domain keeps |
| `:phone` | `io.github.aughtone.normalize:phone` | phone → E.164 | `api(:common)` + `aughtone-phonenumber` | No (region metadata, not Unicode) |
| `:unicode` | `io.github.aughtone.normalize:unicode` | configurable text normalization — control, trim, spaces, case, case folding, NFC, NFD, NFKC, NFKD — and any character property more than one module needs; its Android artifact bundles the `TextPolicyRuleOrder` lint check | `api(:common)` | Yes — normalization tables, delta-packaged, plus shared properties |
| `:confusables` | `io.github.aughtone.normalize:confusables` | UTS-39 skeletons | `api(:unicode)` | Yes — confusable mappings, mirroring, paired brackets |
| `:ubilibet` | `io.github.aughtone.normalize:ubilibet` | every hostname and domain, ASCII included (UTS-46, Punycode), URL, and validating ToUnicode for display | `api(:unicode)` | Yes — IDNA mapping, joining type |

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
| Text: control, trim, spaces, case, case folding, NFC / NFD / NFKC / NFKD | `:unicode` | yes, for Unicode rules | **built** — [#2](https://github.com/aughtone/aughtone-normalize/issues/2), configurable since [#16](https://github.com/aughtone/aughtone-normalize/issues/16) |
| Hostname / domain / punycode (UTS-46) | `:ubilibet` | yes | **built** — [#10](https://github.com/aughtone/aughtone-normalize/issues/10) |
| URL | `:ubilibet` | yes (the host is a hostname) | **built** — [#7](https://github.com/aughtone/aughtone-normalize/issues/7) |
| Confusables / skeleton (UTS-39) | `:confusables` | yes | **built** — [#11](https://github.com/aughtone/aughtone-normalize/issues/11) |
| Credit-card / PAN | `:quodlibet` | no | **built** — [#3](https://github.com/aughtone/aughtone-normalize/issues/3) |
| IBAN / bank account | `:quodlibet` | no | **built** — [#3](https://github.com/aughtone/aughtone-normalize/issues/3) |
| IPv4 | `:quodlibet` | no | **built** — [#14](https://github.com/aughtone/aughtone-normalize/issues/14) |
| IPv6 | `:quodlibet` | no | **built** — [#4](https://github.com/aughtone/aughtone-normalize/issues/4); `ipv4.mapped`, `ipv4.nat64` and `zone.kept` modes under [#17](https://github.com/aughtone/aughtone-normalize/issues/17) |
| IP networks: CIDR input and address-to-block derivation, IPv4 and IPv6 | `:quodlibet` | no | **built** — [#17](https://github.com/aughtone/aughtone-normalize/issues/17) |
| MAC address (EUI-48, EUI-64) | `:quodlibet` | no | **built** — [#23](https://github.com/aughtone/aughtone-normalize/issues/23) |
| UUID, including Windows GUID byte dumps | `:quodlibet` | no | **built** — [#24](https://github.com/aughtone/aughtone-normalize/issues/24) |
| Username / handle | `:quodlibet` base + optional confusable fold from `:confusables` | optional | **built** — [#8](https://github.com/aughtone/aughtone-normalize/issues/8) |

**Slug was considered and declined** — it is a display artifact rather than an identity, and transliterating one requires editorial choices this suite would have to freeze forever. The reasoning is kept in [Slug Normalization](../reference/out-of-scope/slug-normalization.md). **URL** was the other contested entry and is in: it stays narrowly scoped to transforms that cannot change which resource is addressed, which is recorded on its issue.

The no-table normalizers share `:quodlibet`; a table-driven normalizer lives in the module that carries its table. **Unicode data is produced only by the table generator ([ADR-0002](../decisions/ADR-0002-generating-the-unicode-tables.md), built under [#9](https://github.com/aughtone/aughtone-normalize/issues/9)), and ships in the module that needs it** — and a table needed by more than one module lives in `:unicode`, so it exists once.

## The common contract

Every normalizer shares one shape, defined in `:common`, so the API reads uniformly across email, phone, domain and the rest:

- `normalizeX(value, policy): Outcome<NormalizedX>` — returns the `aughtone-types` `Outcome`. **There is no default policy**: the caller must name one, so output is never produced under rules nobody chose.
- The success value implements `Normalized` — `canonical`, `policyId`, `policyVersion`. Store all three beside anything derived from the canonical string.
- Failure is **explicit and value-free**: a typed error that never echoes the input, so a rejected identifier cannot leak into a log. Never a best-effort result.
- **Matching is scoped by policy identity.** Two values normalized under different policies never match, even where their canonical strings happen to agree. Parties that must match each other have to agree on the same policy constant, not merely on a similar rule. This holds for every pair of policies, not for any one of them, with one explicit exception: **comparable forms**.
- **Values from different policies match only in a comparable form both declare.** A comparable form is a named canonical text, spelled with the link grammar (`phone.e164`, `ipv4.address`). A policy declares the forms it writes where its rules guarantee the meaning; where a comparison is an interpretation, a policy only *offers* the form, and a caller opts in by naming it in the id, after every other link and in form-name order: `ipv4.inet-aton+form.ipv4.address`. The opt-in changes the identity and never the bytes, so a value stored before it stays non-comparable. Because the declaration is part of what an id resolves to, `PolicyResolver.comparability` answers from two stored identities alone: the same policy, comparable in a named form, or not comparable. Forms may be added to a published policy and are never removed. A composed policy declares none, because its steps change the output, and a confusable skeleton never declares one, because it is a check rather than an identity. Across Unicode releases only the normalization forms may share a form, because the Unicode stability policy guarantees an assigned character's NFC never changes; IDNA and confusable mappings may change between releases and never share one.

## Policies and versioning

A **policy** is a frozen, named rule-set with a stable `id` and `version` — the byte-stability epoch. That identity travels with anything derived from the output.

- **A policy whose chain ends in `lenient` relaxes one or more of its base's default rules and keeps every suite guarantee**: frozen bytes, identical output on every platform, errors that never echo the input value, refusal of input that cannot be parsed at all, and no guessing. It is a more permissive rule, never a weaker contract. It may accept what the default refuses; it may never invent data the input did not contain. **Where leniency only widens what is accepted, the pair shares a comparable form**, because for input both accept the outputs are the same claim: `pan.digits`, `iban.compact`, `domain.ascii.u17` and the URL pair declare one. Email has no lenient policy: removing the subaddress changes the canonical text rather than what is accepted, so it is an option, `email:subaddress.removed`, not a relaxation.
- **A version is minted only on a *material* change** — when the canonical bytes could actually differ for some input — never per upstream release. A consumer pinned to a version never sees a spurious bump, and a bump always means the canonical form genuinely changed.
- **A policy identity is an ordered chain of links, and the `id` is that chain rendered.** Links join with `:`, and a link's own name is one or more lowercase segments joined by `.`: `phone.e164:region.ca:lenient`, `domain.ascii.u17:lenient`, `email:text.u17:nfc:punycode.u17`, or a bare base like `email`. Building a policy joins links and reading one splits them, so the written and parsed forms cannot drift apart.

- **A link that acts on the value is named `<subject>.<what was done>`**, and one that qualifies how the rules are applied is a bare adjective: `space.collapsed`, `subaddress.removed`, `ipv4.mapped`, `host.zeroed`, against `lenient`. `.removed` is the one word meaning the named part is absent from the result, which is what separates `space.removed` from `space.collapsed`, where whitespace survives. A policy **normalizes what it was given**: dropping a part of the value is an option a caller asks for, never a base's default, because discarding is invisible in the output and cannot be undone, while keeping can always be narrowed later.
- **Three kinds of link, in one canonical order.** A **base** is a normalizer's rule set (`phone.e164`, `domain.ascii.u17`). A **qualifier** belongs to that base — a parameter such as `region.ca`, or a relaxation such as `lenient`. A **step** is a transform contributed by another module (`skeleton.u17`, `punycode.u17`), or a whole configured policy from another module travelling as one group (`text.u17:nfc`). The order is base, then qualifiers with parameters before relaxations, then steps in phase order (map → normalize → encode). Any other order is refused rather than reordered, so one policy has exactly one valid id.
- **A constant names a canonical chain**, ordered the same way: `E164Lenient` is `phone.e164:lenient`, `AsciiU17Lenient` is `domain.ascii.u17:lenient`, `e164ForRegionLenient("ca")` is `phone.e164:region.ca:lenient`. A relaxation is last among a base's own links, and therefore last outright for any policy that adds no steps.

- **`U` is the data version; `V` is the policy version.** `AsciiU17` is frozen against Unicode 17, matching the `u17` in its id. `Address` is version 1 of a policy that carries no data. The two are different things and must not share a letter: a policy version appears in a constant's name only once it exceeds 1, so freezing a second version of `AsciiU17` would name it `AsciiU17V2` and leave the first name untouched.
- **A configurable policy renders its configuration as its id.** A text policy's base names the Unicode release only when a rule uses Unicode data (`text` or `text.u17`), each rule is a parameter link in the normalizer's fixed application order, and a rule in the other character set is marked `.ascii`. Its resolver rebuilds the policy from the id and refuses any spelling that does not render back identically, so one configuration has exactly one id.
- **Combinations are open; rule sets are closed.** A caller may assemble a chain from published links, because the result is completely described by its own id. A caller may never introduce a link: an unknown link fails the whole chain, so no rule set exists that nobody published.
- **The Unicode version segment is `u<major>`**, with the minor appended only when it is not zero — `u17` is Unicode 17.0.0, `u15-1` is 15.1.0. Every Unicode release since 5.1 has carried an update digit of zero, so the short form still names exactly one release.
- **Ids are lowercase ASCII.** A link matches `[a-z0-9]+(\.[a-z0-9]+)*`, which is why a region reads `region.ca` and not `region-CA`. A segment carries no hyphen, so a Unicode minor release is spelled `u15.1`.
- **An id has a portable spelling for slots that refuse `:`.** `PolicyId.toPortable` joins the links with `_` instead — `phone.e164_region.ca_lenient` — and `PolicyId.fromPortable` recovers the exact id. `_` never occurs in the grammar, so the mapping is lossless. It exists for form-encoded query strings, Kubernetes label values, container image tags, identifier slots that must match a programming-language name, and similar restricted positions.

  **The convention, because two consumers choosing differently is how a shared identity diverges: store and compare the canonical `:` form, and convert at the edge that cannot hold it, back again on the way in.** A policy id is a value, not a name — it belongs in a field beside the canonical text, where `:` is legal in every store this suite has met. Converting early, and keeping the portable form as the stored one, spreads a spelling that exists for one slot across everything that reads it; a value that is half one spelling and half the other is refused by `fromPortable` rather than repaired, which is the failure showing up where it was made.
- **Suite-wide invariants live in `:tools:suite-invariants`, because no published module can hold them.** Every module resolves its own ids and a caller combines the resolvers of what it depends on — which is the design, and also means no module can see the others. So each module's round-trip test can pass while the *combination* a caller builds is broken: two modules publishing one id, two declaring one comparable form, a chain that resolves alone and not in company. That module depends on every published module, ships nothing, and is not published. All three failures are silent, and the form collision is the worst of them: it does not error, it **matches**.

- **A character class a rule reads is as wide as the input its normalizer accepts.** A normalizer that accepts a decimal digit from every `Nd` block, and then asks whether the character beside it is a separator using seven ASCII characters, has one class Unicode-wide and one ASCII-only — so a number written consistently in one script has digits it understands and separators it does not. Any rule reading the narrow class then goes blind exactly where that character was the evidence: an en dash slipped past the phone module's trailing-group guard and returned a folded, wrong number where the ASCII hyphen was correctly refused. The mismatch, not the missing character, is the defect. Where such a class is unavoidable, take it from whoever maintains one against real input rather than reasoning it out — a hand-written punctuation set misses `U+00AD` and `U+30FC`, which are ordinary in real numbers, and two projects missed exactly those independently.

- **An id parses back to the policy that produced it.** The grammar above is API rather than decoration: a consumer stores `policyId` and `policyVersion`, and rebuilds that policy to normalize new input under the same rules — when adding a row to a store of existing values, or when a policy is named in configuration. `:common` owns the grammar and the resolution contract; each module exposes **one** resolver over the policies it publishes, and a caller combines the ones it depends on. There is no global registry: on a multiplatform target a registry filled at startup is an initialization-order trap and it defeats dead-code elimination, and explicit resolution states in code which modules a program trusts to name policies. Resolution never guesses — an unknown id, an unknown link, or a version this build does not carry is a typed failure, never the nearest match. A combined resolver asks each module's own resolver, so an id a module rebuilds on demand resolves through `+` exactly as it does alone, and a composed chain resolves group by group into a `ComposedPolicy` carrying the base and steps that re-derive its bytes — or fails whole if any group is owned by a module the caller did not include.
- **A later Unicode release is a new named constant**, never a version bump on a published one: the release cannot change the bytes an existing policy produces, so the existing policy is still correct.
- The four Unicode forms **version independently**: a release may add a compatibility mapping (new epoch for NFKC/NFKD) without any new canonical decomposition (NFC/NFD unchanged).

### Detecting a material change

A build-time check, not a judgement call. As written it applies to the normalization tables in `:unicode`, whose changes the Unicode stability policy guarantees are additive. The IDNA tables in `:ubilibet` may also change characters that were previously disallowed, and the confusable mappings in `:confusables` may change any mapping between versions, so those two modules adapt the check rather than inherit it ([ADR-0003](../decisions/ADR-0003-bundling-modules-by-weight.md)):

1. Regenerate the tables from the new Unicode UCD and **diff against the frozen baseline**, which the generator does entry by entry, classifying every difference as an addition, a modification of an existing entry, or a removal. The Unicode stability policy forbids changing existing normalization mappings, so a modification or removal there is a hard stop: the generator refuses to write and the build fails, to be investigated rather than accepted.
2. **Empty diff → no new epoch. Additions → new epoch**, and the diff *is* the delta. Running it is [Regenerating the Unicode Tables](../guides/DOC-0006-regenerating-the-unicode-tables.md).
3. Validate against the new version's official `NormalizationTest.txt`: the new base+delta normalizer must pass 100%, and the old frozen normalizer must pass everything *except* the new-character cases — which proves the delta is exactly the additions and nothing else moved.

### Delta-packaged tables

Because version-to-version changes are purely additive, tables ship as a **base snapshot plus additive deltas**, each separately loadable. `NfcU17` loads the base; `NfcU18` loads base + the 17→18 delta. A consumer pinned to an old version therefore does not carry later versions' data — unused deltas are dead-code-eliminated on JS and wasm, and unreferenced on native and JVM. Total shipped bytes are roughly equal to whole-table packaging; the win is selective loading, and it grows as versions accumulate.

## Composition across modules

A normalizer in one module may need a step that lives in another — email optionally punycoding its domain, which needs the IDNA tables. The dependency must not become a cycle:

- `:common` defines an **open `interface NormalizationStep`**. Sealed types cannot be extended across module boundaries, so extensibility comes from an interface.
- The table-carrying modules provide implementations — any `TextPolicy` from `:unicode`, `SkeletonU17` from `:confusables` — and each depends on `:common` via `api(...)`. A step contributes a **group** of links: its first link opens the group at a phase, and the rest are its own qualifiers, so a configured text policy composes whole.
- `:quodlibet` accepts steps **by the `:common` interface** rather than by depending on whatever module provides them. A caller wanting email + punycode depends on both and composes: `normalizeUsername(value, UsernamePolicy.Basic, listOf(TextPolicy.NfcU17))`.
- **That is composition, and it is not the same as needing another normalizer to be correct.** `:quodlibet` does depend on `:ubilibet`, because `normalizeEmailParts` returns a domain and a domain has one right reading. Where the choice is a caller's, it arrives as a step through `:common`; where there is only one correct answer, the module depends on whoever has it rather than publishing a second, weaker reading. See [ADR-0003](../decisions/ADR-0003-bundling-modules-by-weight.md), amended.
- Composition is not a special case: a composed policy is the same **chain of links** as any other, with steps after the base and its qualifiers — `username.basic:text.u17:nfc`. The id describes the policy completely, which is what makes it auditable and resolvable later. There is no anonymous composition.
- **Order is validated** when the chain is built and again when an id is parsed. Each step declares a phase (map → normalize → encode) and the builder enforces canonical order. There is one correct order; reordering is degenerate rather than useful (punycode→NFC applies NFC over ASCII, a no-op).

## Email policies as shipped

`Address` (id `email`, version 1) is **the** identity anchor for blind tokenization: the whole address, with the `+`-subaddress kept. It applies only ASCII-level, Unicode-version-independent operations, so it never drifts, expires, or fails when Unicode changes:

- reject unpaired surrogates — an explicit failure, and a permanent well-formed-UTF-8 floor;
- trim ASCII whitespace;
- ASCII-lowercase `A`–`Z` only, leaving non-ASCII untouched so accented local parts are preserved rather than folded;
- domain ASCII-lowercased, raw bytes, no `ToASCII`.

**Keeping the subaddress is the default because it cannot be known whether dropping it is safe.** Some mail systems treat the tag as part of an individual's account: RFC 5233 is optional, RFC 5321 makes the local part opaque to everyone but the receiving server, and a default Postfix treats `+` as a literal character. Where that cannot be discovered, the tagged address **is** the whole address — an assumption that never merges two people, and one a caller can narrow later, where merging two accounts into one token cannot be undone.

This is not only theoretical. **Firebase Auth treats `a+one@example.com` and `a@example.com` as two distinct users**, so an application keyed on that store and tokenizing with the subaddress removed would hold a token that disagrees with its own identity provider, and contact matching could resolve to the wrong account. The duplicate signup a tag-keeping rule allows is a nuisance someone notices; the merge a tag-removing rule causes is an identity error nobody can detect afterwards.

It collapses **no** Unicode variants and encodes **no** provider-specific behaviour — notably not Gmail's treatment of dots as insignificant. The line is: **a universal standard, yes; a single-provider behaviour, no.**

`SubaddressRemoved` (id `email:subaddress.removed`, version 1) removes the `+`-subaddress (RFC 5233), for a caller who knows the provider treats it as a tag — which is the caller's knowledge, not something this library can discover. Its bytes are frozen and identical on every platform. It is not comparable with `Address` and declares no comparable form: for a tagged address the two write different text, and a tag cannot be removed from a stored token after the fact. Its id was `email.lenient` in `0.0.1`, `email.byte-stable+lenient` in `0.0.2`, and `email.byte-stable` in `0.0.3`, where removing the tag was the default; in `0.0.4` it is the option and the id says so. Its bytes never changed.

`normalizeEmailWithSubaddress` with `EmailSubaddressPolicy.V1` returns the mailbox and the RFC 5233 subaddress from one reading of the address, for a caller that tokenizes the two separately and matches on either. The mailbox is exactly `SubaddressRemoved`'s output, id and version, so its tokens match those from `normalizeEmail`. The subaddress is everything after the first `+` of the local part, ASCII-lowercased like the rest; it is absent when there is no `+` and empty for `user+@`. It has its own identity (id `email.subaddress`, version 1), because a stored tag token must record what it is, and it declares no comparable form: a tag never compares with an address.

### Every piece from one reading

`normalizeEmailParts` takes an `EmailPolicy` and a `DomainPolicy`, and returns the mailbox, the local part (id `email.local`, version 1), the domain under `domain.ascii.u17`, and the subaddress from a single reading. It exists because a caller that tokenizes more than the mailbox was splitting the canonical string by hand, which means reproducing rules this module owns — which `@` is the boundary, that lowercasing is ASCII-only, that the domain is bytes rather than an IDNA form — and produces pieces that carry **no policy at all**, so nothing records which reading made the token and nothing can re-derive it.

`normalizeEmail` and `normalizeEmailWithSubaddress` are views over the same reading, not separate parses, and a test runs one corpus through all three and compares. Asserting each path against its own expectations cannot catch a disagreement between them; that is how two phone entry points disagreed about the same input, and it is not worth rebuilding here.

**The local part is the whole local part, subaddress included**, because the anchor keeps what it was given; the mailbox is the piece the policy changes. It declares no comparable form: a local part compares with nothing else this suite writes.

**A domain taken out of an address is a domain, and there is exactly one domain identity.** The domain piece is `normalizeDomain`'s output under the policy the caller passed, so a token minted from an address matches one minted from a URL or a block list. An earlier version of this published a second, table-free reading — raw bytes, ASCII-lowercased, no ToASCII — to keep `:quodlibet` free of a dependency. It was wrong: two identities for one concept agree on every ASCII domain and diverge on the rest, so every test passes and the mismatch waits for the input that mattered. Avoiding a dependency is not a reason to publish an identity whose tokens match nothing; unused code is eliminated by the toolchain, so the weight argument did not hold either. See ADR-0003, amended.

**Which pieces get an identity, and why.** Not their position in the address: **a piece carries its own identity when nothing else in the suite reads the same thing.** A local part and a subaddress are meaningful only inside the address they came from and have no competing reading, so the email rules are the only rules they have. A domain has a competing reading — the right one — so it uses it.

**The domain is an `Outcome`.** An address can be valid while its domain is not one: `user@[192.0.2.1]` carries an address literal, and a label can fail a UTS-46 check the address carried happily. Refusing the whole address would make this entry point disagree with `normalizeEmail` about the same input, which is the defect #32 was about; so the address reads, and the domain reports why it has no token.

**A local part alone identifies nobody** — `sales` is the same at every domain, and local parts come from a small vocabulary, so a token of one is guessable from its distribution even under a keyed hash. It is published because a caller applying its own provider rules needs the piece those rules act on, and it carries the same warning `email.subaddress` does.

**Provider behaviour stays with the caller.** Collapsing dots for one provider, or removing a tag only on domains known to support subaddressing, is one provider's behaviour rather than a standard, and a frozen provider list cannot grow without splitting the tokens minted before a domain joined it from those minted after. The suite hands back the pieces; the caller owns the rule and mints its own identity for the result.

Errors are `MissingAtSign`, `EmptyLocalPart`, `EmptyDomain`, `UnpairedSurrogate` — all value-free.

## Phone extensions

An extension is data — it changes who is reached — and E.164 has no room for one, so the normalizer refuses input that carries one rather than dropping the characters that introduce it. Dropping them splices the extension's digits onto the subscriber number: `+1 212 555 0123 #4` becomes `+121255501234`, a different and entirely plausible number that no caller can detect once it is a token.

`#`, `,` and `;` are refused under every policy, leniency included, because leniency widens what is accepted and may never invent data.

The hard case is that an extension is usually written with ordinary formatting — `+43 1 58058-0`, the Durchwahl convention of German-speaking countries. `-`, `.`, `/`, `(`, `)` and the space separate parts of ordinary numbers as well, so **no character test distinguishes them**. What can be asked is whether the number is already valid without its last group, which needs the national number plan and therefore lives beside the metadata rather than in a character filter. After a hyphen a complete number before the group is enough to refuse; after any other separator the whole number must also be invalid, because in a variable-length plan an ordinary number often has a valid number as its leading part: `+49 89 636 48018` is one number, not two.

**This refuses some legitimately written numbers**, where a number's leading part is itself valid and the writer separated the last group with a hyphen. That is the trade: a refusal the caller sees and can correct, against a wrong number nobody sees. A separator ends the trailing group only once a digit follows it, so trailing formatting — a stray space, or the space before an extension marker — cannot clear the group and take the guard with it. The dependency folds extensions in the same way upstream libphonenumber does, so the marker half is being fixed there ([aughtone/aughtone-phonenumber#5](https://github.com/aughtone/aughtone-phonenumber/issues/5)) while the formatting half stays here.

### Keeping an extension

`normalizePhoneWithExtension` with `ExtensionPolicy.E164` returns the E.164 number and, when the input carries one, the extension under its own identity (id `phone.extension`, version 1). The number is exactly what `normalizePhone` writes, so its tokens match either way; the extension declares no comparable form, because an extension only means something beside its own number.

**The marker is the boundary, so the number is read by the ordinary rules.** A recognised marker — `x`, `ext`, `ext.`, `extn`, `xtn`, `extension`, `#`, `,`, `;`, `;ext=`, each followed by digits — says where the number ends. Everything before it goes through `normalizePhone` under the policy's number policy, and everything after it is read as extension digits. So the number is that function's output by construction, and an input whose number part it refuses is refused here with the same error, extension or no extension.

That is the whole design, and it was learned the hard way. The first version handed the entire input to the phonenumber library as soon as it saw a marker, on the reasoning that the library owns the marker vocabulary and splits against the national number plan. The plan is only needed to split where there is **no** marker, which this never does — so nothing was gained, and what was lost was every rule in this module that the library does not share. `+43 1 58058-0#4` came back as `+431580580`, folding the Durchwahl into the subscriber number, while `+43 1 58058-0` alone was correctly refused. Reading one input by two sets of rules is what made that possible; see aughtone/aughtone-normalize#32.

**A marker with nothing after it introduces nothing.** `+1 212 555 0123#` and `+1 212 555 0123 ext` are that number and no extension: the marker is dropped and the extension is `null`. Dropping it invents nothing, which is the test every relaxation in this suite has to pass, and it is what the phonenumber library does from the release that fixes [aughtone/aughtone-phonenumber#23](https://github.com/aughtone/aughtone-phonenumber/issues/23) and [#24](https://github.com/aughtone/aughtone-phonenumber/issues/24) — matching it costs nothing and removes a rule. What it gives up is a truncation signal: an extension lost somewhere upstream now reads the same as one never written. `normalizePhone` still refuses the same text, because it reads no extension at all and a marker there could only mean digits it would have to splice onto the number.

Separators are allowed between the marker and the first digit, because `x 4` and `ext. 4` are ordinary spellings; after the first digit there are only digits.

**Ambiguity is refused whatever trails it, and the dependency now agrees.** `+43 1 58058-0#4` says two things at once, and an extension marker later in the string does not settle which — so it is refused exactly as `+43 1 58058-0` is. The library folded it, because its own guard ran only when no extension had been stripped; that was reported from here and is [aughtone/aughtone-phonenumber#25](https://github.com/aughtone/aughtone-phonenumber/issues/25), fixed on the same reasoning — the ambiguity is a property of the number, not of whether an extension trails it — and shipping in a later release. Nothing here waits for it: this module reads the number itself.

That is the standing rule for following a dependency at all. Matching it is worth doing where it removes a rule and invents nothing, as with a marker introducing nothing above. It is never worth doing where it means choosing one reading of ambiguous input, and this suite would have kept refusing had they decided the other way.

**No localised label is recognised, deliberately.** `Durchwahl`, `poste`, `anexo`, `ramal` and the rest are how extensions are written in most of the world, and input carrying one is refused on its letters. The set has no end and no owner, so the list would have to be versioned — it decides where a number ends, and "which release of the label list split this token?" is the question a Unicode release asks, which `phone.extension` is free of today. Admitting a label is also a hole in the letter rule that keeps keypad conversion away from the number, in languages nobody maintaining this reads. And the obvious list to copy is partial: measured against the dependency, `anexo` and `extensión` split correctly while `poste` and `ramal` fold into the subscriber number as keypad digits — `+1 212 555 0123 poste 4` becomes `+12125550123767834`. Copying the list means copying its gaps, and its gaps are the defect this area exists to prevent. A caller whose input carries labels strips them first, in the place that knows the language.

The vocabulary does not avoid these words and could not: `anexo` contains an `x` and `extensión` begins with `ext`, so a marker matches inside the label and the split lands mid-word. That is harmless — the letters left on either side are refused, because a letter is refused in the number and in the extension alike. **The letter rule is what makes every label safe, in every language, without a list.**

**The two vocabularies are compared, but only one decides.** Ours decides where the number ends. `PhoneExtensionMarkerTest` asks the dependency what it does with each spelling, so a version bump that adds a form we do not read shows up as a question — input they would have split and we refuse — rather than as a silent change to anyone's tokens.

**The invariant is tested directly.** `PhoneExtensionAgreementTest` runs each case through both entry points and compares, which is the test that was missing: the corpora for each path were green while the two paths disagreed about `+43 1 58058-0#4`.

## Changing any of this

Adding NFC, IDNA/`ToASCII`, or any provider rule to a published policy changes the canonical bytes and orphans every token already derived under it. Such a change is **always** a new policy version and **never** an in-place edit, and it needs an [Architecture Decision Record](../decisions/README.md) before it is written.
