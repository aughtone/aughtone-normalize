package io.github.aughtone.normalize.phone

import io.github.aughtone.phonenumber.PhoneNumberUtil
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A localised extension label is refused, and the divergence from the dependency is a decision.
 *
 * `Durchwahl`, `poste`, `anexo` and the rest are how extensions are written in most of the world, and
 * `findMarker` recognises none of them - its KDoc gives the reasons. This test exists so that the choice
 * cannot drift into partial support unnoticed, and so the next person meets an argument rather than a gap:
 * an acceptance criterion on #31 claimed a localised label was pinned when none was, and nothing failed.
 *
 * See aughtone/aughtone-normalize#36.
 */
class PhoneLocalisedLabelTest {

    /** A label per language, written the way its speakers write it. */
    private val labels = listOf("Durchwahl", "durchwahl", "poste", "anexo", "ramal", "interno", "extensión")

    @Test
    fun aLocalisedLabelIsRefusedWhicheverLanguageItIsIn() {
        // What is asserted is the refusal, not that no marker was found - and the difference is the point.
        // Some labels *contain* a marker: `anexo` holds an `x`, `extensión` begins with `ext`. So a marker
        // is matched inside the word, the split lands mid-label, and the letters left on one side or the
        // other are refused. The letter rule is what makes every label safe, not a vocabulary that
        // carefully avoids them - which could not be written anyway, in languages nobody here reads.
        for (label in labels) {
            val input = "+1 212 555 0123 $label 4"
            val outcome = normalizePhoneWithExtension(input, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused, got $outcome")
            assertTrue(
                outcome.exception is PhoneNormalizationError.LetterNotSupported,
                "<$input> must be refused for its letters, got ${outcome.exception::class.simpleName}",
            )
        }
    }

    @Test
    fun theDependencyReadsSomeOfTheseAndWeDeliberatelyDoNot() {
        // The divergence, recorded as a choice. These two are in the library's label data and split
        // correctly there; we still refuse them, because admitting them means owning the whole list.
        for (label in listOf("anexo", "extensión")) {
            val input = "+1 212 555 0123 $label 4"
            val parsed = PhoneNumberUtil.parse(input, "US")
            assertEquals("+12125550123", parsed.formatToE164(), "<$input>: the dependency splits this")
            assertEquals("4", parsed.extension, "<$input>: the dependency reads the extension")
            assertTrue(
                normalizePhoneWithExtension(input, ExtensionPolicy.E164) is Outcome.Failure,
                "<$input>: we refuse it anyway - see findMarker's KDoc before changing this",
            )
        }
    }

    @Test
    fun theLabelListIsPartialAndItsGapsFoldLettersIntoTheNumber() {
        // The reason the list is not worth copying. `poste` and `ramal` are not in it, so their letters go
        // through keypad conversion and land in the subscriber number - a different, valid-looking number,
        // which is the defect #30 exists to prevent. Refusing every label keeps all of this away from us.
        for ((label, folded) in listOf("poste" to "+12125550123767834", "ramal" to "+12125550123726254")) {
            val input = "+1 212 555 0123 $label 4"
            val parsed = PhoneNumberUtil.parse(input, "US")
            assertEquals(
                folded,
                parsed.formatToE164(),
                "<$input>: FIXED UPSTREAM, or the label list grew. Read the release notes - if this label " +
                    "is now recognised, the argument in findMarker's KDoc is unchanged but its evidence moved.",
            )
            assertEquals(null, parsed.extension, "<$input>: no extension is captured, the digits are folded in")
            assertTrue(
                normalizePhoneWithExtension(input, ExtensionPolicy.E164) is Outcome.Failure,
                "<$input>: we refuse it, which is why the fold cannot reach us",
            )
        }
    }
}
