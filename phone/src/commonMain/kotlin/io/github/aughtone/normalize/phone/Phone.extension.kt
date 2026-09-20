package io.github.aughtone.normalize.phone

import io.github.aughtone.normalize.common.ComparableForm
import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
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
 * ## The marker is the boundary, so the number is read by the ordinary rules
 *
 * A recognised marker - `x`, `ext`, `ext.`, `extn`, `xtn`, `extension`, `#`, `,`, `;`, `;ext=` - says
 * where the number ends. Everything before it is handed to [normalizePhone] under [ExtensionPolicy.number]
 * and everything after it is read as extension digits.
 *
 * **So the number is [normalizePhone]'s output for the text before the marker, by construction rather than
 * by agreement.** Every rule applies to it: the ambiguity guard that refuses `+43 1 58058-0`, the refusal
 * of a second extension marker, the character whitelist, the misplaced-plus rule and the validity check
 * the policy asks for. An input whose number part [normalizePhone] refuses is refused here with the same
 * error, whether or not an extension follows.
 *
 * That matters more than it looks. Splitting the number from the extension needs the national number plan
 * only when there is no marker to split on, and this never splits without one — so nothing is gained by
 * handing the whole input to the phonenumber library, and what is lost is every rule this module applies
 * that the library does not. Reading the input twice, once by each set of rules, is how `+43 1 58058-0#4`
 * came back as `+431580580` rather than a refusal.
 *
 * - **The extension carries its own identity**, because a stored extension token has to record what it is.
 * - **A missing extension is `null`**, never an empty string.
 * - **A marker with nothing after it introduces nothing**, so the number is returned without it and the
 *   extension is `null`: `+1 212 555 0123#` and `+1 212 555 0123 ext` are that number and no extension.
 * - **The extension folds across scripts** by the number's own rule, so an extension written in any
 *   decimal digits has one spelling. Separators are allowed between the marker and the first digit, and
 *   nothing but digits after it.
 *
 * ```
 * normalizePhoneWithExtension("+1 212 555 0123 x4", ExtensionPolicy.E164)   // +12125550123, ext "4"
 * normalizePhoneWithExtension("+1 (212) 555-0123", ExtensionPolicy.E164)    // +12125550123, ext null
 * normalizePhoneWithExtension("+43 1 58058-0 x4", ExtensionPolicy.E164)     // refused: ambiguous
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

    val number = normalizePhone(value.substring(0, marker.start), policy.number).dataOrThrow()
    val extension = readExtension(value, marker.end)

    NormalizedPhoneWithExtension(
        number = number,
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
 * This vocabulary decides where the number ends, so it is ours. `PhoneExtensionMarkerTest` compares it
 * with the phonenumber library's, which is a larger set read against the national number plan: a spelling
 * they read and we do not is input we refuse and they could have split, and that test says so when it
 * appears.
 *
 * Longest first at each position, so `ext.` is not cut short by `ext`, and `;ext=` is matched whole rather
 * than as a bare `;` that would leave the `ext=` behind in the number.
 *
 * ## No localised label, deliberately
 *
 * `Durchwahl`, `poste`, `anexo`, `ramal`, `interno` and the rest are how extensions are written in most of
 * the world, and none of them is recognised here. Input carrying one is refused on its letters, and a
 * caller whose input has them strips the label before calling - in the place that knows the language.
 *
 * Three reasons, in the order they matter:
 *
 * 1. **The set has no end and no owner.** There is no principle that admits German and stops; there is
 *    only a list that grows whenever somebody reports a number that refused. The list would have to be
 *    versioned, because it decides where a number ends - "which release of the label list split this
 *    token?" is the same question a Unicode release asks, and `phone.extension` is free of it today.
 * 2. **A letter marker is a hole in the letter refusal.** Letters are refused here so that the keypad
 *    conversion in the dependency can never turn a word into digits. Every label admitted is a carve-out
 *    in that rule, in a language nobody maintaining this reads.
 * 3. **The obvious list to copy is partial, and its gaps produce wrong numbers.** Measured against
 *    `phonenumber` 0.0.3: `anexo` and `extensión` are read as labels and split correctly, while `poste`
 *    and `ramal` are not recognised and their letters go through keypad conversion instead -
 *    `+1 212 555 0123 poste 4` parses as `+12125550123767834`, a different, valid-looking number.
 *    Adopting that list wholesale would mean adopting its gaps, and its gaps are the defect this module
 *    exists to avoid. `PhoneLocalisedLabelTest` pins this rather than describing it.
 *
 * **The vocabulary does not have to avoid these words, and could not.** Some labels contain a marker -
 * `anexo` holds an `x`, `extensión` begins with `ext` - so a marker is matched inside the word and the
 * split lands mid-label. That is harmless: whichever side the remaining letters fall on is refused,
 * because [normalizePhone] refuses a letter in the number and [readExtension] refuses one in the
 * extension. The letter rule is what makes every label safe, in every language, without a list.
 */
internal fun findMarker(value: String): MarkerMatch? {
    for (index in value.indices) {
        if (!value[index].couldStartMarker()) continue
        val marker = MARKERS.firstOrNull { value.regionMatches(index, it, 0, it.length, ignoreCase = true) }
            ?: continue
        return MarkerMatch(index, index + marker.length)
    }
    return null
}

/** Longest first, and `;ext=` before the bare `;` it starts with. */
private val MARKERS = listOf(";ext=", "extension", "ext.", "extn", "xtn", "ext", "x", "#", ",", ";")

/** The characters a marker can begin with, so the list above is only walked where it could match. */
private fun Char.couldStartMarker(): Boolean =
    this == '#' || this == ',' || this == ';' || this == 'x' || this == 'X' || this == 'e' || this == 'E'

/**
 * The extension digits from [from] to the end of [value], folded to ASCII, or `null` when the marker has
 * nothing after it.
 *
 * Separators are allowed between the marker and the first digit, because `x 4` and `ext. 4` are ordinary
 * spellings. After the first digit there are only digits: an extension is a number, and anything else in
 * it is something this module does not understand rather than something to drop.
 *
 * **A marker with nothing after it leaves the number alone.** `+1 212 555 0123#` is the number and a
 * marker introducing nothing, so the number is complete and there is no extension to read. Dropping that
 * marker invents nothing, which is the test every relaxation here has to pass, and it is what the
 * phonenumber library does from the release that fixes aughtone/aughtone-phonenumber#23 and #24. What it
 * gives up is a truncation signal: an extension lost upstream now reads the same as one never written.
 */
