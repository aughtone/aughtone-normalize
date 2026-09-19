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
 * This file pins the rules that run against Unicode 17's frozen data. A later release gets its own
 * corpus beside this one - `TextRulesByteStabilityU18Test` - and this file is never edited for it,
 * because a `text.u17` policy produces these bytes forever.
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
class TextRulesByteStabilityU17Test {

    private fun canonical(value: String, policy: TextPolicy): String =
        when (val outcome = normalizeText(value, policy)) {
            is Outcome.Success -> outcome.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN: <${policy.id}> must normalize this input, failed with ${outcome.exception::class.simpleName}",
            )
        }

    private val u17 = UnicodeRelease.U17

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
    fun aMixedChainAppliesEachRuleOverItsOwnCharacterSet() {
        val policy = TextPolicy(u17) {
            unicode { trim() }
            ascii { lowercase() }
            nonEmpty()
        }
        assertEquals("text.u17:space.trimmed:case.lower.ascii:empty.refused", policy.id)
        assertEquals("\u00C4b", canonical("\u3000\u00C4B\u00A0", policy))
    }

    @Test
    fun theUnicodeConvenienceConfigurationIsFrozen() {
        assertEquals("text.u17:space.trimmed:case.folded:nfc", TextPolicy.CaselessU17.id)
        val once = canonical("  Stra\u00DFe  ", TextPolicy.CaselessU17)
        assertEquals(once, canonical(once, TextPolicy.CaselessU17), "FROZEN: CaselessU17 is not idempotent")
    }

    @Test
    fun unpairedSurrogatesAreRefusedUnderUnicodeRules() {
        for (policy in listOf(TextPolicy(u17) { unicode { casefold() } }, TextPolicy.CaselessU17)) {
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
