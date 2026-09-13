package io.github.aughtone.normalize.phone

import io.github.aughtone.normalize.common.Comparability
import io.github.aughtone.normalize.common.comparability
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * Every phone policy declares the comparable form `phone.e164`. This pins the declaration twice: the
 * comparability check reports any two phone policies comparable in that form, including region policies
 * rebuilt from their ids, and the same number read internationally and nationally produces identical text.
 * A declaration is a promise a caller matches on; removing it, or a pair that stops agreeing, is the bug.
 *
 * Numbers come from ranges reserved for fiction and documentation.
 */
class PhoneComparableFormsTest {

    private fun text(value: String, policy: PhonePolicy): String = when (val outcome = normalizePhone(value, policy)) {
        is Outcome.Success -> outcome.data.canonical
        is Outcome.Failure -> throw AssertionError("FROZEN: <${policy.id}> must normalize, failed with ${outcome.exception::class.simpleName}")
    }

    @Test
    fun everyPhonePolicyIsComparableInTheE164Form() {
        val ids = listOf(
            "phone.e164",
            "phone.e164+lenient",
            "phone.e164+region-us",
            "phone.e164+region-us+lenient",
            "phone.e164+region-gb",
        )
        for (a in ids) {
            for (b in ids) {
                val result = PhonePolicies.comparability(a, 1, b, 1).dataOrThrow()
                val expected = if (a == b) Comparability.SamePolicy else Comparability.InForm(PhoneForms.E164)
                assertEquals(expected, result, "FROZEN: <$a> against <$b>")
            }
        }
    }

    @Test
    fun theSameNumberReadInternationallyAndNationallyIsTheSameText() {
        assertEquals(text("+1 212 555 0123", PhonePolicy.E164), text("(212) 555-0123", PhonePolicy.e164ForRegion("us")))
        assertEquals(text("+44 20 7123 0000", PhonePolicy.E164), text("020 7123 0000", PhonePolicy.e164ForRegion("gb")))
        assertEquals(text("+1 212 555 0123", PhonePolicy.E164), text("+1 212 555 0123", PhonePolicy.E164Lenient))
    }

    @Test
    fun aRebuiltRegionPolicyDeclaresTheForm() {
        val resolved = PhonePolicies.resolve("phone.e164+region-ca+lenient", 1)
        assertTrue(resolved is Outcome.Success)
        assertEquals(setOf(PhoneForms.E164), resolved.data.forms)
    }
}
