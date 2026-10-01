---
name: io-github-aughtone-normalize-phone
description: >-
  Canonicalise a phone number to E.164 - `+12125550123` - so every spelling of
  one number produces one token you can store, hash or match, instead of
  stripping non-digits by hand. Parse a number a user typed in national format
  against a country or region you name, validate that a number exists in that
  region's numbering plan, accept a number that carries its own country code,
  read digits written in any script, drop the spaces, hyphens, dots, brackets
  and typographic dashes people format numbers with, keep a phone extension as
  a value of its own beside the E.164 number, or refuse a number the plan calls
  invalid rather than normalising it anyway. The country code is never guessed
  and an extension is never folded into the subscriber number. Every result
  carries the frozen policy identity that produced it, including the region
  that read it. Not for email addresses, IP or MAC addresses, cards or other
  identifiers (io.github.aughtone.normalize:quodlibet), or for general text
  (io.github.aughtone.normalize:unicode).
license: Apache-2.0
metadata:
  version: "0.0.5"
  repository: https://github.com/aughtone/aughtone-normalize
---

# Aught One Normalize — Phone

## What it solves

`+1 (212) 555-0123`, `+1 212 555 0123` and `+12125550123` are one number. Strip the non-digits and you get there for those three — and then somebody types `(212) 555-0123` with no country code, or `+43 1 58058-0` where the trailing `0` is an extension and not part of the number, or digits in Devanagari, or an en dash their word processor autocorrected in. Each of those turns a hand-rolled cleanup into a **different, entirely plausible number**, and once the value is a token the input is gone, so nothing can catch it afterwards.

This module writes the E.164 form, and refuses rather than guesses at every point where guessing would produce a wrong number. The country metadata comes from `io.github.aughtone:phonenumber`; this module ships no table of its own, including the digit table.

Every result carries the identity of the rules that read it — and the region is part of that identity, because the region is *how* national input was read.

## How it is meant to be used

**Pick the policy that matches where the country code comes from.** There is no default, and nothing infers a region:

```kotlin
normalizePhone("+1 (212) 555-0123", PhonePolicy.E164)                 // "+12125550123"
normalizePhone("(212) 555-0123", PhonePolicy.e164ForRegion("us"))     // "+12125550123"
```

**Store the canonical text with its policy identity,** since the region and the strictness are in the id:

```kotlin
normalizePhone(value, policy)
    .onSuccess { store(hash(it.canonical), it.policyId, it.policyVersion) }
    .onFailure { error -> log(error) }        // a typed PhoneNormalizationError
```

**Use `normalizePhoneWithExtension` when an extension is data you want to keep.** It returns the number and the extension, each with its own identity, from one reading:

```kotlin
normalizePhoneWithExtension("+1 212 555 0123 x4", ExtensionPolicy.E164)
    .onSuccess { it.number.canonical to it.extension?.canonical }      // "+12125550123" to "4"
```

## Invariants and traps

**The region is never inferred, and there is no way to ask it to be.** `PhonePolicy.E164` refuses national-format input outright with `MissingCountryCode`; `e164ForRegion("ca")` reads it against the region you named. A guessed country code does not fail loudly — it produces a valid-looking token for a **different number**. **Symptom:** `MissingCountryCode` for input a user considers complete; the fix is to know where the user is and name it, not to widen the policy.

**`e164ForRegion` throws instead of returning an `Outcome`.** An `IllegalArgumentException` for a region that is not two ASCII letters, or that the metadata does not cover. Failing at policy construction is the point: an unusable region would otherwise surface much later as a normalization failure, or worse as a plausible number for somewhere else. **Symptom:** an uncaught exception at class-initialization time, because policies are usually built into a `val`.

**A number carrying an international dialling prefix is not a number carrying a country code.** `011…` is a national way of writing "international" and means nothing without knowing where it was dialled from, so it is refused under a policy with no region.

**An extension is refused by `normalizePhone`, never dropped — and the marker set is wider than it looks.** `#`, `,` and `;` are refused under **every** policy, leniency included, because leniency widens what is accepted and may never invent data: dropping the marker splices the extension's digits onto the subscriber number. The wait-for-dial-tone characters `~`, `⁓`, `∼` and `～` are refused for the same reason. **Symptom:** `ExtensionNotSupported` carrying the index and code point, for input that used to normalize — reach for `normalizePhoneWithExtension` if the extension is data you want.

