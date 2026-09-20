package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.Comparability
import io.github.aughtone.normalize.common.comparability
import io.github.aughtone.normalize.email.EmailPolicy
import io.github.aughtone.normalize.email.normalizeEmail
import io.github.aughtone.normalize.iban.IbanForms
import io.github.aughtone.normalize.iban.IbanPolicy
import io.github.aughtone.normalize.iban.normalizeIban
import io.github.aughtone.normalize.pan.PanForms
import io.github.aughtone.normalize.pan.PanPolicy
import io.github.aughtone.normalize.pan.normalizePan
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * Strict/lenient pairs whose leniency only widens what is accepted declare one comparable form, pinned here
 * twice: the comparability check reports them comparable, and both write identical text for input both
 * accept. Email has no lenient policy: its subaddress-keeping policy, `email.byte-stable:subaddressed`,
 * changes meaning rather than acceptance, and is pinned here as NOT comparable with the strict one.
 *
 * Numbers are the test values issuers and registries publish for documentation.
 */
class LenientPairFormsTest {

    private fun comparability(a: String, b: String): Comparability = QuodlibetPolicies.comparability(a, 1, b, 1).dataOrThrow()

    @Test
    fun theCardNumberPairSharesItsForm() {
        assertEquals(Comparability.InForm(PanForms.Digits), comparability("pan.digits", "pan.digits:lenient"))
        val strict = normalizePan("4111 1111 1111 1111", PanPolicy.Digits) as Outcome.Success
        val lenient = normalizePan("4111 1111 1111 1111", PanPolicy.DigitsLenient) as Outcome.Success
        assertEquals(strict.data.canonical, lenient.data.canonical)
    }

    @Test
    fun theIbanPairSharesItsForm() {
        assertEquals(Comparability.InForm(IbanForms.Compact), comparability("iban.compact", "iban.compact:lenient"))
        val strict = normalizeIban("GB82 WEST 1234 5698 7654 32", IbanPolicy.Compact) as Outcome.Success
        val lenient = normalizeIban("GB82 WEST 1234 5698 7654 32", IbanPolicy.CompactLenient) as Outcome.Success
        assertEquals(strict.data.canonical, lenient.data.canonical)
    }

    @Test
    fun theSubaddressRemovingEmailPolicyIsNotComparableWithTheAnchor() {
        assertEquals(Comparability.NotComparable, comparability("email", "email:subaddress.removed"))
        val strict = normalizeEmail("user+tag@example.com", EmailPolicy.SubaddressRemoved) as Outcome.Success
        val subaddressed = normalizeEmail("user+tag@example.com", EmailPolicy.Address) as Outcome.Success
        assertNotEquals(strict.data.canonical, subaddressed.data.canonical)
    }
}
