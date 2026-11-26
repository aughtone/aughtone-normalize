# Slug Normalization

This suite does not produce URL slugs, and does not intend to.

## Why this is out of scope

**A slug is not an identity.** Every normalizer here exists so that two parties, on different platforms and in different years, derive the same bytes from the same value and can match on the result. A slug has one party. It is generated once for display in a path, and nothing downstream compares it back to what it came from. The suite's whole contract — a frozen policy identity stored beside a derived value — buys a slug nothing.

**It is lossy in a way nothing else here is.** Every other canonical form in the suite is a spelling of its input. A slug deliberately discards information: case, punctuation, whitespace, and usually every character outside a small ASCII range. It cannot be reversed and it is expected to collide.

**Both ways of building one are wrong for this suite.** Without transliteration, a slug rule is ASCII-fold-and-strip, which produces an empty or meaningless slug for a Cyrillic, Greek, Hebrew or CJK title — a silent failure for most of the world's text. With transliteration, it needs a table, and transliteration choices are editorial rather than standardized: German convention renders `ö` as `oe`, while much else renders it `o`, and Unicode publishes no single answer. This suite freezes a policy's output forever, so an editorial choice made once would be permanent and unfixable. That is a bad property for a display artifact and an unacceptable one for anything claiming a frozen identity.

**The demand is already well served.** Slug helpers exist in most web frameworks, close to the templating layer where the editorial choices belong and where changing one next year costs nothing.

## What is in scope, decided 2026-11-25

**The character-level canonical form is in `:unicode`, and slug generation is still not.** [#43](https://github.com/aughtone/aughtone-normalize/issues/43) asked for a public General_Category lookup so a caller could decide, character by character, what survives. It was accepted as three frozen text rules instead, shipped in `0.0.6` — `mark.removed`, and `alnum.only` with its spacing alternative `alnum.only.spaced` — which keep letters, decimal digits and combining marks and, after a decomposition form, fold a diacritic to its base letter. Nothing above is overturned: separators, length caps, reserved device names, collision suffixes and transliteration all remain out of scope, and the caller still owns them. What moved is that the part of the problem which *is* an identity — which code points survive, under which Unicode release — now has one, instead of each caller generating its own range table from some JDK's Unicode data.

**The transliteration objection does not reach decomposition-based folding.** Removing a combining mark after `nfd` is a spelling of the input, not an editorial choice: `é` and `e` + combining acute are the same letter by Unicode's own rule. The letters that have no canonical decomposition — `ø`, `đ`, `ł`, `æ`, `þ` — are therefore not folded at all. They survive a Unicode keep-set and are dropped by an ASCII one, so `Jørn` becomes `Jrn` and never `Jorn`. That is the cliff, and it is pinned in a frozen corpus rather than left as advice.

## What would change this

A standards-track specification defining transliteration for identifier use — the way UTS-46 defines domain mapping — would remove the editorial objection, and the question could be reopened. A framework's convention would not: that is exactly the single-vendor behaviour the suite refuses elsewhere, for the same reason.

## Prior requests

- [#5](https://github.com/aughtone/aughtone-normalize/issues/5) — "Add a slug normalizer — and decide whether it belongs in this suite" (closed 2026-09-11)
- [#43](https://github.com/aughtone/aughtone-normalize/issues/43) — "Public General_Category lookup by code point in :unicode" (accepted as text rules 2026-11-25, shipped in `0.0.6`; slug generation still declined)