**An extension written with ordinary formatting is also refused, and this is the subtlest rule here.** `-`, `.`, `/`, `(`, `)` and the space all separate parts of ordinary numbers, so no character test tells an extension from a group. What the module asks instead is whether the number is **already valid without its last group**: `+43 1 58058-0`, the Durchwahl convention, is refused with `AmbiguousTrailingGroup`. After a hyphen that is enough on its own; after any other separator the whole number must *also* be invalid, because in a variable-length plan an ordinary number often has a valid number as its leading part — `+49 89 636 48018` is one number, not two, and is accepted. Every dash in the Unicode dash family counts as that hyphen, including an autocorrected en dash. **Symptom:** a genuine Durchwahl-style number refused rather than silently shortened; the refusal is the library declining to return a different number.

**Letters are always refused, in every position and under every policy.** `1-800-FLOWERS` cannot be dialled without a keypad mapping, and inventing one produces a token for a number nobody typed. `LetterNotSupported` carries the index and the code point.

**No localised extension label is recognised, deliberately.** `Durchwahl`, `poste`, `anexo`, `ramal`, `interno` and the rest are how extensions are written in most of the world, and input carrying one is refused **on its letters**. The recognised vocabulary is `;ext=`, `extension`, `ext.`, `extn`, `xtn`, `ext`, `x`, `#`, `,`, `;` and nothing else. A label list has no end and no owner, it would have to be versioned because it decides where a number ends, and every label admitted is a carve-out in the letter refusal. **Symptom:** `LetterNotSupported` for a perfectly ordinary Spanish or Portuguese number — strip the label in the place that knows the language, then call.

**Leniency relaxes the validity check and nothing else.** `E164Lenient` and `e164ForRegionLenient` normalize a number the metadata calls invalid instead of refusing it, and drop characters a strict policy reports as `UnsupportedCharacter`. They do not accept letters, extensions, or a misplaced `+`. A number can be well-formed and not exist, and which of those you want depends on whether you are dialling it or matching it.

**Two errors carry a code point and an index; the rest are value-free.** `LetterNotSupported`, `UnsupportedCharacter` and `ExtensionNotSupported` report "something is wrong at position 7, and it is U+00A0" — a single character is not the number, and the alternative is a caller guessing. Everything else says only what failed. Match on the subclass; the message text is not API.

**Every phone policy declares one comparable form, so cross-policy matching is explicit.** The output is always the E.164 number, whatever region read it and however lenient the reading, so all four policy shapes declare `PhoneForms.E164`. Ask `PolicyResolver.comparability(idA, versionA, idB, versionB)` rather than trusting that two stored strings agree.

**The version here means something different from the rest of the suite.** Nothing is frozen against a table this repository controls — the country metadata belongs to the phonenumber library — so a version is minted when moving to a newer release of that library changes what a policy produces for some input in the frozen corpus. Most callers will never see a bump; the field exists so the one who does can tell.

**A region policy is rebuilt from its id, not looked up.** There are hundreds of regions, so `PhonePolicies` does not enumerate them: `phone.e164:region.ca` resolves by rebuilding. An id naming a region the metadata does not cover fails rather than resolving to something close.

**Two extensions with the same digits are still not comparable across policies.** `ExtensionPolicy.E164` is `phone.extension` and `ExtensionPolicy.forRegion("ca")` is `phone.extension:region.ca`, rebuilt from its id the way a phone region policy is. The extension digits are read identically under both — the region governs the number beside them, not the extension — so the canonical bytes agree while the identities do not, and an extension declares no comparable form to bridge them. **Symptom:** two extension tokens whose strings are equal compare as `NotComparable`; compare the numbers, which do share `PhoneForms.E164`, and treat the extension as something read beside a number rather than a key of its own. There is no lenient extension policy, so `phone.extension:region.ca:lenient` resolves to nothing.

**There is no `normalizePhoneOrNull`.** Use the `Outcome`, or `getOrNull()?.canonical` where a failure needs no handling of its own.

## What moved, and what it used to be called

