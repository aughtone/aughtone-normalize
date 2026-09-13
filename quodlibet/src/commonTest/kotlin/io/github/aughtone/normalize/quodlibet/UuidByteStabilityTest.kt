package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.Comparability
import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.normalize.common.comparability
import io.github.aughtone.normalize.uuid.NormalizedUuid
import io.github.aughtone.normalize.uuid.UuidForms
import io.github.aughtone.normalize.uuid.UuidNormalizationError
import io.github.aughtone.normalize.uuid.UuidNotation
import io.github.aughtone.normalize.uuid.UuidPolicy
import io.github.aughtone.normalize.uuid.formatUuid
import io.github.aughtone.normalize.uuid.normalizeUuid
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * Every UUID here is the Nil UUID, the Max UUID, or a test vector from RFC 9562 Appendices A and B, read from
 * the RFC text - or, where a refusal needs one, a vector with one field deliberately altered and marked so.
 * Nothing here identifies a real object. No rule here uses Unicode data, so this corpus is unversioned.
 *
 * ## Changes that ARE allowed
 *
 * Adding cases. Deleting or editing an existing expectation is not.
 */
class UuidByteStabilityTest {

    private val v4 = "919108f7-52d1-4320-9bac-f847db4148a8"
    private val v4GuidBytes = "f7089191d15220439bacf847db4148a8"

    private fun canonical(value: String, policy: UuidPolicy): NormalizedUuid = when (val outcome = normalizeUuid(value, policy)) {
        is Outcome.Success -> outcome.data
        is Outcome.Failure -> throw AssertionError("FROZEN: <$value> must normalize under ${policy.id}, failed with ${outcome.exception::class.simpleName}")
    }

    private inline fun <reified E : UuidNormalizationError> assertRefused(value: String, policy: UuidPolicy) {
        val outcome = normalizeUuid(value, policy)
        assertTrue(outcome is Outcome.Failure && outcome.exception is E, "FROZEN: <$value> must be refused with ${E::class.simpleName} under ${policy.id}, got $outcome")
    }

    @Test
    fun everyStringSpellingIsFrozen() {
        val spellings = listOf(
            "919108f7-52d1-4320-9bac-f847db4148a8",
            "919108F7-52D1-4320-9BAC-F847DB4148A8",
            "{919108f7-52d1-4320-9bac-f847db4148a8}",
            "{919108F7-52D1-4320-9BAC-F847DB4148A8}",
            "urn:uuid:919108f7-52d1-4320-9bac-f847db4148a8",
            "URN:UUID:919108F7-52D1-4320-9BAC-F847DB4148A8",
            "919108f752d143209bacf847db4148a8",
            "{919108f752d143209bacf847db4148a8}",
        )
        for (policy in listOf(UuidPolicy.Hex, UuidPolicy.Rfc9562)) {
            for (spelling in spellings) {
                assertEquals(v4, canonical(spelling, policy).canonical, "FROZEN: <$spelling> under ${policy.id}")
            }
        }
    }

    @Test
    fun theRfc9562VectorsAreFrozen() {
        val vectors = mapOf(
            "C232AB00-9414-11EC-B3C8-9F6BDECED846" to "c232ab00-9414-11ec-b3c8-9f6bdeced846",
            "5df41881-3aed-3515-88a7-2f4a814cf09e" to "5df41881-3aed-3515-88a7-2f4a814cf09e",
            "919108f7-52d1-4320-9bac-f847db4148a8" to "919108f7-52d1-4320-9bac-f847db4148a8",
            "2ed6657d-e927-568b-95e1-2665a8aea6a2" to "2ed6657d-e927-568b-95e1-2665a8aea6a2",
            "1EC9414C-232A-6B00-B3C8-9F6BDECED846" to "1ec9414c-232a-6b00-b3c8-9f6bdeced846",
            "017F22E2-79B0-7CC3-98C4-DC0C0C07398F" to "017f22e2-79b0-7cc3-98c4-dc0c0c07398f",
            "2489E9AD-2EE2-8E00-8EC9-32D5F69181C0" to "2489e9ad-2ee2-8e00-8ec9-32d5f69181c0",
            "00000000-0000-0000-0000-000000000000" to "00000000-0000-0000-0000-000000000000",
            "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF" to "ffffffff-ffff-ffff-ffff-ffffffffffff",
        )
        for ((input, expected) in vectors) {
            assertEquals(expected, canonical(input, UuidPolicy.Rfc9562).canonical, "FROZEN: <$input>")
            assertEquals(expected, canonical(input, UuidPolicy.Hex).canonical, "FROZEN: <$input>")
        }
    }

