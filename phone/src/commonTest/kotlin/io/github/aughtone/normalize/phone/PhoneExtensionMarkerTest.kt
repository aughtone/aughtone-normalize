package io.github.aughtone.normalize.phone

import io.github.aughtone.phonenumber.PhoneNumberUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The extension markers the dependency recognises, compared with ours.
 *
 * `normalizePhoneWithExtension` splits at the marker itself and never hands raw input to the phonenumber
 * library, so this vocabulary is ours and a change in theirs cannot move our split. What it can tell us is
 * that the two have diverged: a spelling they read and we do not is input they would have split and we
 * refuse, which is safe but is a form somebody writes numbers in.
 *
 * **This test exists to fail on a dependency upgrade**, and a failure is a question rather than a defect:
 * read their release notes, then either add the spelling to `findMarker` or record why not. The marker set
 * is pinned per release there and a change to it is a called-out byte change.
 *
 * **It detects divergence; it does not prove coverage.** The list below is one we maintain, so a spelling
 * neither side thought of is invisible here - which is exactly how the absence of every localised label
 * went unnoticed while an acceptance criterion claimed one was pinned. Read it as "these agree", never as
 * "nothing is missing". Localised labels are refused deliberately and `PhoneLocalisedLabelTest` says so.
 *
 * Pinned against `io.github.aughtone:phonenumber:0.0.3`.
 */
class PhoneExtensionMarkerTest {

    /** Each spelling, and the extension the dependency must read from `+1 212 555 0123<marker>4`. */
    private val markers: List<String> = listOf(
        "#", ",", ";", ";ext=", "x", "X", " ext", " ext.", " extn", " xtn", " extension",
    )

    @Test
    fun theDependencyStillReadsEveryMarkerWeRecognise() {
        for (marker in markers) {
            val input = "+1 212 555 0123$marker" + "4"
            val parsed = PhoneNumberUtil.parse(input, "US")
            assertEquals(
                "+12125550123",
                parsed.formatToE164(),
                "<$input>: the dependency no longer splits this marker off the number. Our own split is " +
                    "unaffected; read its release notes and decide whether the spelling still belongs here.",
            )
            assertEquals("4", parsed.extension, "<$input>: extension")
        }
    }

    @Test
    fun weRecogniseEveryMarkerTheDependencyReads() {
        for (marker in markers) {
            val input = "+1 212 555 0123$marker" + "4"
            assertTrue(
                findMarker(input) != null,
                "<$input>: the dependency reads this marker and we do not, so we refuse input it could have " +
                    "split. Add the spelling to findMarker, or record why it stays out.",
            )
        }
    }

    @Test
    fun anOrdinaryNumberCarriesNoMarker() {
        // No marker means the whole input is an ordinary number, read start to finish by normalizePhone.
        // `+43 1 58058-0` is the case that matters: its hyphen is a separator, not a marker, so the
        // ambiguity guard decides it rather than a split.
        for (input in listOf("+1 212 555 0123", "+1 (212) 555-0123", "+49 89 636 48018", "+43 1 58058-0")) {
            assertEquals(null, findMarker(input), "<$input> carries no marker, so it is read as one number")
        }
    }
}
