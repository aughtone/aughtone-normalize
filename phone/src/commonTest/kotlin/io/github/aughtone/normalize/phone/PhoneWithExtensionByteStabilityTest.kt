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

        // Markers, each split by the dependency against the national number plan.
        Triple("+1 212 555 0123#4", "+12125550123", "4"),
        Triple("+1 212 555 0123,4", "+12125550123", "4"),
        Triple("+1 212 555 0123;4", "+12125550123", "4"),
        Triple("+1 212 555 0123x4", "+12125550123", "4"),
        Triple("+1 212 555 0123 ext 4", "+12125550123", "4"),
        Triple("+1 212 555 0123 ext. 4", "+12125550123", "4"),
        Triple("+1 212 555 0123 extension 4", "+12125550123", "4"),
        Triple("+1 212 555 0123 x123", "+12125550123", "123"),

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
        for ((input, _, extension) in corpus) {
            if (extension != null) continue
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
    fun aMarkerWithNoDigitsIsRefusedRatherThanDelegated() {
        // Measured against the dependency at 0.0.3: `+1 212 555 0123 ext` parses as +12125550123398,
        // because a marker with nothing after it is not read as one and `ext` then goes through keypad
        // conversion; `+1 212 555 0123#` splits as +1212555 with extension 0123. Neither may be
        // delegated, so both fall to the ordinary path and are refused there.
        for (input in listOf("+1 212 555 0123 ext", "+1 212 555 0123#", "+1 212 555 0123 x")) {
            val outcome = normalizePhoneWithExtension(input, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused, got $outcome")
        }
    }

    @Test
    fun anAmbiguousTrailingGroupIsStillRefused() {
        for (input in listOf("+43 1 58058-0", "+49 30 12345678-12")) {
            val outcome = normalizePhoneWithExtension(input, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused, got $outcome")
        }
    }

    @Test
    fun lettersOutsideAMarkerAreRefusedSoKeypadConversionCannotFire() {
        // The dependency turns a run of three or more letters into digits, as upstream does. A vanity
        // number must never become a number the caller did not type - with or without a marker present.
        for (input in listOf("1-800-FLOWERS", "+1 212 555 0123 FLOWERS x4", "+1 800 CALL x2")) {
            val outcome = normalizePhoneWithExtension(input, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused, got $outcome")
        }
    }
}
