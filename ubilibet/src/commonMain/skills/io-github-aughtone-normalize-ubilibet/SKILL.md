---
name: io-github-aughtone-normalize-ubilibet
description: >-
  Canonicalise a host name, domain name or URL into byte-stable text you can
  store, compare or hash, so two spellings of one host produce one token - use
  instead of lowercasing a host or reaching for a platform IDN converter, whose
  Unicode data changes with the OS. Convert an internationalised domain to its
  `xn--` Punycode A-label under UTS-46 (IDNA), validate a host name against the
  hyphen, bidi, joiner, STD3 and DNS-length rules, punycode or unpunycode a
  label, convert a domain back to readable Unicode for display and learn which
  labels are unsafe to show, canonicalise a URL by lowercasing its scheme,
  normalising its host, dropping a default port, fixing percent-encoding case
  and resolving `.` and `..` in the path, or relax the checks that ordinary web
  input routinely fails. Not for text or Unicode forms
  (io.github.aughtone.normalize:unicode), email addresses, IP addresses or
  other identifiers (io.github.aughtone.normalize:quodlibet).
license: Apache-2.0
metadata:
  version: "0.0.4"
  repository: https://github.com/aughtone/aughtone-normalize
---

# Aught One Normalize — Ubilibet

## What it solves

`Example.COM`, `example.com` and `EXAMPLE.com.` are one host to a person. `café.fr`, `CAFÉ.FR` and `xn--caf-dma.fr` are one host to DNS. If you lowercase and compare, the first group works and the second does not; if you reach for the platform's IDN converter, both work until the code runs on a device whose Unicode data is a different vintage — and then the same name produces different bytes on different platforms, which is an invisible miss rather than a visible error.

This module converts a host name to its **A-label form** under UTS-46, against IDNA tables compiled into the library from a pinned Unicode release. Nothing reads the platform's Unicode data. It does the same for URLs, changing only what RFC 3986 defines as not changing which resource is addressed.

It also converts back, for display: `toUnicodeDomain` gives you the readable form **and** says, per label, which ones failed a check and should be shown as Punycode instead.

## How it is meant to be used

**Normalize a host to its A-label and store that.** Every host goes through the same function, ASCII included:

```kotlin
normalizeDomain(value, DomainPolicy.AsciiU17)
    .onSuccess { store(it.canonical, it.policyId, it.policyVersion) }   // "xn--caf-dma.fr"
    .onFailure { error -> log(error) }
```

**Normalize a URL under a policy that names the host policy it used:**

```kotlin
normalizeUrl(value, UrlPolicy.Rfc3986U17)      // url.rfc3986:domain.ascii.u17
    .onSuccess { store(it.canonical, it.policyId, it.policyVersion) }
```

**Convert to Unicode only to show a person, and honour the per-label verdict:**

```kotlin
toUnicodeDomain(value, DomainPolicy.AsciiU17).onSuccess { domain ->
    show(domain.labels.joinToString(".") { if (it.isValid) it.unicode else it.ascii })
}
```

## Invariants and traps

**There is no ASCII fast path, and adding one would be a bug.** An ASCII host is normalized by the same call and the same policy as any other, because a second, simpler rule would produce identical bytes under a *different* policy identity — two callers agreeing on the output and still failing to match. **Symptom:** if you shortcut `value.lowercase()` for names that look ASCII, those tokens carry no identity and never match the ones this produced.

**The output is ASCII, and the Unicode form is not an identity.** `normalizeDomain` writes the A-label. `toUnicodeDomain` returns no policy id at all, deliberately: it is display conversion. **Symptom:** a stored "readable" domain that matches nothing, and cannot be re-derived because nothing records how it was produced.

**Decoding Punycode yourself is not the same as converting for display.** A decoded label can hold a code point UTS-46 disallows, or break the bidi or joiner rules that stop a name displaying as one thing and resolving as another. `toUnicodeDomain` runs the policy's checks and reports the first failure **per label**, continuing past it as the specification requires. **Symptom:** a spoofed name rendered to a user as legitimate-looking Unicode.

**A bidi failure can land on a label that looks innocent.** The rule is about the whole name's direction, so the reported label may not be the one that made the name right-to-left.

**Leniency relaxes validation only, and never the spoofing rules.** `AsciiU17Lenient` relaxes hyphen placement, the STD3 ASCII restriction and DNS length — what ordinary web input fails. It keeps the bidi and joiner checks, because relaxing those is not leniency but accepting a name that cannot be represented unambiguously. **Symptom:** input a browser accepts is refused under `AsciiU17`; that is the strict policy doing its job, and `AsciiU17Lenient` is the other choice, not a weaker guarantee.

**A trailing root dot is different bytes under the two policies.** `example.com.` names the same host as `example.com`, but stripping the dot here would diverge from UTS-46: with DNS-length verification on, the empty root label is an error, so `AsciiU17` **refuses** it, and `AsciiU17Lenient` **keeps** it. **Symptom:** under the lenient policy, `example.com.` and `example.com` produce two tokens. Normalize the form you mean.

