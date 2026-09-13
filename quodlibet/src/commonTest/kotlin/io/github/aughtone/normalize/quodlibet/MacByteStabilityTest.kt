package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.Comparability
import io.github.aughtone.normalize.common.comparability
import io.github.aughtone.normalize.mac.MacForms
import io.github.aughtone.normalize.mac.MacNormalizationError
import io.github.aughtone.normalize.mac.MacNotation
import io.github.aughtone.normalize.mac.MacPolicy
import io.github.aughtone.normalize.mac.NormalizedMac
import io.github.aughtone.normalize.mac.formatMac
import io.github.aughtone.normalize.mac.normalizeMac
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * Every address comes from the ranges RFC 7042 reserves for documentation: `00-00-5E-00-53-00` to
 * `00-00-5E-00-53-FF` for EUI-48 and `00-00-5E-EF-10-00-00-00` to `00-00-5E-EF-10-00-00-FF` for EUI-64, so
 * nothing here names a real device. No rule here uses Unicode data, so this corpus is unversioned.
 *
 * ## Changes that ARE allowed
 *
 * Adding cases. Deleting or editing an existing expectation is not.
 */
class MacByteStabilityTest {

    private fun canonical(value: String, policy: MacPolicy): NormalizedMac = when (val outcome = normalizeMac(value, policy)) {
        is Outcome.Success -> outcome.data
        is Outcome.Failure -> throw AssertionError("FROZEN: <$value> must normalize under ${policy.id}, failed with ${outcome.exception::class.simpleName}")
    }

    private inline fun <reified E : MacNormalizationError> assertRefused(value: String, policy: MacPolicy) {
        val outcome = normalizeMac(value, policy)
        assertTrue(outcome is Outcome.Failure && outcome.exception is E, "FROZEN: <$value> must be refused with ${E::class.simpleName}, got $outcome")
    }

    @Test
    fun everyEui48SpellingIsFrozen() {
        val spellings = listOf(
            "00:00:5e:00:53:01",
            "00:00:5E:00:53:01",
            "00-00-5e-00-53-01",
            "00-00-5E-00-53-01",
            "0000.5e00.5301",
            "0000.5E00.5301",
            "00005e005301",
            "00005E005301",
            "0:0:5e:0:53:1",
            "0-0-5E-0-53-1",
            "0.5e00.5301",
        )
        for (spelling in spellings) {
            assertEquals("00:00:5e:00:53:01", canonical(spelling, MacPolicy.Eui48).canonical, "FROZEN: <$spelling>")
        }
        assertEquals("00:00:5e:00:53:ff", canonical("00-00-5E-00-53-FF", MacPolicy.Eui48).canonical)
    }

    @Test
    fun everyEui64SpellingIsFrozen() {
        val spellings = listOf(
            "00:00:5e:ef:10:00:00:01",
            "00-00-5E-EF-10-00-00-01",
            "0000.5eef.1000.0001",
            "00005EEF10000001",
            "0:0:5e:ef:10:0:0:1",
            "0.5eef.1000.1",
        )
        for (spelling in spellings) {
            assertEquals("00:00:5e:ef:10:00:00:01", canonical(spelling, MacPolicy.Eui64).canonical, "FROZEN: <$spelling>")
        }
    }

    @Test
    fun refusalsAreFrozen() {
        assertRefused<MacNormalizationError.MixedSeparators>("00:00-5e:00:53:01", MacPolicy.Eui48)
        assertRefused<MacNormalizationError.MixedSeparators>("0000.5e00:5301", MacPolicy.Eui48)
        assertRefused<MacNormalizationError.WrongLength>("00:00:5e:00:53", MacPolicy.Eui48)
        assertRefused<MacNormalizationError.WrongLength>("00005e00530", MacPolicy.Eui48)
        assertRefused<MacNormalizationError.WrongLength>("", MacPolicy.Eui48)
        // Never widened into each other.
        assertRefused<MacNormalizationError.WrongLength>("00:00:5e:ef:10:00:00:01", MacPolicy.Eui48)
        assertRefused<MacNormalizationError.WrongLength>("00:00:5e:00:53:01", MacPolicy.Eui64)
        assertRefused<MacNormalizationError.MalformedGroup>("00::5e:00:53:01", MacPolicy.Eui48)
        assertRefused<MacNormalizationError.MalformedGroup>("000:0:5e:00:53:01", MacPolicy.Eui48)
        assertRefused<MacNormalizationError.MalformedGroup>("00000.5e00.5301", MacPolicy.Eui48)
        assertRefused<MacNormalizationError.NotHexadecimal>("00:00:5g:00:53:01", MacPolicy.Eui48)
        // Nothing is trimmed.
        assertRefused<MacNormalizationError.NotHexadecimal>(" 00005e005301", MacPolicy.Eui48)
    }

    @Test
    fun theMulticastAndLocalBitsAreNotValidated() {
        assertEquals("01:00:5e:90:10:01", canonical("01-00-5E-90-10-01", MacPolicy.Eui48).canonical)
        assertEquals("ff:ff:ff:ff:ff:ff", canonical("FF:FF:FF:FF:FF:FF", MacPolicy.Eui48).canonical)
    }

    @Test
    fun everyNotationIsFrozenAndRoundTrips() {
        val address = canonical("00:00:5e:00:53:01", MacPolicy.Eui48)
        val expected = mapOf(
            MacNotation.Colon to "00:00:5e:00:53:01",
            MacNotation.Ieee to "00-00-5E-00-53-01",
            MacNotation.CiscoDotted to "0000.5e00.5301",
            MacNotation.Bare to "00005e005301",
        )
        for (notation in MacNotation.entries) {
            val rendered = formatMac(address, notation)
            assertEquals(expected.getValue(notation), rendered, "FROZEN: $notation")
            assertEquals(address.canonical, canonical(rendered, MacPolicy.Eui48).canonical, "FROZEN: $notation must round-trip")
        }
        val eui64 = canonical("00:00:5e:ef:10:00:00:01", MacPolicy.Eui64)
        assertEquals("0000.5eef.1000.0001", formatMac(eui64, MacNotation.CiscoDotted))
        assertEquals("00-00-5E-EF-10-00-00-01", formatMac(eui64, MacNotation.Ieee))
    }

    @Test
    fun identitiesAndFormsAreFrozen() {
        assertEquals("mac.eui48", MacPolicy.Eui48.id)
        assertEquals("mac.eui64", MacPolicy.Eui64.id)
        assertEquals(setOf(MacForms.Eui48), MacPolicy.Eui48.forms)
        assertEquals(setOf(MacForms.Eui64), MacPolicy.Eui64.forms)
        assertEquals(Comparability.NotComparable, QuodlibetPolicies.comparability("mac.eui48", 1, "mac.eui64", 1).dataOrThrow())
        for (policy in listOf(MacPolicy.Eui48, MacPolicy.Eui64)) {
            val resolved = QuodlibetPolicies.resolve(policy.id, policy.version)
            assertTrue(resolved is Outcome.Success, "<${policy.id}> must resolve")
        }
    }
}
