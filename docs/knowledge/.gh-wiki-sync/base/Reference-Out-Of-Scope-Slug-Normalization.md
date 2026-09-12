# Slug Normalization

This suite does not produce URL slugs, and does not intend to.

## Why this is out of scope

**A slug is not an identity.** Every normalizer here exists so that two parties, on different platforms and in different years, derive the same bytes from the same value and can match on the result. A slug has one party. It is generated once for display in a path, and nothing downstream compares it back to what it came from. The suite's whole contract — a frozen policy identity stored beside a derived value — buys a slug nothing.

**It is lossy in a way nothing else here is.** Every other canonical form in the suite is a spelling of its input. A slug deliberately discards information: case, punctuation, whitespace, and usually every character outside a small ASCII range. It cannot be reversed and it is expected to collide.

**Both ways of building one are wrong for this suite.** Without transliteration, a slug rule is ASCII-fold-and-strip, which produces an empty or meaningless slug for a Cyrillic, Greek, Hebrew or CJK title — a silent failure for most of the world's text. With transliteration, it needs a table, and transliteration choices are editorial rather than standardized: German convention renders `ö` as `oe`, while much else renders it `o`, and Unicode publishes no single answer. This suite freezes a policy's output forever, so an editorial choice made once would be permanent and unfixable. That is a bad property for a display artifact and an unacceptable one for anything claiming a frozen identity.

**The demand is already well served.** Slug helpers exist in most web frameworks, close to the templating layer where the editorial choices belong and where changing one next year costs nothing.

## What would change this

A standards-track specification defining transliteration for identifier use — the way UTS-46 defines domain mapping — would remove the editorial objection, and the question could be reopened. A framework's convention would not: that is exactly the single-vendor behaviour the suite refuses elsewhere, for the same reason.

## Prior requests

- [#5](https://github.com/aughtone/aughtone-normalize/issues/5) — "Add a slug normalizer — and decide whether it belongs in this suite" (closed 2026-09-11)
