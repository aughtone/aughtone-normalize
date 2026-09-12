package io.github.aughtone.normalize.pan

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize a payment card number to its canonical digits-only form under [policy].
 *
 * People type card numbers in groups, with spaces or hyphens, copied from a card face or a receipt.
 * Those separators are presentation, not data, so both policies drop them and the canonical form is
 * digits alone - which is what a caller hashes when it stores a token instead of the number.
 *
 * **Read the error discipline before using this.** A PAN is regulated payment data. No error here
 * carries any part of the input, not even the offending character: [PanNormalizationError] reports a
 * position and nothing else, and that is deliberate. A log line echoing a rejected card number is a
 * data-protection incident, not a debugging aid.
 *
 * ```
 * when (val outcome = normalizePan(value, PanPolicy.Digits)) {
 *     is Outcome.Success -> outcome.data.canonical   // "4111111111111111"
 *     is Outcome.Failure -> outcome.exception        // a typed PanNormalizationError
 * }
 * ```
 */
fun normalizePan(value: String, policy: PanPolicy): Outcome<NormalizedPan> = runOutcome {
    val digits = StringBuilder(value.length)
    for ((index, character) in value.withIndex()) {
        when {
            character in '0'..'9' -> digits.append(character)
            character == ' ' || character == '-' -> Unit
            else -> throw PanNormalizationError.UnexpectedCharacter(index)
        }
    }

    // ASCII digits only, because this module carries no lookup table of any kind. `:phone` converts
    // digits from any script because the library it depends on publishes that mapping; nothing here
    // can, and guessing would be worse than refusing.
    if (digits.length !in MIN_DIGITS..MAX_DIGITS) throw PanNormalizationError.WrongLength()
    if (policy.checkLuhn && !digits.passesLuhn()) throw PanNormalizationError.ChecksumFailed()

    NormalizedPan(canonical = digits.toString(), policyId = policy.id, policyVersion = policy.version)
}

private const val MIN_DIGITS = 12
private const val MAX_DIGITS = 19

/**
 * The Luhn check digit, as every card scheme defines it: double every second digit from the right,
 * subtract nine from anything over nine, and the total must be divisible by ten.
 */
private fun CharSequence.passesLuhn(): Boolean {
    var sum = 0
    var double = false
    for (index in lastIndex downTo 0) {
        var digit = this[index] - '0'
        if (double) {
            digit *= 2
            if (digit > 9) digit -= 9
        }
        sum += digit
        double = !double
    }
    return sum % 10 == 0
}

/**
 * A frozen payment-card normalization policy.
 *
 * [Digits] refuses a number whose Luhn check fails; [DigitsLenient] normalizes it anyway. That is the
 * same answer `:phone` gives for an implausible number, and the two must not diverge: a failing check
 * digit almost always means a typo, and tokenizing a typo puts a row in someone's store that will never
 * match anything again.
 */
class PanPolicy internal constructor(
    override val id: String,
    override val version: Int,
    internal val checkLuhn: Boolean,
) : Policy {

    override fun toString(): String = id

    companion object {
        /** The base link both policies are built on. */
        internal val Base: PolicyLink = PolicyLink("pan.digits", LinkKind.Base)

        /** Digits only, and the Luhn check must pass. */
        val Digits: PanPolicy = PanPolicy(id = chainOf(Base), version = 1, checkLuhn = true)

        /** Digits only, with the Luhn check relaxed. Every other guarantee is unchanged. */
        val DigitsLenient: PanPolicy =
            PanPolicy(id = chainOf(Base, PolicyLink.Lenient), version = 1, checkLuhn = false)

        internal val all: List<PanPolicy> = listOf(Digits, DigitsLenient)

        private fun chainOf(vararg links: PolicyLink): String =
            when (val outcome = PolicyId.of(links.toList())) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            }
    }
}

/** The canonical digits plus the policy identity that produced them. */
data class NormalizedPan(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/**
 * Why a card number could not be normalized.
 *
 * **These messages carry no part of the input, ever** - not the value, not the offending character.
 * [UnexpectedCharacter] reports a position only. This is the strictest error discipline in the suite
 * and this is the module it exists for.
 */
sealed class PanNormalizationError(message: String) : Exception(message) {

    /** Something that is neither a digit nor a grouping separator, at [index] in the input. */
    class UnexpectedCharacter(val index: Int) : PanNormalizationError("pan: unexpected character at index $index")

    /** Fewer than 12 or more than 19 digits, the range the standard allows. */
    class WrongLength : PanNormalizationError("pan: wrong number of digits")

    /** The Luhn check failed, which under a strict policy means the number is not usable. */
    class ChecksumFailed : PanNormalizationError("pan: checksum failed")
}
