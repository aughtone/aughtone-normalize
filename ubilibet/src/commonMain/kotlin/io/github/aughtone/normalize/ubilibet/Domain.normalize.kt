package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize a hostname or domain name to its canonical A-label form under [policy].
 *
 * **Every hostname goes through here, ASCII included.** There is no separate ASCII-only entry point, and
 * that is deliberate: for ASCII input UTS-46 does little more than lowercase and validate, so a second
 * simpler rule elsewhere in the suite would produce identical bytes under a different policy identity -
 * two callers agreeing on the output and still failing to match.
 *
 * - **No default policy:** the caller names one, so a domain is never normalized under rules nobody chose.
 * - **Output is ASCII:** the A-label form (`xn--…`), which survives storage, transport and every system
 *   that handles hostnames as bytes. A readable Unicode form is a display concern and a different identity.
 * - **Nontransitional processing only.** Transitional processing is deprecated upstream and is not
 *   implemented, not even behind a flag.
 * - **A trailing root dot is the standard's business, not this function's.** `example.com.` names the
 *   same host as `example.com`, but stripping it here would diverge from UTS-46: with DNS length
 *   verification on, the empty root label is an error, and with it off the dot is part of the output.
 *   [DomainPolicy.AsciiU17] therefore refuses it and [DomainPolicy.AsciiU17Lenient] keeps it, which
 *   means the two spellings are different bytes under the lenient policy. Normalize the form you mean.
 *
 * ```
 * when (val outcome = normalizeDomain(value, DomainPolicy.AsciiU17)) {
 *     is Outcome.Success -> outcome.data.canonical   // "xn--caf-dma.fr"
 *     is Outcome.Failure -> outcome.exception        // a typed, value-free DomainNormalizationError
 * }
 * ```
 */
fun normalizeDomain(value: String, policy: DomainPolicy): Outcome<NormalizedDomain> = runOutcome {
    if (value.hasUnpairedSurrogate()) throw DomainNormalizationError.UnpairedSurrogate()

    NormalizedDomain(
        canonical = Uts46.toAscii(value, policy.flags),
        policyId = policy.id,
        policyVersion = policy.version,
    )
}

/**
 * Loose convenience: the canonical domain, or `null` if [value] cannot be normalized under [policy].
 * NOT for tokenization - use [normalizeDomain] and keep the policy identity beside whatever you derive.
 */
fun String.normalizeDomainOrNull(policy: DomainPolicy): String? =
    when (val outcome = normalizeDomain(this, policy)) {
        is Outcome.Success -> outcome.data.canonical
        is Outcome.Failure -> null
    }

/**
 * The canonical A-label form plus the policy identity that produced it. Store all three beside anything
 * derived from [canonical]: the identity records both the rules and the Unicode release behind them.
 */
data class NormalizedDomain(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/**
 * Why a hostname could not be normalized.
 *
 * Value-free, like every error in this suite: no message carries any part of the input. A rejected
 * hostname is routinely attacker-supplied, and a log line echoing it is both an injection vector and a
 * record of something the caller may not be allowed to keep.
 *
 * Match on the subclass; the message text is not API.
 */
sealed class DomainNormalizationError(message: String) : Exception(message) {

    /** A code point the IDNA table disallows, or an ASCII character the STD3 rules exclude. */
    class DisallowedCodePoint : DomainNormalizationError("domain: disallowed code point")

    /** An empty label: two dots in a row, or a name that is nothing but dots. */
    class EmptyLabel : DomainNormalizationError("domain: empty label")

    /** A label longer than the 63 octets DNS allows. */
    class LabelTooLong : DomainNormalizationError("domain: label too long")

    /** A name longer than the 253 octets DNS allows. */
    class NameTooLong : DomainNormalizationError("domain: name too long")

    /** A hyphen where the rules forbid one, or a label falsely claiming the `xn--` prefix. */
    class HyphenRule : DomainNormalizationError("domain: hyphen rule")

    /** A label that is not in Normalization Form C, which an A-label's contents must be. */
    class NotNormalized : DomainNormalizationError("domain: label is not NFC")

    /** A label beginning with a combining mark, which has no base character to combine with. */
    class LeadingCombiningMark : DomainNormalizationError("domain: label begins with a combining mark")

    /** A right-to-left name that breaks the bidi rule, so it would display and resolve differently. */
    class BidiRule : DomainNormalizationError("domain: bidi rule")

    /** A zero-width joiner or non-joiner outside the context that justifies one. */
    class JoinerRule : DomainNormalizationError("domain: joiner rule")

    /** A label wearing the `xn--` prefix whose contents are not valid Punycode. */
    class PunycodeDecodeFailed : DomainNormalizationError("domain: invalid punycode")

    /** A label that cannot be encoded to Punycode, which a legitimate label never hits. */
    class PunycodeEncodeFailed : DomainNormalizationError("domain: punycode encoding failed")

    /** Half a character: an unpaired surrogate has no valid UTF-8 form and is refused, never encoded. */
    class UnpairedSurrogate : DomainNormalizationError("domain: unpaired surrogate")
}

/** True if the string contains a high surrogate without a following low surrogate, or vice versa. */
private fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        if (character.isHighSurrogate()) {
            if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
            index += 2
        } else {
            if (character.isLowSurrogate()) return true
            index += 1
        }
    }
    return false
}