private fun readExtension(value: String, from: Int): String? {
    val digits = StringBuilder()
    var index = from
    while (index < value.length) {
        val codePoint = value.codePointAtIndex(index)
        val width = if (codePoint >= 0x10000) 2 else 1
        val digit = decimalDigitValue(codePoint)
        when {
            digit != null -> digits.append('0' + digit)
            digits.isEmpty() && codePoint.isMarkerSeparator() -> Unit
            codePoint.isAsciiLetter() -> throw PhoneNormalizationError.LetterNotSupported(index, codePoint)
            else -> throw PhoneNormalizationError.UnsupportedCharacter(index, codePoint)
        }
        index += width
    }
    return digits.toString().takeIf { it.isNotEmpty() }
}

/** What may sit between a marker and the first digit of the extension: ` `, `.`, `-`, `:` and `=`. */
private fun Int.isMarkerSeparator(): Boolean =
    this == 0x20 || this == 0x2E || this == 0x2D || this == 0x3A || this == 0x3D

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
    /** The E.164 number - exactly what [normalizePhone] writes for the text before the marker. */
    val number: NormalizedPhone,

    /** The extension digits, or `null` when the input carried no marker, or a marker introducing nothing. */
    val extension: NormalizedExtension?,
)

/** A normalized extension plus its policy identity. Store all three beside anything derived from it. */
data class NormalizedExtension(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized
