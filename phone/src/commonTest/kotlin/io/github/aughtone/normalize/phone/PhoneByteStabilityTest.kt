package io.github.aughtone.normalize.phone

import io.github.aughtone.phonenumber.PhoneNumberUtil
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * Every number here is from a documentation range reserved for examples - the `555-01xx` block in North
 America, and the ranges regulators set aside elsewhere - so nothing in this repository can ring a real
 * telephone.
 *
 * If an expectation changes, the canonical form changed, and every token already derived under that
 * policy is unmatchable. That includes changes caused by moving to a newer phonenumber release: the
 * corpus is what tells you a dependency bump was not invisible.
 */
class PhoneByteStabilityTest {

    private fun canonical(value: String, policy: PhonePolicy): String =
        when (val outcome = normalizePhone(value, policy)) {
            is Outcome.Success -> outcome.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN: <$value> must normalize, failed with ${outcome.exception::class.simpleName}",
            )
        }

    private inline fun <reified E : PhoneNormalizationError> assertRefused(value: String, policy: PhonePolicy) {
        when (val outcome = normalizePhone(value, policy)) {
            is Outcome.Success -> throw AssertionError("FROZEN: <$value> must be refused, produced <${outcome.data.canonical}>")
            is Outcome.Failure -> assertTrue(
                outcome.exception is E,
                "FROZEN: <$value> refused for the wrong reason (${outcome.exception::class.simpleName})",
            )
        }
    }

    @Test
    fun internationalInputIsFrozen() {
        val corpus = listOf(
            "+12125550123" to "+12125550123",
            "+1 212 555 0123" to "+12125550123",
            "+1 (212) 555-0123" to "+12125550123",
            "+1.212.555.0123" to "+12125550123",
            "+1-212-555-0123" to "+12125550123",
            "  +1 212 555 0123  " to "+12125550123",
            "+442071230000" to "+442071230000",
        )
        for ((input, expected) in corpus) {
            assertEquals(expected, canonical(input, PhonePolicy.E164), "FROZEN: <$input>")
        }
    }

    @Test
    fun digitsFromAnyScriptAreConverted() {
        // The library publishes a frozen digit table covering every Unicode decimal digit, so a number
        // typed on a Thai or Arabic keyboard normalizes to the same bytes as one typed on a Latin one.
        // This module ships no digit table of its own, which is why `:phone` needs that dependency.
        val arabicIndic = "+١٢١٢٥٥٥٠١٢٣"
        val thai = "+๑๒๑๒๕๕๕๐๑๒๓"
        val fullwidth = "＋１２１２５５５０１２３"
        for (input in listOf(arabicIndic, thai, fullwidth)) {
            assertEquals("+12125550123", canonical(input, PhonePolicy.E164), "FROZEN: <$input>")
        }
    }

    @Test
    fun nationalInputNeedsARegionAndIsFrozen() {
        val us = PhonePolicy.e164ForRegion("us")
        assertEquals("+12125550123", canonical("(212) 555-0123", us))
        assertEquals("+12125550123", canonical("212-555-0123", us))
        // An international number still normalizes the same way under a region policy.
        assertEquals("+442071230000", canonical("+44 20 7123 0000", us))

        val uk = PhonePolicy.e164ForRegion("gb")
        assertEquals("+442071230000", canonical("020 7123 0000", uk))
    }

    @Test
    fun refusalsAreFrozen() {
        // No country code and no region: the code would have to be guessed, and a guess produces a
        // valid-looking token for a different number.
        assertRefused<PhoneNormalizationError.MissingCountryCode>("212-555-0123", PhonePolicy.E164)
        // An international dialling prefix is a national way of writing "international".
        assertRefused<PhoneNormalizationError.MissingCountryCode>("011 44 20 7123 0000", PhonePolicy.E164)
        assertRefused<PhoneNormalizationError.LetterNotSupported>("+1-800-FLOWERS", PhonePolicy.E164)
        assertRefused<PhoneNormalizationError.MisplacedPlus>("+1 212+555 0123", PhonePolicy.E164)
        assertRefused<PhoneNormalizationError.NoDigits>("+", PhonePolicy.E164)
        assertRefused<PhoneNormalizationError.UnpairedSurrogate>("+1212555012" + Char(0xD800), PhonePolicy.E164)
        // A non-breaking space is not ASCII formatting; the strict policy will not guess at it.
        assertRefused<PhoneNormalizationError.UnsupportedCharacter>("+1 212 5550123", PhonePolicy.E164)
    }

    @Test
    fun leniencyDropsCruftAndAcceptsImplausibleNumbers() {
        // The lenient policies relax two things and nothing else: unsupported characters are dropped
        // rather than refused, and a number the metadata calls invalid is normalized anyway.
        assertEquals("+12125550123", canonical("+1 212 5550123", PhonePolicy.E164Lenient))
        // Too short to be a real US number: the strict policy refuses it, the lenient one takes it.
        assertRefused<PhoneNormalizationError.NotValidForRegion>("+1 212 555", PhonePolicy.E164)
        assertEquals("+1212555", canonical("+1 212 555", PhonePolicy.E164Lenient))
        // What leniency does NOT relax: an unassigned country code has no E.164 form at all, so there
        // is nothing to be lenient about. Accepting it would mean inventing the one thing E.164 needs.
        assertRefused<PhoneNormalizationError.UnknownCountryCode>("+999 1234", PhonePolicy.E164Lenient)
        // Letters are still refused under leniency: they are not cruft, they are a number this
        // normalizer cannot read.
        assertRefused<PhoneNormalizationError.LetterNotSupported>("+1-800-FLOWERS", PhonePolicy.E164Lenient)
    }

    @Test
    fun outputDoesNotDependOnTheRegionUsedToParse() {
        // The E.164-only policies pass a fixed region to the parser because one is required, and this
        // pins that it cannot influence the result - "it does not matter" being exactly the sort of
        // claim that quietly stops being true.
        val international = "+12125550123"
        val viaNoRegion = canonical(international, PhonePolicy.E164)
        for (region in listOf("us", "ca", "gb", "de", "jp")) {
            assertEquals(viaNoRegion, canonical(international, PhonePolicy.e164ForRegion(region)), region)
        }
    }

    @Test
    fun policyIdentitiesAreFrozen() {
        assertEquals("phone.e164", PhonePolicy.E164.id)
        assertEquals("phone.e164+lenient", PhonePolicy.E164Lenient.id)
        assertEquals("phone.e164+region-ca", PhonePolicy.e164ForRegion("ca").id)
        assertEquals("phone.e164+region-ca+lenient", PhonePolicy.e164ForRegionLenient("ca").id)
        // The region is normalized into the identity, so two spellings of one region are one policy.
        assertEquals(PhonePolicy.e164ForRegion("ca").id, PhonePolicy.e164ForRegion("CA").id)
    }

    @Test
    fun anUnusableRegionFailsWhenThePolicyIsCreated() {
        // Not when a number is normalized under it, and certainly not by falling back to somewhere else.
        assertFailsWith<IllegalArgumentException> { PhonePolicy.e164ForRegion("zz") }
        assertFailsWith<IllegalArgumentException> { PhonePolicy.e164ForRegion("001") }
        assertFailsWith<IllegalArgumentException> { PhonePolicy.e164ForRegion("u") }
        assertFailsWith<IllegalArgumentException> { PhonePolicy.e164ForRegion("") }
    }

    @Test
    fun theFrozenVersionsAreRecorded() {
        // What version 1 of these policies was frozen against. A change here means the canonical form
        // may have moved, which is what the corpus above is for.
        assertEquals("9.0.39", PhoneNumberUtil.metadataVersion)
        assertEquals("17.0.0", PhoneNumberUtil.digitUnicodeVersion)
    }
}
