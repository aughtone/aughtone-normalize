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
 * This file pins the rules that run over ASCII. They use no Unicode data and name no release, so they
 * never change and never get a versioned sibling: a new Unicode release does not touch this file.
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
class TextAsciiRulesByteStabilityTest {

    private fun canonical(value: String, policy: TextPolicy): String =
        when (val outcome = normalizeText(value, policy)) {
            is Outcome.Success -> outcome.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN: <${policy.id}> must normalize this input, failed with ${outcome.exception::class.simpleName}",
            )
        }

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
    fun rulesRunInApplicationOrderHoweverTheyWereWritten() {
        // Order matters here: trimming first would leave the space the control character was hiding.
        val written = TextPolicy { ascii { trim(); stripControl() } }
        val ordered = TextPolicy { ascii { stripControl(); trim() } }
        assertEquals("a", canonical("\u0001 a", written))
        assertEquals("a", canonical("\u0001 a", ordered))
        assertEquals("text+strip-control+trim", written.id)
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
    fun theAsciiConvenienceConfigurationIsFrozen() {
        assertEquals("text+trim+lower", TextPolicy.TrimLowercase.id)
        assertEquals("user@example.com", canonical("  User@Example.COM\n", TextPolicy.TrimLowercase))
        val once = canonical("  MiXeD Case  ", TextPolicy.TrimLowercase)
        assertEquals(once, canonical(once, TextPolicy.TrimLowercase), "FROZEN: TrimLowercase is not idempotent")
    }

    @Test
    fun unpairedSurrogatesAreRefusedUnderAsciiRules() {
        for (policy in listOf(TextPolicy { ascii { trim() } }, TextPolicy.TrimLowercase)) {
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