    @Test
    fun theRfc9562PolicyChecksVariantAndVersion() {
        // The v4 vector with its version nibble set to 0: a valid 128-bit value, not an RFC 9562 UUID.
        val versionZero = "919108f7-52d1-0320-9bac-f847db4148a8"
        // The v4 vector with its variant bits set to 01.
        val wrongVariant = "919108f7-52d1-4320-7bac-f847db4148a8"
        assertRefused<UuidNormalizationError.NotRfc9562>(versionZero, UuidPolicy.Rfc9562)
        assertRefused<UuidNormalizationError.NotRfc9562>(wrongVariant, UuidPolicy.Rfc9562)
        assertEquals(versionZero, canonical(versionZero, UuidPolicy.Hex).canonical)
        assertEquals(wrongVariant, canonical(wrongVariant, UuidPolicy.Hex).canonical)
    }

    @Test
    fun stringRefusalsAreFrozen() {
        assertRefused<UuidNormalizationError.MisplacedHyphen>("919108f75-2d1-4320-9bac-f847db4148a8", UuidPolicy.Hex)
        assertRefused<UuidNormalizationError.WrongLength>("919108f7-52d1-4320-9bac-f847db4148a", UuidPolicy.Hex)
        assertRefused<UuidNormalizationError.WrongLength>("919108f752d143209bacf847db4148a", UuidPolicy.Hex)
        assertRefused<UuidNormalizationError.MalformedWrapper>("{919108f7-52d1-4320-9bac-f847db4148a8", UuidPolicy.Hex)
        assertRefused<UuidNormalizationError.MalformedWrapper>("{urn:uuid:919108f7-52d1-4320-9bac-f847db4148a8}", UuidPolicy.Hex)
        assertRefused<UuidNormalizationError.MalformedWrapper>("urn:uuid:{919108f7-52d1-4320-9bac-f847db4148a8}", UuidPolicy.Hex)
        assertRefused<UuidNormalizationError.NotHexadecimal>("919108g7-52d1-4320-9bac-f847db4148a8", UuidPolicy.Hex)
        assertRefused<UuidNormalizationError.NotHexadecimal>(" 919108f7-52d1-4320-9bac-f847db4148a8", UuidPolicy.Hex)
        assertRefused<UuidNormalizationError.NotHexadecimal>("919108f7-52d1-4320-9bac-f847db4148au", UuidPolicy.Hex)
    }

    @Test
    fun guidByteDumpsAreFrozen() {
        val dumps = listOf(
            v4GuidBytes,
            "F7089191D15220439BACF847DB4148A8",
            "F7-08-91-91-D1-52-20-43-9B-AC-F8-47-DB-41-48-A8",
            "f7:08:91:91:d1:52:20:43:9b:ac:f8:47:db:41:48:a8",
            "f7 08 91 91 d1 52 20 43 9b ac f8 47 db 41 48 a8",
            "f7089191-d152-2043-9bac-f847db4148a8",
        )
        for (policy in listOf(UuidPolicy.Hex.guidBytes(), UuidPolicy.Rfc9562.guidBytes())) {
            for (dump in dumps) {
                assertEquals(v4, canonical(dump, policy).canonical, "FROZEN: <$dump> under ${policy.id}")
            }
        }
    }

