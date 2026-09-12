package io.github.aughtone.normalize.unicode

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * ## If a test here fails, the canonical form changed. That is the bug.
 *
 * The conformance suite proves this module agrees with the Unicode standard. This file proves it keeps
 * producing the same bytes as the day it shipped, which is a different promise: a caller hashes the
 * output and discards the input, so a byte that moves makes every token already derived from it
 * unmatchable - silently, with no way to find the affected records afterwards.
 *
 * ## What to do instead, when a form genuinely needs to change
 *
 * Nothing here changes for a new Unicode release. A release mints a NEW policy - `NfcU18` - with its
 * own id and its own corpus alongside this one. `NfcU17` keeps producing exactly what it produces
 * below, for as long as anyone holds a value derived under it.
 *
 * ## Changes that ARE allowed
 *
 * Adding cases. Deleting or editing an existing expectation is not.
 */
class TextByteStabilityTest {

    private fun canonical(value: String, policy: TextPolicy): String =
        when (val outcome = normalizeText(value, policy)) {
            is Outcome.Success -> outcome.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN CORPUS BROKEN: an input that must normalize now fails with " +
                    "${outcome.exception::class.simpleName}. Do not change the expectation.",
            )
        }

    // Written as escapes rather than literal characters so the expectations survive any editor,
    // and so a reviewer can see exactly which code points are being pinned.
    private val aWithRing = "Å"            // LATIN CAPITAL LETTER A WITH RING ABOVE
    private val aThenRing = "Å"           // A + COMBINING RING ABOVE
    private val angstrom = "Å"             // ANGSTROM SIGN, a singleton decomposition
    private val ohmSign = "Ω"              // OHM SIGN, also a singleton
    private val hangulGa = "가"             // HANGUL SYLLABLE GA
    private val hangulJamo = "가"     // its jamo spelling
    private val ligatureFi = "ﬁ"           // LATIN SMALL LIGATURE FI, compatibility only
    private val superscriptTwo = "²"       // SUPERSCRIPT TWO, compatibility only
    private val fullWidthA = "Ａ"           // FULLWIDTH LATIN CAPITAL LETTER A
    private val qWithTwoMarks = "q̣̇" // q + DOT ABOVE (230) + DOT BELOW (220), out of order
    private val qReordered = "q̣̇"    // the canonical ordering of the same marks
    private val notoSans = "𐌀"       // a supplementary character, unaffected by normalization

    @Test
    fun nfcIsFrozen() {
        val corpus = listOf(
            aThenRing to aWithRing,
            aWithRing to aWithRing,
            angstrom to aWithRing,            // a singleton decomposes and recomposes to the letter
            ohmSign to "Ω",              // OHM SIGN becomes GREEK CAPITAL LETTER OMEGA
            hangulJamo to hangulGa,           // jamo compose arithmetically
            hangulGa to hangulGa,
            qWithTwoMarks to qReordered,      // marks are reordered, not recomposed
            ligatureFi to ligatureFi,         // canonical forms never touch a compatibility mapping
            superscriptTwo to superscriptTwo,
            fullWidthA to fullWidthA,
            notoSans to notoSans,
            "" to "",
            "plain ascii" to "plain ascii",
        )
        for ((input, expected) in corpus) {
            assertEquals(expected, canonical(input, TextPolicy.NfcU17), "FROZEN: NFC of <$input>")
        }
    }

    @Test
    fun nfdIsFrozen() {
        val corpus = listOf(
            aWithRing to aThenRing,
            aThenRing to aThenRing,
            angstrom to aThenRing,
            hangulGa to hangulJamo,
            qWithTwoMarks to qReordered,
            ligatureFi to ligatureFi,
            notoSans to notoSans,
        )
        for ((input, expected) in corpus) {
            assertEquals(expected, canonical(input, TextPolicy.NfdU17), "FROZEN: NFD of <$input>")
        }
    }

    @Test
    fun compatibilityFormsAreFrozenAndLossy() {
        // The lossiness is the point of these two, and it is why they are not the default: `fi` cannot
        // be turned back into the ligature, and the superscript is gone for good.
        assertEquals("fi", canonical(ligatureFi, TextPolicy.NfkcU17))
        assertEquals("fi", canonical(ligatureFi, TextPolicy.NfkdU17))
        assertEquals("2", canonical(superscriptTwo, TextPolicy.NfkcU17))
        assertEquals("A", canonical(fullWidthA, TextPolicy.NfkcU17))
        assertEquals(aWithRing, canonical(angstrom, TextPolicy.NfkcU17))
        assertEquals(aThenRing, canonical(angstrom, TextPolicy.NfkdU17))
    }

    @Test
    fun everyFormIsIdempotent() {
        val inputs = listOf(aThenRing, angstrom, hangulJamo, qWithTwoMarks, ligatureFi, fullWidthA, notoSans)
        for (policy in TextPolicy.all) {
            for (input in inputs) {
                val once = canonical(input, policy)
                assertEquals(once, canonical(once, policy), "FROZEN: ${policy.id} is not idempotent for <$input>")
            }
        }
    }

    @Test
    fun policyIdentitiesAreFrozen() {
        // Stored beside every derived value. Renaming one orphans the data it identifies.
        assertEquals("nfc.u17", TextPolicy.NfcU17.id)
        assertEquals("nfd.u17", TextPolicy.NfdU17.id)
        assertEquals("nfkc.u17", TextPolicy.NfkcU17.id)
        assertEquals("nfkd.u17", TextPolicy.NfkdU17.id)
        for (policy in TextPolicy.all) {
            assertEquals(1, policy.version, "FROZEN: ${policy.id} version")
        }
        assertEquals("17.0.0", UnicodeTables.VERSION)
    }

    @Test
    fun unpairedSurrogatesAreRefused() {
        // Built from code units rather than written as literals: a lone surrogate in source does not
        // survive the JS bundler's UTF-8 round trip, so a literal would quietly become U+FFFD and this
        // test would pass by testing nothing.
        val highOnly = Char(0xD800)
        val lowOnly = Char(0xDC00)
        for (policy in TextPolicy.all) {
            for (broken in listOf("a" + highOnly + "b", "a" + lowOnly + "b", highOnly.toString())) {
                val outcome = normalizeText(broken, policy)
                assertTrue(
                    outcome is Outcome.Failure && outcome.exception is TextNormalizationError.UnpairedSurrogate,
                    "FROZEN: a lone surrogate must be refused under ${policy.id}",
                )
            }
        }
    }
}
