package io.github.aughtone.normalize.iban

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize an international bank account number to its canonical compact form under [policy].
 *
 * **This is the one normalizer in the suite that upper-cases.** An IBAN is defined in uppercase, and
 * its printed form is grouped in fours for humans to read aloud; the canonical form is uppercase with
 * every space removed. Everywhere else in this suite lowercase is canonical, so the exception is worth
 * knowing before it surprises someone comparing two normalizers.
 *
 * Errors carry no part of the input, as everywhere in the suite, and here for the same reason as
 * `:pan`: an account number in a log is a data-protection incident.
 *
 * ```
 * when (val outcome = normalizeIban(value, IbanPolicy.Compact)) {
 *     is Outcome.Success -> outcome.data.canonical   // "GB82WEST12345698765432"
 *     is Outcome.Failure -> outcome.exception        // a typed IbanNormalizationError
 * }
 * ```
 */
fun normalizeIban(value: String, policy: IbanPolicy): Outcome<NormalizedIban> = runOutcome {
    val compact = StringBuilder(value.length)
    for ((index, character) in value.withIndex()) {
        when {
            character in '0'..'9' -> compact.append(character)
            character in 'A'..'Z' -> compact.append(character)
            character in 'a'..'z' -> compact.append(character - 32)
            character == ' ' -> Unit
            else -> throw IbanNormalizationError.UnexpectedCharacter(index)
        }
    }

    if (compact.length !in MIN_LENGTH..MAX_LENGTH) throw IbanNormalizationError.WrongLength()
    if (!compact.hasIbanShape()) throw IbanNormalizationError.MalformedStructure()

    // Structural validation stops here on purpose. A per-country length registry would make this
    // normalizer's refusals drift as countries join or amend their formats, and a frozen policy whose
    // answers change over time is exactly what this suite exists to prevent.
    if (policy.checkMod97 && !compact.passesMod97()) throw IbanNormalizationError.ChecksumFailed()

    NormalizedIban(canonical = compact.toString(), policyId = policy.id, policyVersion = policy.version)
}

private const val MIN_LENGTH = 5
private const val MAX_LENGTH = 34

/** Two letters for the country, two check digits, then the account identifier. */
private fun CharSequence.hasIbanShape(): Boolean =
    this[0] in 'A'..'Z' && this[1] in 'A'..'Z' && this[2] in '0'..'9' && this[3] in '0'..'9'

/**
 * The mod-97 check of ISO 7064: move the first four characters to the end, read letters as 10 to 35,
 * and the resulting number must leave a remainder of one. Computed digit by digit, because the number
 * is far larger than any integer type here can hold.
 */
private fun CharSequence.passesMod97(): Boolean {
    var remainder = 0
    val rearranged = substring(4) + substring(0, 4)
    for (character in rearranged) {
        val value = when (character) {
            in '0'..'9' -> character - '0'
            else -> character - 'A' + 10
        }
        remainder = if (value > 9) (remainder * 100 + value) % 97 else (remainder * 10 + value) % 97
    }
    return remainder == 1
}

/**
 * A frozen IBAN normalization policy.
 *
 * [Compact] refuses an account number whose mod-97 check fails; [CompactLenient] normalizes it anyway.
 * The check catches transcription errors, which is most of what goes wrong with an IBAN typed by hand.
 */
class IbanPolicy internal constructor(
    override val id: String,
    override val version: Int,
    internal val checkMod97: Boolean,
) : Policy {

    override fun toString(): String = id

    companion object {
        /** The base link both policies are built on. */
        internal val Base: PolicyLink = PolicyLink("iban.compact", LinkKind.Base)

        /** Uppercase and unspaced, and the mod-97 check must pass. */
        val Compact: IbanPolicy = IbanPolicy(id = chainOf(Base), version = 1, checkMod97 = true)

        /** Uppercase and unspaced, with the mod-97 check relaxed. */
        val CompactLenient: IbanPolicy =
            IbanPolicy(id = chainOf(Base, PolicyLink.Lenient), version = 1, checkMod97 = false)

        internal val all: List<IbanPolicy> = listOf(Compact, CompactLenient)

        private fun chainOf(vararg links: PolicyLink): String =
            when (val outcome = PolicyId.of(links.toList())) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            }
    }
}

/** The canonical compact IBAN plus the policy identity that produced it. */
data class NormalizedIban(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/** Why an IBAN could not be normalized. No message carries any part of the input. */
sealed class IbanNormalizationError(message: String) : Exception(message) {

    /** Something that is neither alphanumeric nor a space, at [index] in the input. */
    class UnexpectedCharacter(val index: Int) : IbanNormalizationError("iban: unexpected character at index $index")

    /** Shorter than five characters or longer than the 34 the standard allows. */
    class WrongLength : IbanNormalizationError("iban: wrong length")

    /** Not two letters, two digits, then an account identifier. */
    class MalformedStructure : IbanNormalizationError("iban: malformed structure")

    /** The mod-97 check failed, which under a strict policy means the number is not usable. */
    class ChecksumFailed : IbanNormalizationError("iban: checksum failed")
}