**Transitional processing is not implemented, not even behind a flag.** It is deprecated upstream. So `faß.example` and `fass.example` are different names here, as nontransitional processing requires.

**The UTS-46 flags are not yours to choose.** Every combination of the five flags is a different rule-set that accepts and refuses different names, so they are fixed per policy and travel inside the id. **Symptom:** there is no flag parameter to reach for; pick the policy whose id records the rules.

**A URL's query and fragment survive byte for byte, and that is the design.** Sorting query parameters, dropping empty ones, stripping tracking parameters, removing `www.`, upgrading `http` to `https`, adding or removing a trailing slash — none of it happens, because each can change what the URL asks for. Treat additions here as regressions. **Symptom:** two URLs you consider equivalent do not compare equal; that difference was preserved on purpose.

**`normalizeUrl` refuses three authorities outright rather than guessing.** Userinfo (`UserinfoNotSupported` — credential material has no place in a canonical form), an IPv6 literal or other bracketed host (`UnsupportedHost`), and an address host in any spelling but canonical dotted-quad (`AmbiguousAddressHost`: `192.000.002.001`, `0x7f.1`, `3221225985`). Those spellings are read differently by different stacks, so rewriting one would bury a choice of reading inside a URL. **Symptom:** a URL that a browser opens is refused; normalize the address deliberately with `normalizeIpv4` in `io.github.aughtone.normalize:quodlibet`, then rebuild the URL.

**A relative reference has no canonical form.** No scheme, or one that is not a scheme, is `MissingScheme` — there is no base-URL resolution here.

**Errors are value-free, and there are twelve of them.** No `DomainNormalizationError` or `UrlNormalizationError` message carries any part of the input: a rejected host is routinely attacker-supplied, and a URL query routinely holds personal data. Match on the subclass; the message text is not API.

**Both policies declare a comparable form, so a strict and a lenient token can be matched deliberately.** For a name both accept they write the same A-label, so `DomainForms.AsciiU17` (and `DomainForms.UrlRfc3986U17` for URLs) is declared by both — ask `PolicyResolver.comparability` rather than assuming. Domains normalized against **different Unicode releases never share a form**, because IDNA mappings may change between releases; the release is in the form's name for exactly that reason.

**A new Unicode release is a new constant, never a change.** UTS-46 guarantees a character already valid keeps its mapping, so a newer table can accept what an older one refused but never changes a name that already normalized. `AsciiU17` keeps producing what it produces today.

## What moved, and what it used to be called

**Every policy id was respelled in `0.0.4`, and no canonical bytes changed with it.** Links join with `:` instead of `+`: `domain.ascii.u17+lenient` is now `domain.ascii.u17:lenient`, and `url.rfc3986+domain.ascii.u17+lenient` is now `url.rfc3986:domain.ascii.u17:lenient`. A `0.0.3` id is refused rather than aliased.

**`email.domain` never shipped.** A domain identity of that name was built and withdrawn before release, because a domain taken out of an address is a domain: `normalizeEmailParts` in `io.github.aughtone.normalize:quodlibet` returns the address's domain under **this** module's `domain.ascii.u17`, the same identity a domain read from a URL or a block list carries. If you are holding a reference to `email.domain`, it is from an unreleased build.

**`toUnicodeDomain` is new in `0.0.3`.** Before it there was no display conversion at all, and `normalizeDomain` is unchanged by its arrival.

**Both entry points arrived in `0.0.2`**, so there is no earlier shape of this module to unlearn — but an agent will guess at one anyway. There is no `toAscii`/`toUnicode` pair of top-level functions, no `IDN` class, no flags parameter, and no separate ASCII entry point: the whole surface is `normalizeDomain`, `String.normalizeDomainOrNull`, `toUnicodeDomain`, `normalizeUrl`, the `DomainPolicy` and `UrlPolicy` constants, `DomainForms`, and `UbilibetPolicies`.

**The `Outcome` accessors were renamed to match `kotlin.Result` in this release,** following the `io.github.aughtone:types` library every result here is built on: `dataOrNull()` is `getOrNull()`, `dataOrThrow()` is `getOrThrow()`, `dataOrElse { }` is `getOrElse { }`, and the failure callbacks receive the `Throwable` rather than the `Outcome.Failure` wrapper. The old names are gone rather than deprecated. **What the compiler will not catch:** `Outcome.Failure.message` is a non-null `String` and `Throwable.message` is `String?`, so a `getOrElse { }` interpolating `it.message` keeps compiling and starts writing `null`.

## Called from Kotlin

**Kotlin only.** There is no Swift or JavaScript consumer surface: nothing in this library is annotated `@JsExport`, and no XCFramework, Swift package or podspec is published. The iOS framework and JavaScript targets in the build prove the code compiles for those platforms; they do not export an API to Swift or JavaScript callers. Every declaration lives in `commonMain` — there is no `expect`/`actual` anywhere in the suite — so the IDNA tables and every claim here are the same on all of them.

## What it is not for

*This section needs the maintainer and is unwritten.*
