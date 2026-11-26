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
 * The keep-set and mark-removal rules, against Unicode 17's frozen data. A later release gets its own
 * corpus beside this one - `TextKeepSetByteStabilityU18Test` - and this file is never edited for it.
 *
 * These rules are what a caller deriving text from a title reaches for, so what they produce is hashed
 * and the input discarded. A byte that moves makes every token already derived under that configuration
 * unmatchable, silently.
 *
 * Characters are written as escapes so a reviewer can see exactly which code points are pinned, and so
 * no editor or bundler can quietly substitute one. Supplementary-plane characters are written as their
 * surrogate pairs for the same reason, with the code point named in a comment.
 *
 * ## Changes that ARE allowed
 *
 * Adding cases. Deleting or editing an existing expectation is not.
 */
class TextKeepSetByteStabilityU17Test {

    private fun canonical(value: String, policy: TextPolicy): String =
        when (val outcome = normalizeText(value, policy)) {
            is Outcome.Success -> outcome.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN: <${policy.id}> must normalize this input, failed with ${outcome.exception::class.simpleName}",
            )
        }

    private val u17 = UnicodeRelease.U17

    /** Keep letters, digits and marks; delete everything else. */
    private val keep = TextPolicy(u17) { unicode { keepAlnum() } }

    /** The same, standing a single space where a run of anything else was. */
    private val keepSpaced = TextPolicy(u17) { unicode { keepAlnumSpaced() } }

    /** Decompose, drop the marks that exposed, then keep what is left - the fold. */
    private val folded = TextPolicy(u17) { unicode { nfd(); removeMarks(); keepAlnum() } }

    /** The same fold with an ASCII keep-set, which is where the cliff is. */
    private val foldedAscii = TextPolicy(u17) {
        unicode { nfd(); removeMarks() }
        ascii { keepAlnum() }
    }

    @Test
    fun theIdsAreFrozen() {
        assertEquals("text.u17:alnum.only", keep.id)
        assertEquals("text.u17:alnum.only.spaced", keepSpaced.id)
        assertEquals("text.u17:nfd:mark.removed:alnum.only", folded.id)
        assertEquals("text.u17:nfd:mark.removed:alnum.only.ascii", foldedAscii.id)
        assertEquals("text:alnum.only", TextPolicy { ascii { keepAlnum() } }.id)
        assertEquals("text:alnum.only.spaced", TextPolicy { ascii { keepAlnumSpaced() } }.id)
    }

    @Test
    fun theNamedConfigurationsAreExactlyWhatTheyWere() {
        // The new rules sit between the normalization form and the non-empty check, so nothing that was
        // already published moved: every id here is byte for byte what it was before
        // aughtone/aughtone-normalize#43, and a stored token keyed under one still resolves to it.
        assertEquals(
            listOf(
                "text.u17:nfc",
                "text.u17:nfd",
                "text.u17:nfkc",
                "text.u17:nfkd",
                "text:space.trimmed:case.lower",
                "text.u17:space.trimmed:case.folded:nfc",
            ),
            TextPolicy.all.map { it.id },
            "FROZEN: a published policy's id changed",
        )
    }

    @Test
    fun theKeepSetCoversTheSupplementaryPlanes() {
        // The whole point of aughtone/aughtone-normalize#44: these are declared in UnicodeData.txt as
        // First>/Last> range pairs, and a table built by skipping those reports every one of them as a
        // non-letter. U+20000 CJK Extension B, U+17000 Tangut, U+AC00 Hangul syllable, U+1E900 Adlam.
        val input = "\uD840\uDC00!\uD81C\uDC00?\uAC00,\uD83A\uDD00"
        assertEquals("\uD840\uDC00\uD81C\uDC00\uAC00\uD83A\uDD00", canonical(input, keep), "FROZEN: <${keep.id}>")
    }

    @Test
    fun aDecimalDigitOfAnyScriptIsKeptAndOtherNumbersAreNot() {
        // Nd only. U+0664 is an Arabic-Indic decimal digit; U+00BD (one half) is No and U+2160 (Roman
        // numeral one) is Nl, and neither is a digit a caller can do anything with.
        assertEquals("a\u0664b", canonical("a\u0664-b", keep), "FROZEN: <${keep.id}>")
        assertEquals("ab", canonical("a\u00BD\u2160b", keep), "FROZEN: <${keep.id}>")
    }

    @Test
    fun precomposedAndDecomposedTextProduceTheSameBytes() {
        // The defect the consumer reported. U+AC01 is a Hangul syllable; its canonical decomposition is
        // the three jamo U+1100 U+1161 U+11A8, which are letters in their own right and so survive the
        // keep-set. Without a normalization form in the policy the two spellings would key differently.
        val composing = TextPolicy(u17) { unicode { nfc(); keepAlnum() } }
        assertEquals("text.u17:nfc:alnum.only", composing.id)
        assertEquals("\uAC01", canonical("\uAC01", composing), "FROZEN: <${composing.id}>")
        assertEquals("\uAC01", canonical("\u1100\u1161\u11A8", composing), "FROZEN: <${composing.id}>")
        assertEquals(
            canonical("\uAC01!", composing),
            canonical("\u1100\u1161\u11A8!", composing),
            "FROZEN: the two spellings of one syllable must key identically",
        )
    }

    @Test
    fun aMarkIsKeptByTheKeepSetAndRemovedOnlyWhenAskedFor() {
        // A combining mark belongs to the letter in front of it, so the keep-set keeps it: dropping it
        // here would rewrite the word without the policy saying so.
        assertEquals("e\u0301", canonical("e\u0301!", keep), "FROZEN: <${keep.id}>")
        assertEquals("e", canonical("\u00E9!", folded), "FROZEN: <${folded.id}>")
        assertEquals("e", canonical("e\u0301!", folded), "FROZEN: <${folded.id}>")
    }

    @Test
    fun theFoldIsFoldingAndNotTransliteration() {
        // These five have no canonical decomposition, so there is no mark to remove and no base letter
        // to fold to. Under a Unicode keep-set they survive; under an ASCII one they are dropped. What
        // never happens is a mapping to `o`, `d`, `l`, `ae` or `th` - that is transliteration, which is
        // language-specific and editorial, and a frozen policy cannot promise it. See
        // aughtone/aughtone-normalize#43.
        assertEquals("J\u00F8rn", canonical("J\u00F8rn", folded), "FROZEN: <${folded.id}>")
        assertEquals("Jrn", canonical("J\u00F8rn", foldedAscii), "FROZEN: <${foldedAscii.id}>")
        assertEquals("an", canonical("\u0111an", foldedAscii), "FROZEN: <${foldedAscii.id}>")
        assertEquals("odz", canonical("\u0141\u00F3d\u017A", foldedAscii), "FROZEN: <${foldedAscii.id}>")
        assertEquals("r", canonical("\u00C6r\u00F8", foldedAscii), "FROZEN: <${foldedAscii.id}>")
        assertEquals("ing", canonical("\u00FEing", foldedAscii), "FROZEN: <${foldedAscii.id}>")
        // The diacritics that DO decompose fold as usual, in the same input.
        assertEquals("Grusse", canonical("Gr\u00FCsse", foldedAscii), "FROZEN: <${foldedAscii.id}>")
    }

    @Test
    fun anAsciiKeepSetDropsEveryNonAsciiLetterIncludingWholeScripts() {
        // The cliff, stated as a test rather than only as a warning: an ASCII keep-set empties text that
        // is not written in the Latin alphabet. U+4E2D U+6587 is Chinese; U+0416 is Cyrillic Zhe.
        assertEquals("", canonical("\u4E2D\u6587", foldedAscii), "FROZEN: <${foldedAscii.id}>")
        assertEquals("", canonical("\u0416", foldedAscii), "FROZEN: <${foldedAscii.id}>")
        assertEquals("\u4E2D\u6587", canonical("\u4E2D\u6587", folded), "FROZEN: <${folded.id}>")
    }

    @Test
    fun theSpacingVariantKeepsTheWordBoundaryThatWasInThePunctuation() {
        assertEquals("Hello World", canonical("Hello, World!", keepSpaced), "FROZEN: <${keepSpaced.id}>")
        assertEquals("HelloWorld", canonical("Hello, World!", keep), "FROZEN: <${keep.id}>")
        // A run of any length is one space, and a leading or trailing run leaves none: the trim and
        // collapse rules have already run by this point in the order, so this rule does its own.
        assertEquals("Hello World", canonical("  ...Hello...   World...  ", keepSpaced), "FROZEN: <${keepSpaced.id}>")
        assertEquals("a b", canonical("a\u00A0\u2014\tb", keepSpaced), "FROZEN: <${keepSpaced.id}>")
    }

    @Test
    fun aFormatCharacterOrAnUnassignedCodePointIsDeletedAndNeverSpaced() {
        // Substituting a space for something invisible invents a word boundary nobody typed. U+200D is a
        // zero-width joiner, which sits inside a word by design; U+FFFE is assigned to nothing at all.
        assertEquals("ab", canonical("a\u200Db", keepSpaced), "FROZEN: <${keepSpaced.id}>")
        assertEquals("ab", canonical("a\uFFFEb", keepSpaced), "FROZEN: <${keepSpaced.id}>")
        // It does not end a run either: the two commas are one run with nothing between them.
        assertEquals("a b", canonical("a,\u200D,b", keepSpaced), "FROZEN: <${keepSpaced.id}>")
        // A private-use character is assigned and visible, so it is spaced like any other non-letter.
        assertEquals("a b", canonical("a\uE000b", keepSpaced), "FROZEN: <${keepSpaced.id}>")
    }

    @Test
    fun anEmptyResultIsEmptyUnlessNonEmptyIsEnabled() {
        assertEquals("", canonical("!!!", keep), "FROZEN: <${keep.id}>")
        assertEquals("", canonical("...", keepSpaced), "FROZEN: <${keepSpaced.id}>")
        val refusing = TextPolicy(u17) { unicode { keepAlnum() }; nonEmpty() }
        assertEquals("text.u17:alnum.only:empty.refused", refusing.id)
        val outcome = normalizeText("!!!", refusing)
        assertTrue(
            outcome is Outcome.Failure && outcome.exception is TextNormalizationError.Empty,
            "FROZEN: an input that keeps nothing must be refused under ${refusing.id}",
        )
    }

    @Test
    fun theNewRulesRunAfterTheNormalizationFormHoweverTheyWereWritten() {
        // Written back to front: the fold only works decompose-then-strip-then-filter, and the fixed
        // order is what guarantees it rather than the order the caller happened to write.
        val written = TextPolicy(u17) {
            unicode { keepAlnum(); removeMarks(); nfd() }
        }
        assertEquals("text.u17:nfd:mark.removed:alnum.only", written.id)
        assertEquals("e", canonical("\u00E9!", written))
    }

    @Test
    fun theTwoKeepSetRulesAreAlternatives() {
        // Same rank, so a policy chooses one: deleting and spacing cannot both happen.
        val error = kotlin.runCatching { TextPolicy(u17) { unicode { keepAlnum(); keepAlnumSpaced() } } }.exceptionOrNull()
        assertTrue(error is TextPolicyError.ContradictoryRules, "two keep-set rules must contradict, got $error")
    }

    @Test
    fun unpairedSurrogatesAreRefusedUnderTheNewRules() {
        for (policy in listOf(keep, keepSpaced, folded)) {
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