**`ExtensionPolicy.forRegion(region)` writes a different policy id in `0.0.5`, and the compiler cannot show you this one.** Same signature, same canonical extension digits, different identity: it used to carry `ExtensionPolicy.E164`'s id, `phone.extension`, and now carries `phone.extension:region.ca`. Two policies that do not accept the same input shared one `(id, version)` pair, so `resolve("phone.extension", 1)` handed back the policy that refuses national-format input whichever one had produced the value. If you stored `phone.extension` beside an extension read under `forRegion`, that id resolves to `ExtensionPolicy.E164` and always did — the extension's own bytes are unaffected, because the region governs the number beside them, so re-deriving the extension gives what you already stored. There is no lenient extension policy, so `phone.extension:region.ca:lenient` resolves to nothing rather than to something close.

**Every policy id was respelled in `0.0.4`, and no canonical bytes changed with it.** Links join with `:` instead of `+` and a link name carries no hyphen: `phone.e164+lenient` is now `phone.e164:lenient`, `phone.e164+region-ca` is `phone.e164:region.ca`, and `phone.e164+region-ca+lenient` is `phone.e164:region.ca:lenient`. A `0.0.3` id is refused rather than aliased.

**Extension handling changed behaviour in `0.0.4`, and this is the one change the compiler cannot show you.** Previously the lenient policies dropped `#`, `,` or `;` and spliced the digits after it onto the subscriber number, and the strict policies did the same wherever the folded result was still valid — so `+1 212 555 0123 #4` returned `+121255501234`, a different and entirely plausible number. Both are now refused. Separately, a trailing group after ordinary formatting is refused when the number is already valid without it, so `+43 1 58058-0` now fails where it used to return `+431580580`. Same signatures, same policy ids, **more input refused**. Ordinary numbers written with separators are unaffected.

**`normalizePhoneWithExtension` is new in `0.0.4`.** Before it, keeping an extension was not possible at all: `normalizePhone` was and remains the only entry point, and it refuses one, because E.164 cannot carry an extension. `ExtensionPolicy`, `NormalizedPhoneWithExtension`, `NormalizedExtension` and the id `phone.extension` all arrive with it.

**The module arrived in `0.0.2`,** and an agent will guess at a shape it never had. There is no `PhoneNumber` type to hold a parsed number, no `format(number, Format.E164)` call, no `isValidNumber` predicate, no default region argument, and no region *parameter* on `normalizePhone`: the region lives on the policy, because it is part of the identity of anything derived under it.

**The `Outcome` accessors were renamed to match `kotlin.Result` in `0.0.5`,** following the `io.github.aughtone:types` library every result here is built on: `dataOrNull()` is `getOrNull()`, `dataOrThrow()` is `getOrThrow()`, `dataOrElse { }` is `getOrElse { }`, and the failure callbacks receive the `Throwable` rather than the `Outcome.Failure` wrapper. The old names are gone rather than deprecated. **What the compiler will not catch:** `Outcome.Failure.message` is a non-null `String` and `Throwable.message` is `String?`, so a `getOrElse { }` interpolating `it.message` keeps compiling and starts writing `null`.

## Called from Kotlin

**Kotlin only.** There is no Swift or JavaScript consumer surface: nothing in this library is annotated `@JsExport`, and no XCFramework, Swift package or podspec is published. The iOS framework and JavaScript targets in the build prove the code compiles for those platforms; they do not export an API to Swift or JavaScript callers. Every declaration lives in `commonMain` — there is no `expect`/`actual` anywhere in the suite — so a claim made here is true on every target.

## What it is not for

**It is not a phone-number library.** Number type, carrier, time zone, geocoding, as-you-type formatting, short codes, premium-rate classification — the `io.github.aughtone:phonenumber` library's business. This module canonicalises to E.164 and does nothing else with a number.

**It tells you nothing about the number.** No region is inferred, nothing is reported about where it is or who serves it, and a number that normalizes is well formed rather than real: nobody answers it, it may never have been assigned, and nothing here dials or checks anything.

**It is not a formatter.** National and international display forms are presentation, and a person's number should be shown to them the way their country writes it — which this does not do, and which no byte-stable form can.

**It encodes universal standards, never one provider's behaviour.** E.164 decides what the canonical string is. Which numbers one carrier, one country's regulator or one product will accept is a rule above this suite.