    @Test
    fun theGuidByteModeRefusesStringForms() {
        val guid = UuidPolicy.Hex.guidBytes()
        assertRefused<UuidNormalizationError.StringFormNotByteDump>("{f7089191-d152-2043-9bac-f847db4148a8}", guid)
        assertRefused<UuidNormalizationError.StringFormNotByteDump>("urn:uuid:f7089191-d152-2043-9bac-f847db4148a8", guid)
        assertRefused<UuidNormalizationError.MisplacedHyphen>("f7-08:91-91-d1-52-20-43-9b-ac-f8-47-db-41-48-a8", guid)
        assertRefused<UuidNormalizationError.WrongLength>("f7-08-91-91-d1-52-20-43-9b-ac-f8-47-db-41-48", guid)
    }

    @Test
    fun everyNotationIsFrozenAndRoundTrips() {
        val uuid = canonical(v4, UuidPolicy.Hex)
        val expected = mapOf(
            UuidNotation.Hyphenated to "919108f7-52d1-4320-9bac-f847db4148a8",
            UuidNotation.Braces to "{919108f7-52d1-4320-9bac-f847db4148a8}",
            UuidNotation.Urn to "urn:uuid:919108f7-52d1-4320-9bac-f847db4148a8",
            UuidNotation.Uppercase to "919108F7-52D1-4320-9BAC-F847DB4148A8",
            UuidNotation.Bare to "919108f752d143209bacf847db4148a8",
            UuidNotation.GuidBytes to v4GuidBytes,
        )
        for (notation in UuidNotation.entries) {
            val rendered = formatUuid(uuid, notation)
            assertEquals(expected.getValue(notation), rendered, "FROZEN: $notation")
            val policy = if (notation == UuidNotation.GuidBytes) UuidPolicy.Hex.guidBytes() else UuidPolicy.Hex
            assertEquals(v4, canonical(rendered, policy).canonical, "FROZEN: $notation must round-trip through ${policy.id}")
        }
    }

    @Test
    fun identitiesAndFormsAreFrozen() {
        assertEquals("uuid.hex", UuidPolicy.Hex.id)
        assertEquals("uuid.rfc9562", UuidPolicy.Rfc9562.id)
        assertEquals("uuid.hex+guid-bytes", UuidPolicy.Hex.guidBytes().id)
        assertEquals("uuid.rfc9562+guid-bytes", UuidPolicy.Rfc9562.guidBytes().id)

        fun comparability(a: String, b: String) = QuodlibetPolicies.comparability(a, 1, b, 1).dataOrThrow()
        assertEquals(Comparability.InForm(UuidForms.Uuid), comparability("uuid.hex", "uuid.rfc9562"))
        assertEquals(Comparability.NotComparable, comparability("uuid.hex", "uuid.hex+guid-bytes"))
        assertEquals(Comparability.InForm(UuidForms.Uuid), comparability("uuid.hex", "uuid.hex+guid-bytes+form.uuid"))

        val optedIn = UuidPolicy.Hex.guidBytes().withForms(setOf(UuidForms.Uuid))
        val stored = normalizeUuid(v4GuidBytes, optedIn)
        assertTrue(stored is Outcome.Success)
        assertEquals("uuid.hex+guid-bytes+form.uuid", stored.data.policyId)
        assertEquals(v4, stored.data.canonical)

        val notOffered = QuodlibetPolicies.resolve("uuid.hex+form.uuid", 1)
        assertTrue(notOffered is Outcome.Failure && notOffered.exception is PolicyIdentityError.FormNotOffered, "got $notOffered")
        for (id in listOf("uuid.hex", "uuid.rfc9562+guid-bytes", "uuid.rfc9562+guid-bytes+form.uuid")) {
            assertTrue(QuodlibetPolicies.resolve(id, 1) is Outcome.Success, "<$id> must resolve")
        }
    }
}
