package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Convert a domain to its U-label form for display, validating each label under [policy].
 *
 * **This is display, not identity.** The result carries no policy identity and must never be stored,
 * hashed or compared in place of a normalized domain: the canonical form is the A-label that
 * [normalizeDomain] produces. Normalize to store and match; convert with this to show a person.
 *
 * Decoding Punycode alone recovers the letters of a name but not whether they are safe to show: a
 * decoded label may contain a code point UTS-46 disallows, or break the bidi or joiner rules that stop
 * a name displaying as one thing and resolving as another. This runs the same checks as
 * [normalizeDomain], pinned by the same [policy] to the same Unicode release and flags, and says per
 * label which failed - so a display can show the U-label where it validates and the A-label where it
 * does not.
 *
 * - **Any input is accepted**, as UTS-46 ToUnicode accepts it: `CAFÉ.example` and `xn--caf-dma.example`
 *   both convert to `café.example`.
 * - **Errors are per label, and processing continues past them**, as the specification requires. A
 *   bidi failure lands on the label that breaks the rule, which may not be the label that made the name
 *   right-to-left.
 * - **The only failure of the whole call is ill-formed input:** an unpaired surrogate is not a domain.
 * - **Not here:** mixed-script and confusable detection. Deciding when not to show a valid U-label is a
 *   display policy rather than an IDNA rule; `:confusables` provides the skeleton a display can use.
 *
 * ```
 * toUnicodeDomain("xn--caf-dma.example", DomainPolicy.AsciiU17)
 *     .onSuccess { domain -> show(domain.labels.joinToString(".") { if (it.isValid) it.unicode else it.ascii }) }
 * ```
 */
fun toUnicodeDomain(value: String, policy: DomainPolicy): Outcome<UnicodeDomain> = runOutcome {
    if (value.hasUnpairedSurrogate()) throw DomainNormalizationError.UnpairedSurrogate()

    UnicodeDomain(
        Uts46.toUnicode(value, policy.flags).map { label ->
            UnicodeLabel(unicode = label.unicode, ascii = label.ascii, error = label.error)
        },
    )
}

/**
 * A domain converted for display: one [UnicodeLabel] per label, in order, including an empty root label
 * when the name ends in a dot.
 */
class UnicodeDomain internal constructor(val labels: List<UnicodeLabel>) {

    /** True when every label validated, so [unicode] is safe to show as it stands. */
    val isValid: Boolean get() = labels.all { it.isValid }

    /** The UTS-46 ToUnicode result: every label's U-label, valid or not, joined by dots. */
    val unicode: String get() = labels.joinToString(".") { it.unicode }
}

/**
 * One label of a [UnicodeDomain].
 *
 * @property unicode the label after UTS-46 mapping and Punycode decoding. For a label whose Punycode
 * did not decode, the label as given.
 * @property ascii the label's ASCII form, to show in place of [unicode] when the label is not valid: a
 * label given as Punycode keeps the form it was given in, and any other is encoded.
 * @property error the first check the label failed, or `null` when it validated. Value-free, like every
 * error in this suite.
 */
class UnicodeLabel internal constructor(
    /** The label for display: its U-label where it converts, otherwise the label as it arrived. */
    val unicode: String,

    /** The label's A-label form, which is what [normalizeDomain] writes and what DNS carries. */
    val ascii: String,

    /** The first check this label failed, or `null` when it passed every one. */
    val error: DomainNormalizationError?,
) {
    /** True when the label passed every check [error] would report. */
    val isValid: Boolean get() = error == null
}
