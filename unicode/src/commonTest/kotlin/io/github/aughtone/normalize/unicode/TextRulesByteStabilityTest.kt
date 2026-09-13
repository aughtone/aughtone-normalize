package io.github.aughtone.normalize.unicode

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * ## If a test here fails, a configured text policy's canonical form changed. That is the bug.
 *
 * A caller hashes the output and discards the input, so a byte that moves makes every token already
 * derived under that configuration unmatchable, silently. The ids pinned here are stored beside those
 * tokens, and renaming one orphans the data it identifies.
 *
 * Characters are written as escapes so a reviewer can see exactly which code points are pinned, and so
 * no editor or bundler can quietly substitute one. Lone surrogates are built from code units, because a
 * literal one does not survive the JS bundler.
 *
 * ## Changes that ARE allowed
 *
 * Adding cases. Deleting or editing an existing expectation is not.
 */
class TextRulesByteStabilityTest {

    private fun canonical(value: String, policy: TextPolicy): String =
        when (val outcome = normalizeText(value, policy)) {
            is Outcome.Success -> outcome.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN: <${policy.id}> must normalize this input, failed with ${outcome.exception::class.simpleName}",
            )
        }

    private val u17 = UnicodeRelease.U17

    @Test
    fun asciiRulesTouchOnlyAscii() {
        val cases = listOf(
            // stripControl keeps the ASCII whitespace controls for the space rules and removes the rest.
            Triple(TextPolicy { ascii { stripControl() } }, "a\u0000b\u007Fc\td\u0080e", "abc\td\u0080e"),
            // trim removes the six ASCII whitespace characters and nothing else.
            Triple(TextPolicy { ascii { trim() } }, " \t\u000B\u000C\r\n a b \u00A0", "a b \u00A0"),
            Triple(TextPolicy { ascii { trim() } }, "\u3000 abc ", "\u3000 abc"),
            Triple(TextPolicy { ascii { collapseSpace() } }, "a \t\n b\u3000\u3000c", "a b\u3000\u3000c"),
            Triple(TextPolicy { ascii { removeSpace() } }, "a b\tc\u00A0d", "abc\u00A0d"),
            Triple(TextPolicy { ascii { lowercase() } }, "\u00C4BC Stra\u00DFe", "\u00C4bc stra\u00DFe"),
            Triple(TextPolicy { ascii { uppercase() } }, "stra\u00DFe \u00E4", "STRA\u00DFE \u00E4"),
        )
        for ((policy, input, expected) in cases) {
            assertEquals(expected, canonical(input, policy), "FROZEN: <${policy.id}>")
        }
    }

    @Test
    fun unicodeRulesUseTheFrozenRelease() {
        val cases = listOf(
            // U+0085 is a control and also White_Space, so it is left for the space rules; U+0080 is not.
            Triple(TextPolicy(u17) { unicode { stripControl() } }, "a\u0085b\u0080c", "a\u0085bc"),
            Triple(TextPolicy(u17) { unicode { trim() } }, "\u3000\u00A0 abc\u2029\u0085", "abc"),
            Triple(TextPolicy(u17) { unicode { collapseSpace() } }, "a\u3000\u00A0\tb", "a b"),
            Triple(TextPolicy(u17) { unicode { removeSpace() } }, "a\u2003b\u00A0c", "abc"),
            Triple(TextPolicy(u17) { unicode { lowercase() } }, "\u00C4BC\u0130", "\u00E4bci"),
            // Simple mappings only: sharp s has no one-to-one uppercase and is left alone.
            Triple(TextPolicy(u17) { unicode { uppercase() } }, "\u00E4\u00DF", "\u00C4\u00DF"),
            // Full folding: sharp s and the fi ligature expand, dotted capital I becomes i + combining dot.
            Triple(TextPolicy(u17) { unicode { casefold() } }, "Stra\u00DFe \uFB01 \u0130", "strasse fi i\u0307"),
            Triple(TextPolicy(u17) { unicode { nfc() } }, "A\u030A", "\u00C5"),
            Triple(TextPolicy(u17) { unicode { nfd() } }, "\u00C5", "A\u030A"),
            Triple(TextPolicy(u17) { unicode { nfkc() } }, "\uFB01\u00B2", "fi2"),
            Triple(TextPolicy(u17) { unicode { nfkd() } }, "\u00C5\uFB01", "A\u030Afi"),
        )
        for ((policy, input, expected) in cases) {
            assertEquals(expected, canonical(input, policy), "FROZEN: <${policy.id}>")
        }
    }

    @Test
    fun theNormalizationFormRunsAfterCaseFolding() {
        // Folding J-with-caron yields j + combining caron, which is not NFC. The form runs after the fold,
        // so the output is in the form the id names: the precomposed U+01F0.
        val policy = TextPolicy.CaselessU17
        assertEquals("\u01F0", canonical("J\u030C", policy))
        assertEquals("\u01F0", canonical("\u01F0", policy))
        assertEquals("strasse", canonical("  STRASSE\u3000", policy))
        assertEquals("strasse", canonical("Stra\u00DFe", policy))
    }

    @Test
    fun rulesRunInApplicationOrderHoweverTheyWereWritten() {
        // Order matters here: trimming first would leave the space the control character was hiding.
        val written = TextPolicy { ascii { trim(); stripControl() } }
        val ordered = TextPolicy { ascii { stripControl(); trim() } }
        assertEquals("a", canonical("\u0001 a", written))
        assertEquals("a", canonical("\u0001 a", ordered))
        assertEquals("text+strip-control+trim", written.id)
    }

    @Test
    fun aMixedChainAppliesEachRuleOverItsOwnCharacterSet() {
        val policy = TextPolicy(u17) {
            unicode { trim() }
            ascii { lowercase() }
            nonEmpty()
        }
        assertEquals("text.u17+trim+lower.ascii+non-empty", policy.id)
        assertEquals("\u00C4b", canonical("\u3000\u00C4B\u00A0", policy))
    }

    @Test
    fun theConvenienceConfigurationsAreFrozen() {
        assertEquals("text+trim+lower", TextPolicy.TrimLowercase.id)
        assertEquals("user@example.com", canonical("  User@Example.COM\n", TextPolicy.TrimLowercase))
        assertEquals("text.u17+trim+casefold+nfc", TextPolicy.CaselessU17.id)
    }

    @Test
    fun nonEmptyRefusesWhatTheOtherRulesLeaveEmpty() {
        val policy = TextPolicy { ascii { trim() }; nonEmpty() }
        assertEquals("text+trim+non-empty", policy.id)
        for (input in listOf("", "   ", "\t\n")) {
            val outcome = normalizeText(input, policy)
            assertTrue(
                outcome is Outcome.Failure && outcome.exception is TextNormalizationError.Empty,
                "FROZEN: an input that trims to nothing must be refused under ${policy.id}",
            )
        }
        assertEquals("", canonical("   ", TextPolicy { ascii { trim() } }))
    }

    @Test
    fun unpairedSurrogatesAreRefusedUnderEveryConfiguration() {
        val policies = listOf(
            TextPolicy { ascii { trim() } },
            TextPolicy(u17) { unicode { casefold() } },
            TextPolicy.CaselessU17,
        )
        for (policy in policies) {
            for (broken in listOf("a" + Char(0xD800) + "b", Char(0xDC00).toString())) {
                val outcome = normalizeText(broken, policy)
                assertTrue(
                    outcome is Outcome.Failure && outcome.exception is TextNormalizationError.UnpairedSurrogate,
                    "FROZEN: a lone surrogate must be refused under ${policy.id}",
                )
            }
        }
    }
}
