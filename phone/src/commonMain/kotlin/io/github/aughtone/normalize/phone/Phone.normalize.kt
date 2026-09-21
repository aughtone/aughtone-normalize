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
 * ## An extension is refused, never folded in
 *
 * E.164 has no room for an extension, and dropping the characters that introduce one would splice its
 * digits onto the subscriber number - `+1 212 555 0123 #4` would become `+121255501234`, a different and
 * entirely plausible number. So `#`, `,` and `;` are refused under every policy, leniency included:
 * leniency widens what is accepted and may never invent data.
 *
 * The harder half is that an extension is usually written with ordinary formatting. `+43 1 58058-0` is
 * the Durchwahl convention of German-speaking countries, and `-`, `.`, `/`, `(`, `)` and the space all
 * separate parts of ordinary numbers too, so no character test tells them apart. What can be asked is
 * whether the number is already **valid without its last group**: if it is, the input says two things at
 * once and is refused with [PhoneNormalizationError.AmbiguousTrailingGroup]. After a hyphen that is
 * enough on its own; after any other separator the whole number must also be invalid, because in a
 * variable-length plan an ordinary number often has a valid number as its leading part - `+49 89 636
 * 48018` is one number, not two.
 *
 * ```
 * normalizePhone("+1 (212) 555-1234", PhonePolicy.E164)                  // "+12125551234"
 * normalizePhone("(212) 555-1234", PhonePolicy.e164ForRegion("us"))      // "+12125551234"
 * normalizePhone("+43 1 58058-0", PhonePolicy.E164)                      // refused: AmbiguousTrailingGroup
 * ```
 */
fun normalizePhone(value: String, policy: PhonePolicy): Outcome<NormalizedPhone> = runOutcome {
    if (value.hasUnpairedSurrogate()) throw PhoneNormalizationError.UnpairedSurrogate()

    val digits = StringBuilder(value.length)
    var leadingPlus = false
    var index = 0
    // Digits written after the last formatting character. An extension is usually written exactly this
    // way, so the count is what lets the check below ask whether the number is complete without them.
    var trailingGroup = 0
    var lastSeparator = 0
    var pendingSeparator = 0
    while (index < value.length) {
        val codePoint = value.codePointAtIndex(index)
        val width = if (codePoint >= 0x10000) 2 else 1

        val digit = decimalDigitValue(codePoint)
        when {
            digit != null -> {
                // A separator only ends the trailing group once a digit actually follows it, so trailing
                // formatting - `+43 1 58058-0 ` before an extension marker, or a stray space - cannot
                // clear the group and take the ambiguity guard below with it.
                if (pendingSeparator != 0) {
                    trailingGroup = 0
                    lastSeparator = pendingSeparator
                    pendingSeparator = 0
                }
                digits.append('0' + digit)
                trailingGroup++
            }

            codePoint.isExtensionMarker() -> throw PhoneNormalizationError.ExtensionNotSupported(index, codePoint)

            // A dash is recorded as THE hyphen, whichever of the family was written, so the guard below
            // reads one separator rather than nine spellings of it.
            codePoint.isFormatting() -> pendingSeparator = if (codePoint.isDash()) HYPHEN else codePoint

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
        throw failure.toNormalizationError()
    }

    // An extension written with ordinary formatting - the Durchwahl style `+43 1 58058-0`, or a trailing
    // group after a space - cannot be told from a number by the characters alone: `-`, `.`, `/`, `(`, `)`
    // and the space all separate parts of ordinary numbers too. What can be asked is whether the number is
    // already complete without that last group. If it is, the input says two things at once, and a
    // normalizer that must not invent data refuses rather than choosing one of them. Folding the group in
    // is the alternative, and it produces a different, valid-looking number that nothing reports.
    if (trailingGroup in 1 until digits.length) {
        val withoutTrailingGroup = digits.substring(0, digits.length - trailingGroup)
        val base = if (leadingPlus) "+$withoutTrailingGroup" else withoutTrailingGroup
        if (PhoneNumberUtil.isValid(base, region)) {
            // A hyphen before the last group is how German-speaking countries write an extension - the
            // Durchwahl, `+43 1 58058-0` - so a complete number before it is taken as ambiguous. After any
            // other separator, groups are ordinary formatting and only refused when the whole number is
            // not valid, which means the last group cannot belong to it. Without that second condition a
            // plainly written number would be refused wherever its leading part is also a valid number,
            // which in variable-length plans is common: `+49 89 636 48018` is one number, not two.
            val hyphenated = lastSeparator == HYPHEN
            if (hyphenated || !PhoneNumberUtil.isValid(prepared, region)) {
                throw PhoneNormalizationError.AmbiguousTrailingGroup(trailingGroup)
            }
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
/**
 * The typed error one of the dependency's failures becomes.
 *
 * Every error type is mapped deliberately rather than through an `else`: the dependency added four of
 * these in 0.0.3, and an `else` would have swallowed them silently. A new one should break this build,
 * because an unmapped failure reaching a caller as the wrong type is worse than a compile error.
 */
internal fun PhoneNumberUtil.NumberParseException.toNormalizationError(): PhoneNormalizationError =
    when (errorType) {
        PhoneNumberUtil.ErrorType.INVALID_COUNTRY_CODE -> PhoneNormalizationError.UnknownCountryCode()
        PhoneNumberUtil.ErrorType.NOT_A_NUMBER -> PhoneNormalizationError.NotANumber()
        PhoneNumberUtil.ErrorType.TOO_SHORT_AFTER_IDD,
        PhoneNumberUtil.ErrorType.TOO_SHORT_NSN,
        PhoneNumberUtil.ErrorType.TOO_LONG,
        -> PhoneNormalizationError.NotValidForRegion()
        // The dependency reached the same conclusion our own guard does, by its own route: on the plain
        // path we hand it digits with no formatting to read a trailing group from, so this is the
        // extension path, where the raw input does reach it.
        PhoneNumberUtil.ErrorType.AMBIGUOUS_TRAILING_GROUP -> PhoneNormalizationError.AmbiguousTrailingGroup(null)
        // Added in 0.0.4: the dependency now refuses a vanity number by default rather than folding its
        // letters into keypad digits. It has reached the same answer this module always gave, by its own
        // route - and that route cannot be taken from here, because every letter is refused before the
        // parser is called and what it receives is digits and at most a leading `+`.
        //
        // Mapped to an existing error rather than a new one. `LetterNotSupported` carries an index and a
        // code point, and this exception carries neither, so reporting it would mean inventing a position
        // this module never saw. A new public type would be worse: API a caller must handle for a failure
        // that cannot arrive. `NotANumber` is what is left and it is true - the dependency was handed
        // something it will not read as a number.
        PhoneNumberUtil.ErrorType.ALPHA_NUMBER_DISALLOWED -> PhoneNormalizationError.NotANumber()
    }

internal const val NEUTRAL_REGION = "US"

/**
 * Characters that are presentation rather than data: grouping, spacing and separators.
 *
 * **This class has to be as wide as the digit class, and that is the whole point of it.** A decimal digit
 * is read from every `Nd` block, so a number written in one script has digits this module understands. If
 * the separators between them are ASCII-only, the same number has separators it does not - and a rule that
 * reads separators, such as the trailing-group guard, goes blind exactly where the separator was the
 * evidence. That is how an en dash returned `+431580580` where the ASCII hyphen refused.
 *
 * The set replicates upstream libphonenumber's `VALID_PUNCTUATION` rather than being reasoned out here.
 * Two characters say why: `U+00AD` soft hyphen and `U+30FC` katakana-hiragana prolonged sound mark are
 * both in it, both appear in real numbers, and neither occurs to anyone writing a list from first
 * principles - the dependency tried and missed exactly those two. A set somebody maintains against real
 * input beats one either project reasons out.
 *
 * What is deliberately **not** here: `x` and the wait-for-dial-tone characters, which upstream keeps in
 * the same regex class. `x` is a letter and introduces an extension; the tildes say what follows is not
 * the number at all. Both are handled as markers, not as spacing.
 */
private fun Int.isFormatting(): Boolean = isDash() || isSeparatorSpace() || isGrouping()

/**
 * The dash family, every member treated **as a hyphen**.
 *
 * Not merely recorded as "a separator": the trailing-group guard asks whether the last separator was a
 * hyphen, because a hyphen before a final group is how German-speaking countries write an extension. A
 * typographic dash is that hyphen, typed by a word processor's autocorrect or pasted from a formatted
 * page. Recording it would let the guard see a separator; treating it as a hyphen makes the guard fire.
 *
 * `U+30FC` is here because it is written as a dash in Japanese numbers, not only as a vowel mark.
 */
private fun Int.isDash(): Boolean =
    this == 0x2D || this in 0x2010..0x2015 || this == 0x2212 || this == 0xFF0D || this == 0x30FC

/** Spacing, visible and invisible alike - an invisible one still ends a digit group. */
private fun Int.isSeparatorSpace(): Boolean =
    this == 0x20 || this == 0x09 || this == 0xA0 || this == 0xAD ||
        this == 0x200B || this == 0x2060 || this == 0x3000

/** Grouping punctuation, ASCII and fullwidth alike. */
private fun Int.isGrouping(): Boolean =
    this == 0x2E || this == 0x28 || this == 0x29 || this == 0x2F || this == 0x5B || this == 0x5D ||
        this == 0xFF0E || this == 0xFF0F || this == 0xFF08 || this == 0xFF09 ||
        this == 0xFF3B || this == 0xFF3D

/** ASCII `+` and its fullwidth twin, both of which mean "a country code follows". */
private fun Int.isPlus(): Boolean = this == 0x2B || this == 0xFF0B

internal fun Int.isAsciiLetter(): Boolean = this in 0x41..0x5A || this in 0x61..0x7A

/**
 * `#`, `,` and `;`, each of which says that what follows is not part of the number: an extension, a DTMF
 * sequence, or a dialling pause. Refused under every policy, because leniency widens what is accepted and
 * may never invent data - dropping the marker would splice the digits after it onto the subscriber number.
 */
private fun Int.isExtensionMarker(): Boolean =
    this == 0x23 || this == 0x2C || this == 0x3B ||
        // Wait for a dial tone: `~`, and the three characters that are written for it in other scripts.
        // Dropping one and running the digits together folds a wrong number exactly as a comma would, so
        // they are refused for the same reason rather than left to fall through as unsupported.
        this == 0x7E || this == 0x2053 || this == 0x223C || this == 0xFF5E

/** `-`, the separator German-speaking countries write an extension after. */
private const val HYPHEN = 0x2D

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

    /**
     * A marker that introduces something other than the number - `#`, `,` or `;`. The digits after it are
     * an extension or a dialling sequence, and E.164 has no room for either, so the input is refused
     * rather than silently folded into the subscriber number.
     */
    class ExtensionNotSupported(val index: Int, val codePoint: Int) :
        PhoneNormalizationError("phone: extension marker at index $index (U+${codePoint.toHex()})")

    /**
     * A trailing group of digits after ordinary formatting, where the number is already valid without it -
     * `+43 1 58058-0`. The input names a number and something else, and which is which cannot be decided
     * from the characters: the same separators appear inside ordinary numbers. Refused rather than folded.
     *
     * @property digits how many digits the trailing group held, or `null` when the dependency refused it
     * first and did not say. Not part of the number, and not enough of one to identify anybody.
     */
    class AmbiguousTrailingGroup(val digits: Int?) :
        PhoneNormalizationError(
            if (digits == null) "phone: trailing group after a complete number"
            else "phone: trailing group of $digits digits after a complete number",
        )

    /** Half a character: an unpaired surrogate has no valid UTF-8 form. */
    class UnpairedSurrogate : PhoneNormalizationError("phone: unpaired surrogate")
}

private fun Int.toHex(): String = toString(16).uppercase().padStart(4, '0')

/** The code point at [index], reading a surrogate pair as one character. */
internal fun String.codePointAtIndex(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return high.code
}

internal fun String.hasUnpairedSurrogate(): Boolean {
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
