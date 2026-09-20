package io.github.aughtone.normalize.phone

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * What `normalizePhoneWithExtension` writes: the E.164 number, and the extension beside it. Both are
 * tokenized by callers, so both are frozen. A failure here means the split between number and extension
 * moved, which silently re-keys every token derived under the old split.
 *
 * Adding cases is allowed. Editing one is a new policy version, not an edit.
 */
class PhoneWithExtensionByteStabilityTest {

    private fun read(value: String): NormalizedPhoneWithExtension {
        val outcome = normalizePhoneWithExtension(value, ExtensionPolicy.E164)
        assertTrue(outcome is Outcome.Success, "FROZEN CORPUS BROKEN: <$value> must normalize, got $outcome")
        return outcome.data
    }

    /** Input to number and extension. A `null` extension means the input carried none. */
    private val corpus: List<Triple<String, String, String?>> = listOf(
        // No marker: the number is read by the ordinary path and nothing is delegated.
        Triple("+12125550123", "+12125550123", null),
        Triple("+1 (212) 555-0123", "+12125550123", null),
        Triple("+49 89 636 48018", "+498963648018", null),

        // Markers: the marker is the boundary, so the number is the text before it read by the ordinary
        // rules, and the extension is the digits after it.
        Triple("+1 212 555 0123#4", "+12125550123", "4"),
        Triple("+1 212 555 0123,4", "+12125550123", "4"),
        Triple("+1 212 555 0123;4", "+12125550123", "4"),
        Triple("+1 212 555 0123x4", "+12125550123", "4"),
        Triple("+1 212 555 0123 ext 4", "+12125550123", "4"),
        Triple("+1 212 555 0123 ext. 4", "+12125550123", "4"),
        Triple("+1 212 555 0123 extension 4", "+12125550123", "4"),
        Triple("+1 212 555 0123 x123", "+12125550123", "123"),
        Triple("+1 212 555 0123;ext=4", "+12125550123", "4"),
        Triple("+1 212 555 0123 x 4", "+12125550123", "4"),

        // A marker introducing nothing leaves the number alone and reads no extension - the behaviour the
        // phonenumber library takes from the release fixing aughtone/aughtone-phonenumber#23 and #24.
        Triple("+1 212 555 0123#", "+12125550123", null),
        Triple("+1 212 555 0123 ext", "+12125550123", null),
        Triple("+1 212 555 0123 x", "+12125550123", null),
        Triple("+1 212 555 0123,", "+12125550123", null),
        Triple("+1 212 555 0123;ext=", "+12125550123", null),

        // A space-separated trailing group is refused only when the whole number is invalid with it, so
        // this one is accepted as a number and an extension - the same reading `normalizePhone` gives the
        // text before the marker. #30 narrowed the rule deliberately; see its corpus.
        Triple("+49 89 636 48018 9 x1", "+4989636480189", "1"),

        // Extension digits fold to ASCII by the same rule as the number: any script, one spelling.
        Triple("+1 212 555 0123 ext ４", "+12125550123", "4"),
        Triple("+1 212 555 0123 ext ٤", "+12125550123", "4"),
        Triple("+1 212 555 0123 ext ४", "+12125550123", "4"),
    )

    @Test
    fun theNumberAndExtensionAreTheFrozenBytes() {
        for ((input, number, extension) in corpus) {
            val read = read(input)
            assertEquals(number, read.number.canonical, "FROZEN CORPUS BROKEN for <$input>: number")
            assertEquals(extension, read.extension?.canonical, "FROZEN CORPUS BROKEN for <$input>: extension")
        }
    }

    @Test
    fun theNumberIsExactlyWhatNormalizePhoneWritesWhereItAcceptsTheInput() {
        for ((input, _, _) in corpus) {
            // Marker-free input only: with a marker the number comes from the text before it, which is
            // what `PhoneExtensionAgreementTest` compares. A null extension no longer means no marker.
            if (findMarker(input) != null) continue
            val direct = normalizePhone(input, PhonePolicy.E164)
            assertTrue(direct is Outcome.Success, "<$input>")
            assertEquals(direct.data, read(input).number, "<$input> differs from normalizePhone")
        }
    }

    @Test
    fun theIdentitiesAreFrozen() {
        assertEquals("phone.extension", ExtensionPolicy.E164.id)
        assertEquals(1, ExtensionPolicy.E164.version)
        val read = read("+1 212 555 0123 x4")
        assertEquals("phone.extension", read.extension?.policyId)
        assertEquals(1, read.extension?.policyVersion)
        assertEquals("phone.e164", read.number.policyId)
    }

    @Test
    fun aMarkerWithNoDigitsReadsNoExtensionRatherThanAnEmptyOne() {
        // The number is complete and the marker introduces nothing, so it is dropped - it cannot be told
        // from an input that never carried one, which is the cost recorded on the function.
        for (input in listOf("+1 212 555 0123 ext", "+1 212 555 0123#", "+1 212 555 0123 x")) {
            assertEquals(null, read(input).extension, "<$input> must read no extension")
            assertEquals("+12125550123", read(input).number.canonical, "<$input> must keep the number")
        }
        // `normalizePhone` still refuses the same text, because it reads no extensions at all and a marker
        // there could only mean digits it would have to splice onto the number.
        assertTrue(normalizePhone("+1 212 555 0123#", PhonePolicy.E164) is Outcome.Failure)
    }

    @Test
    fun anAmbiguousTrailingGroupIsStillRefused() {
        // Including when an extension marker follows it. The number is read by `normalizePhone` from the
        // text before the marker, so the guard cannot be walked around by writing the extension out:
        // `+43 1 58058-0#4` once returned `+431580580`, the wrong line. See aughtone/aughtone-normalize#32.
        for (input in listOf(
            "+43 1 58058-0",
            "+49 30 12345678-12",
            "+43 1 58058-0#4",
            "+43 1 58058-0 x4",
            "+49 30 12345678-12 x4",
            "+41 44 123 45 67-8 x2",
        )) {
            val outcome = normalizePhoneWithExtension(input, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused, got $outcome")
        }
    }

    @Test
    fun lettersOutsideAMarkerAreRefused() {
        // A vanity number must never become a number the caller did not type. The number part goes through
        // `normalizePhone`, which refuses a letter, and the extension part takes digits only - so neither
        // half can reach the keypad conversion the dependency performs on a run of three or more letters.
        for (input in listOf(
            "1-800-FLOWERS",
            "+1 212 555 0123 FLOWERS x4",
            "+1 800 CALL x2",
            "+1 212 555 0123 x4 FLOWERS",
            "+1 212 555 0123 x4x5",
        )) {
            val outcome = normalizePhoneWithExtension(input, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused, got $outcome")
        }
    }
}
