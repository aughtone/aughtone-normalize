package io.github.aughtone.normalize.phone

import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.phonenumber.PhoneNumberUtil
import io.github.aughtone.phonenumber.decimalDigitValue
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize a phone number to E.164 under [policy].
 *
 * ## The region is never guessed
 *
 * E.164 output needs a country code, and a number written in national form does not carry one. So the
 * region travels on the policy: [PhonePolicy.E164] accepts only input that already carries its own
 * country code, and [PhonePolicy.e164ForRegion] interprets national form against a region the caller
 * named deliberately. A guessed country code does not fail loudly - it produces a valid-looking token
 * for a *different number*, and once the value is a token the input is gone, so nothing can catch it
 * later. That decision is [ADR-0001].
 *
 * ## Digits from any script, and nothing else
 *
 * Every Unicode decimal digit is converted to its ASCII value through the frozen table the phonenumber
 * library publishes - this module ships no table of its own. ASCII formatting (spaces, hyphens, dots,
 * parentheses, slashes) is dropped as presentation. Letters are refused: a number spelled `1-800-FLOWERS`
 * cannot be dialled without a keypad mapping, and inventing one would produce a token for a number the
 * caller never typed.
 *
 * ```
 * normalizePhone("+1 (212) 555-1234", PhonePolicy.E164)                  // "+12125551234"
 * normalizePhone("(212) 555-1234", PhonePolicy.e164ForRegion("us"))      // "+12125551234"
 * ```
 */
fun normalizePhone(value: String, policy: PhonePolicy): Outcome<NormalizedPhone> = runOutcome {
    if (value.hasUnpairedSurrogate()) throw PhoneNormalizationError.UnpairedSurrogate()

    val digits = StringBuilder(value.length)
    var leadingPlus = false
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAtIndex(index)
        val width = if (codePoint >= 0x10000) 2 else 1

        val digit = decimalDigitValue(codePoint)
        when {
            digit != null -> digits.append('0' + digit)

            codePoint.isFormatting() -> Unit

            codePoint.isPlus() -> {
                // A plus means "what follows is a country code", so it is only meaningful first. In the
                // middle of a number it signals something this normalizer does not understand.
                if (digits.isNotEmpty() || leadingPlus) throw PhoneNormalizationError.MisplacedPlus(index)
                leadingPlus = true
            }

            codePoint.isAsciiLetter() -> throw PhoneNormalizationError.LetterNotSupported(index, codePoint)

            policy.dropsUnsupportedCharacters -> Unit

            else -> throw PhoneNormalizationError.UnsupportedCharacter(index, codePoint)
        }
        index += width
    }

    if (digits.isEmpty()) throw PhoneNormalizationError.NoDigits()
    // Without a region, only input that carries its own country code can be resolved - including input
    // that begins with an international dialling prefix such as `011`, which is a national way of
    // writing "international" and means nothing without knowing where it was dialled from.
    if (!leadingPlus && !policy.hasRegion) throw PhoneNormalizationError.MissingCountryCode()

    val prepared = if (leadingPlus) "+$digits" else digits.toString()
    val region = policy.region ?: NEUTRAL_REGION

    val parsed = try {
        PhoneNumberUtil.parse(prepared, region)
    } catch (failure: PhoneNumberUtil.NumberParseException) {
        throw when (failure.errorType) {
            PhoneNumberUtil.ErrorType.INVALID_COUNTRY_CODE -> PhoneNormalizationError.UnknownCountryCode()
            PhoneNumberUtil.ErrorType.NOT_A_NUMBER -> PhoneNormalizationError.NotANumber()
        }
    }

    if (policy.requiresValidity && !PhoneNumberUtil.isValid(prepared, region)) {
        throw PhoneNormalizationError.NotValidForRegion()
    }

    NormalizedPhone(
        canonical = parsed.formatToE164(),
        policyId = policy.id,
        policyVersion = policy.version,
    )
}

/**
 * The region used to parse input that already carries its own country code.
 *
 * A region is required by the underlying parser even when the number is international, where it has no
 * effect on the result. The E.164-only policies refuse national-format input outright, so this is never
 * consulted for anything it could change - and a frozen corpus test pins that, because "it does not
 * matter" is the kind of claim that quietly stops being true.
 */
private const val NEUTRAL_REGION = "US"

/** ASCII characters that are presentation rather than data: grouping, spacing and separators. */
private fun Int.isFormatting(): Boolean =
    this == 0x20 || this == 0x09 || this == 0x2D || this == 0x2E ||
        this == 0x28 || this == 0x29 || this == 0x2F

/** ASCII `+` and its fullwidth twin, both of which mean "a country code follows". */
private fun Int.isPlus(): Boolean = this == 0x2B || this == 0xFF0B

private fun Int.isAsciiLetter(): Boolean = this in 0x41..0x5A || this in 0x61..0x7A

/** The canonical E.164 string plus the policy identity that produced it. */
data class NormalizedPhone(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/**
 * Why a number could not be normalized.
 *
 * Value-free, with one deliberate exception: the character-level failures carry the UTF-16 index and the
 * offending code point. A phone number is personal data and must never reach a log, but "there is
 * something wrong at position 7, and it is U+00A0" is the difference between a caller fixing their input
 * and guessing at it - and a single character is not the number.
 */
sealed class PhoneNormalizationError(message: String) : Exception(message) {

    /** An ASCII letter. Letter-dialling needs a keypad mapping this suite will not invent. */
    class LetterNotSupported(val index: Int, val codePoint: Int) :
        PhoneNormalizationError("phone: letter at index $index (U+${codePoint.toHex()})")

    /** A character that is neither a digit, formatting, nor a plus - under a policy that refuses those. */
    class UnsupportedCharacter(val index: Int, val codePoint: Int) :
        PhoneNormalizationError("phone: unsupported character at index $index (U+${codePoint.toHex()})")

    /** A plus sign somewhere other than the start, where it cannot mean a country code. */
    class MisplacedPlus(val index: Int) : PhoneNormalizationError("phone: misplaced '+' at index $index")

    /** Nothing that could be a number. */
    class NoDigits : PhoneNormalizationError("phone: no digits")

    /** National-format input under a policy with no region: the country code would have to be guessed. */
    class MissingCountryCode : PhoneNormalizationError("phone: no country code and no region")

    /** A country code that is not assigned. */
    class UnknownCountryCode : PhoneNormalizationError("phone: unknown country code")

    /** The parser could not read the input as a number at all. */
    class NotANumber : PhoneNormalizationError("phone: not a number")

    /** Parsed, but the metadata says no such number exists in that region. */
    class NotValidForRegion : PhoneNormalizationError("phone: not valid for its region")

    /** Half a character: an unpaired surrogate has no valid UTF-8 form. */
    class UnpairedSurrogate : PhoneNormalizationError("phone: unpaired surrogate")
}

private fun Int.toHex(): String = toString(16).uppercase().padStart(4, '0')

/** The code point at [index], reading a surrogate pair as one character. */
private fun String.codePointAtIndex(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return high.code
}

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
