package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.iban.IbanNormalizationError
import io.github.aughtone.normalize.iban.IbanPolicy
import io.github.aughtone.normalize.iban.normalizeIban
import io.github.aughtone.normalize.pan.PanNormalizationError
import io.github.aughtone.normalize.pan.PanPolicy
import io.github.aughtone.normalize.pan.normalizePan
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * Every value below is a published test number - the card numbers schemes document for testing, and the
 * example IBANs from the standard's own registry. **No real card or account number belongs in this
 * file**, now or ever: it is a public repository, and a test fixture is not a safe place for regulated
 * data even briefly.
 *
 * If a test here fails, the canonical form changed, and every token already derived under that policy
 * is now unmatchable. Mint a new policy version instead of editing an expectation.
 */
class FinancialByteStabilityTest {

    @Test
    fun panCanonicalFormIsFrozen() {
        val corpus = listOf(
            "4111111111111111" to "4111111111111111",
            "4111 1111 1111 1111" to "4111111111111111",
            "4111-1111-1111-1111" to "4111111111111111",
            " 4111 1111-1111 1111 " to "4111111111111111",
            "5555555555554444" to "5555555555554444",
            "378282246310005" to "378282246310005",
        )
        for ((input, expected) in corpus) {
            val outcome = normalizePan(input, PanPolicy.Digits)
            assertTrue(outcome is Outcome.Success, "FROZEN: <$input> must normalize")
            assertEquals(expected, outcome.data.canonical, "FROZEN: <$input>")
        }
    }

    @Test
    fun panRefusalsAreFrozen() {
        // A digit changed, so the Luhn check fails: almost always a typo, and tokenizing a typo puts a
        // row in the caller's store that can never match anything again.
        val wrongCheckDigit = "4111111111111112"
        assertRefusedPan<PanNormalizationError.ChecksumFailed>(wrongCheckDigit, PanPolicy.Digits)
        assertRefusedPan<PanNormalizationError.WrongLength>("4111", PanPolicy.Digits)
        assertRefusedPan<PanNormalizationError.WrongLength>("4".repeat(20), PanPolicy.Digits)
        assertRefusedPan<PanNormalizationError.UnexpectedCharacter>("4111a111111111111", PanPolicy.Digits)
        // Non-ASCII digits are refused rather than converted: this module carries no table, and a
        // guessed conversion would be worse than a refusal.
        assertRefusedPan<PanNormalizationError.UnexpectedCharacter>("４111111111111111", PanPolicy.Digits)

        // The lenient policy relaxes the checksum and nothing else.
        val lenient = normalizePan(wrongCheckDigit, PanPolicy.DigitsLenient)
        assertTrue(lenient is Outcome.Success)
        assertEquals(wrongCheckDigit, lenient.data.canonical)
        assertRefusedPan<PanNormalizationError.UnexpectedCharacter>("4111a111111111111", PanPolicy.DigitsLenient)
    }

    @Test
    fun ibanCanonicalFormIsFrozen() {
        val corpus = listOf(
            "GB82 WEST 1234 5698 7654 32" to "GB82WEST12345698765432",
            "GB82WEST12345698765432" to "GB82WEST12345698765432",
            "gb82 west 1234 5698 7654 32" to "GB82WEST12345698765432",
            "DE89 3704 0044 0532 0130 00" to "DE89370400440532013000",
            "FR14 2004 1010 0505 0001 3M02 606" to "FR1420041010050500013M02606",
        )
        for ((input, expected) in corpus) {
            val outcome = normalizeIban(input, IbanPolicy.Compact)
            assertTrue(outcome is Outcome.Success, "FROZEN: <$input> must normalize")
            assertEquals(expected, outcome.data.canonical, "FROZEN: <$input>")
        }
    }

    @Test
    fun ibanRefusalsAreFrozen() {
        val wrongCheckDigits = "GB83WEST12345698765432"
        assertRefusedIban<IbanNormalizationError.ChecksumFailed>(wrongCheckDigits, IbanPolicy.Compact)
        assertRefusedIban<IbanNormalizationError.MalformedStructure>("1B82WEST12345698765432", IbanPolicy.Compact)
        assertRefusedIban<IbanNormalizationError.WrongLength>("GB82", IbanPolicy.Compact)
        assertRefusedIban<IbanNormalizationError.WrongLength>("GB82" + "W".repeat(31), IbanPolicy.Compact)
        assertRefusedIban<IbanNormalizationError.UnexpectedCharacter>("GB82-WEST-1234", IbanPolicy.Compact)

        val lenient = normalizeIban(wrongCheckDigits, IbanPolicy.CompactLenient)
        assertTrue(lenient is Outcome.Success)
        assertEquals(wrongCheckDigits, lenient.data.canonical)
    }

    @Test
    fun noErrorMessageCarriesAnyPartOfTheInput() {
        // The discipline this module exists to demonstrate. A regulated value must not reach a log
        // through an exception message, so the messages are checked rather than assumed.
        val pan = "4111a111111111111"
        val panOutcome = normalizePan(pan, PanPolicy.Digits)
        assertTrue(panOutcome is Outcome.Failure)
        val panMessage = panOutcome.exception.message.orEmpty()
        // The whole value, and any run of it long enough to identify the card, must be absent. A single
        // letter is not a leak - "character" contains an `a` - so the check is about the input, not
        // about individual characters appearing anywhere in English prose.
        assertTrue(pan !in panMessage, "PAN error leaked the value: $panMessage")
        assertTrue("4111" !in panMessage, "PAN error leaked a digit run: $panMessage")

        val iban = "GB82-WEST-1234"
        val ibanOutcome = normalizeIban(iban, IbanPolicy.Compact)
        assertTrue(ibanOutcome is Outcome.Failure)
        val ibanMessage = ibanOutcome.exception.message.orEmpty()
        assertTrue(iban !in ibanMessage, "IBAN error leaked the value: $ibanMessage")
        assertTrue("GB82" !in ibanMessage && "WEST" !in ibanMessage, "IBAN error leaked part of it: $ibanMessage")
    }

    @Test
    fun policyIdentitiesAreFrozen() {
        assertEquals("pan.digits", PanPolicy.Digits.id)
        assertEquals("pan.digits+lenient", PanPolicy.DigitsLenient.id)
        assertEquals("iban.compact", IbanPolicy.Compact.id)
        assertEquals("iban.compact+lenient", IbanPolicy.CompactLenient.id)
    }

    private inline fun <reified E : PanNormalizationError> assertRefusedPan(value: String, policy: PanPolicy) {
        when (val outcome = normalizePan(value, policy)) {
            is Outcome.Success -> throw AssertionError("FROZEN: must be refused, produced <${outcome.data.canonical}>")
            is Outcome.Failure -> assertTrue(
                outcome.exception is E,
                "FROZEN: refused for the wrong reason (${outcome.exception::class.simpleName})",
            )
        }
    }

    private inline fun <reified E : IbanNormalizationError> assertRefusedIban(value: String, policy: IbanPolicy) {
        when (val outcome = normalizeIban(value, policy)) {
            is Outcome.Success -> throw AssertionError("FROZEN: must be refused, produced <${outcome.data.canonical}>")
            is Outcome.Failure -> assertTrue(
                outcome.exception is E,
                "FROZEN: refused for the wrong reason (${outcome.exception::class.simpleName})",
            )
        }
    }
}
