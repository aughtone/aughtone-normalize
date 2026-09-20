package io.github.aughtone.normalize.phone

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * **The spelling of a separator must not change the answer.** Every case here is a number this suite
 * already pins in ASCII, rewritten with the separator someone actually typed - a typographic dash from a
 * word processor, a no-break space from a web page, fullwidth punctuation from a CJK keyboard - and every
 * one must give exactly what its ASCII twin gives, acceptance and refusal alike.
 *
 * This exists because the separator class was once narrower than the digit class. A decimal digit is read
 * from every `Nd` block, so `＋１ ２１２ ５５５ ０１２３` was understood; the separators around it were
 * matched against seven ASCII characters, so `+43 1 58058–0` with an en dash dropped its dash, took the
 * trailing-group guard with it, and returned `+431580580` - the wrong line, and the exact number #30
 * exists to refuse. See aughtone/aughtone-normalize#39.
 */
class PhoneSeparatorByteStabilityTest {

    private fun read(value: String, policy: PhonePolicy): Outcome<NormalizedPhone> =
        normalizePhone(value, policy)

    private fun canonical(value: String, policy: PhonePolicy): String {
        val outcome = read(value, policy)
        assertTrue(outcome is Outcome.Success, "FROZEN CORPUS BROKEN: <$value> must normalize, got $outcome")
        return outcome.data.canonical
    }

    /** Every dash that is written where a hyphen is meant. */
    private val dashes = listOf(
        '-' to "hyphen-minus",
        '‐' to "hyphen",
        '‑' to "non-breaking hyphen",
        '‒' to "figure dash",
        '–' to "en dash",
        '—' to "em dash",
        '―' to "horizontal bar",
        '−' to "minus sign",
        '－' to "fullwidth hyphen-minus",
        'ー' to "katakana-hiragana prolonged sound mark",
    )

    /** Every space that is written between groups. */
    private val spaces = listOf(
        ' ' to "space",
        ' ' to "no-break space",
        '­' to "soft hyphen",
        '​' to "zero-width space",
        '⁠' to "word joiner",
        '　' to "ideographic space",
    )

    @Test
    fun aDurchwahlRefusesWhicheverDashWasTyped() {
        // The bug this file exists for. Autocorrect turns `-` into an en dash on its own, so the
        // non-ASCII spellings are the ordinary ones once text has been through anything that formats it.
        for ((dash, name) in dashes) {
            val input = "+43 1 58058${dash}0"
            for (policy in listOf(PhonePolicy.E164, PhonePolicy.E164Lenient)) {
                val outcome = read(input, policy)
                assertTrue(
                    outcome is Outcome.Failure && outcome.exception is PhoneNormalizationError.AmbiguousTrailingGroup,
                    "FROZEN CORPUS BROKEN: <$input> ($name) under ${policy.id} must refuse as ambiguous, got $outcome",
                )
            }
        }
    }

    @Test
    fun anOrdinaryNumberNormalizesWhicheverSeparatorWasTyped() {
        for ((separator, name) in dashes + spaces) {
            val input = "+1${separator}212${separator}555${separator}0123"
            for (policy in listOf(PhonePolicy.E164, PhonePolicy.E164Lenient)) {
                assertEquals(
                    "+12125550123",
                    canonical(input, policy),
                    "FROZEN CORPUS BROKEN: <$input> ($name) under ${policy.id}",
                )
            }
        }
    }

    @Test
    fun aNumberWrittenWhollyInFullwidthCharactersNormalizes() {
        // Fullwidth digits were always accepted; the fullwidth punctuation beside them was not, which is
        // the mismatch in one line. `＋` was already handled, which is why it was the only one that worked.
        assertEquals("+12125550123", canonical("＋１（２１２）５５５－０１２３", PhonePolicy.E164))
        assertEquals("+493012345678", canonical("＋４９　３０　１２３４５６７８", PhonePolicy.E164))
    }

    @Test
    fun aWaitForDialToneIsRefusedLikeEveryOtherMarker() {
        // `~` and its siblings say what follows is not the number. Dropping one and running the digits
        // together folds a wrong number exactly as a comma would, so they refuse under every policy.
        for (marker in listOf('~', '⁓', '∼', '～')) {
            val input = "+1 212 555 0123${marker}4"
            for (policy in listOf(PhonePolicy.E164, PhonePolicy.E164Lenient)) {
                val outcome = read(input, policy)
                assertTrue(
                    outcome is Outcome.Failure && outcome.exception is PhoneNormalizationError.ExtensionNotSupported,
                    "FROZEN CORPUS BROKEN: <$input> under ${policy.id} must refuse, got $outcome",
                )
            }
        }
    }
}
