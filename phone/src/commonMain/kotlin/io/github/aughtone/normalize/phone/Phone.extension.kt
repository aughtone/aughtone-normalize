package io.github.aughtone.normalize.phone

import io.github.aughtone.normalize.common.ComparableForm
import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.phonenumber.PhoneNumberUtil
import io.github.aughtone.phonenumber.decimalDigitValue
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.dataOrElse
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize a number into its E.164 form and, when the input carries one, its extension.
 *
 * [normalizePhone] refuses an extension, because E.164 has no room for one and dropping it would splice
 * its digits onto the subscriber number. That is the right answer for a single canonical string, and the
 * wrong answer for a caller who has the extension and wants to keep it. This returns both from one
 * reading, the way [normalizeEmailWithSubaddress][io.github.aughtone.normalize.email.normalizeEmailWithSubaddress]
 * returns a mailbox and its subaddress.
 *
 * - **The number is exactly [normalizePhone]'s output** for the same input under [ExtensionPolicy.number],
 *   so a number token derived here matches one derived there.
 * - **The extension carries its own identity**, because a stored extension token has to record what it is.
 * - **No extension is `null`**, never an empty string.
 * - **A marker with no digits after it is refused**, not read as an empty extension. `+1 212 555 0123 ext`
 *   and `+1 212 555 0123#` are not delegated at all, because the dependency mis-reads both: the first
 *   becomes `+12125550123398` when `ext` goes through keypad conversion, and the second splits as
 *   `+1212555` with extension `0123`. They reach the ordinary path instead, which refuses them.
 *
 * ## This accepts input [normalizePhone] refuses, and only that
 *
 * An extension is only findable in the text the caller typed, so input carrying a **recognised marker** -
 * `x`, `ext`, `ext.`, `xtn`, `#`, `,`, `;` - is handed to the phonenumber library, which owns the marker
 * vocabulary and splits the number from the extension against the national number plan.
 *
 * **Everything else goes down [normalizePhone]'s own path, unchanged.** That is deliberate rather than
 * incidental: the library's trailing-group guard refuses some ordinary numbers whose leading part is also
 * valid - `+49 89 636 48018` is one Munich number, not a number and an extension - so input with no marker
 * must never reach it. Gating on the marker keeps the two entry points in agreement for every ordinary
 * number, and confines the difference to exactly the inputs an extension can appear in.
 *
 * ## Letters are still refused, except a marker
 *
 * The library converts a run of three or more letters to keypad digits, as upstream does, so
 * `1-800-FLOWERS` becomes a number nobody typed. Letters are therefore refused here as they are in
 * [normalizePhone], with one exception: the letters of a recognised marker itself.
 *
 * ```
 * normalizePhoneWithExtension("+1 212 555 0123 x4", ExtensionPolicy.E164)   // +12125550123, ext "4"
 * normalizePhoneWithExtension("+1 (212) 555-0123", ExtensionPolicy.E164)    // +12125550123, ext null
 * normalizePhoneWithExtension("+43 1 58058-0", ExtensionPolicy.E164)        // refused: ambiguous
 * ```
 */
fun normalizePhoneWithExtension(
    value: String,
    policy: ExtensionPolicy,
): Outcome<NormalizedPhoneWithExtension> = runOutcome {
    if (value.hasUnpairedSurrogate()) throw PhoneNormalizationError.UnpairedSurrogate()

    val marker = findMarker(value)
        ?: return@runOutcome NormalizedPhoneWithExtension(
            number = normalizePhone(value, policy.number).dataOrThrow(),
            extension = null,
        )

    // Before the value reaches the library: every letter outside the marker is refused, so its keypad
    // conversion can never turn a word into digits. The marker's own letters are the one exception.
    refuseLettersOutside(value, marker)

    val region = policy.number.region ?: NEUTRAL_REGION
    val parsed = try {
        PhoneNumberUtil.parse(value, region)
    } catch (failure: PhoneNumberUtil.NumberParseException) {
        throw failure.toNormalizationError()
    }
    // Validity is asked of the number this returns, not of the text it came from: the raw input still
    // carries the marker, and a marker is not part of any number.
    val canonical = parsed.formatToE164()
    if (policy.number.requiresValidity && !PhoneNumberUtil.isValid(canonical, region)) {
        throw PhoneNormalizationError.NotValidForRegion()
    }

    val extension = parsed.extension?.takeIf { it.isNotEmpty() }
    NormalizedPhoneWithExtension(
        number = NormalizedPhone(
            canonical = canonical,
            policyId = policy.number.id,
            policyVersion = policy.number.version,
        ),
        extension = extension?.let {
            NormalizedExtension(canonical = it, policyId = policy.id, policyVersion = policy.version)
        },
    )
}

/** Where a recognised extension marker starts and ends in the input. */
internal class MarkerMatch(val start: Int, val end: Int)

/**
 * The first recognised extension marker in [value], or `null` when it carries none.
 *
 * This vocabulary decides only **whether to delegate**; the library decides where the number ends. Keeping
 * it small and explicit is the point: a marker it knows and this does not means input that could have been
 * split is refused instead, which is safe, visible, and pinned by `PhoneExtensionMarkerTest`.
 */
internal fun findMarker(value: String): MarkerMatch? {
    for (index in value.indices) {
        val match = when (value[index]) {
            '#', ',', ';' -> MarkerMatch(index, index + 1)
            'x', 'X', 'e', 'E' -> LETTER_MARKERS
                .firstOrNull { value.regionMatches(index, it, 0, it.length, ignoreCase = true) }
                ?.let { MarkerMatch(index, index + it.length) }

            else -> null
        }
        // WORKAROUND for aughtone/aughtone-phonenumber#23 and #24, to be removed when they are fixed. A
        // marker with no digits after it is mis-read at `phonenumber` 0.0.3: `+1 212 555 0123 ext` parses
        // as +12125550123398, because a marker with nothing after it is not read as one and its letters
        // then go through keypad conversion (#23); `+1 212 555 0123#` parses as +1212555 with extension
        // 0123 (#24). Requiring a digit keeps both away from the parser, and they are refused by the
        // ordinary path instead.
        // `PhoneDependencyDefectTest` pins both defects and FAILS once they are fixed - delete this
        // condition then rather than updating that test.
        if (match != null && hasDigitAfter(value, match.end)) return match
    }
    return null
}

/** Whether any digit follows [from], allowing the spaces and dots a marker is usually written with. */
private fun hasDigitAfter(value: String, from: Int): Boolean {
    for (index in from until value.length) {
        val character = value[index]
        if (decimalDigitValue(character.code) != null) return true
        if (character != ' ' && character != '.' && character != '-' && character != ':' && character != '=') return false
    }
    return false
}

/** Longest first, so `ext.` and `extension` are not cut short by `ext`. */
private val LETTER_MARKERS = listOf("extension", "ext.", "extn", "xtn", "ext", "x")

/** Refuse any ASCII letter outside [marker], so the library's keypad conversion can never fire. */
private fun refuseLettersOutside(value: String, marker: MarkerMatch) {
    for (index in value.indices) {
        if (index >= marker.start && index < marker.end) continue
        val character = value[index]
        if (character in 'A'..'Z' || character in 'a'..'z') {
            throw PhoneNormalizationError.LetterNotSupported(index, character.code)
        }
    }
}

/** A frozen policy for reading a number and its extension from one input. */
class ExtensionPolicy internal constructor(
    override val id: String,
    override val version: Int,
    internal val number: PhonePolicy,
) : Policy {

    /** The extension is its own value; it declares no comparable form, because it compares with nothing. */
    override val forms: Set<ComparableForm> = emptySet()

    override fun toString(): String = id

    companion object {
        internal val Base: PolicyLink = PolicyLink("phone.extension", LinkKind.Base)

        /** The extension beside a number read under [PhonePolicy.E164]. */
        val E164: ExtensionPolicy = ExtensionPolicy(
            id = PolicyId.of(listOf(Base)).dataOrElse { error("not a valid policy chain: ${it.message}") }.rendered,
            version = 1,
            number = PhonePolicy.E164,
        )

        /** The extension beside a number read for [region], an ISO region code such as `"ca"`. */
        fun forRegion(region: String): ExtensionPolicy = ExtensionPolicy(
            id = E164.id,
            version = 1,
            number = PhonePolicy.e164ForRegion(region),
        )

        internal val all: List<ExtensionPolicy> = listOf(E164)
    }
}

/** A number and, when the input carried one, its extension - each with the identity that produced it. */
data class NormalizedPhoneWithExtension(
    val number: NormalizedPhone,
    val extension: NormalizedExtension?,
)

/** A normalized extension plus its policy identity. Store all three beside anything derived from it. */
data class NormalizedExtension(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized
