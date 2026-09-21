package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.normalize.common.Comparability
import io.github.aughtone.normalize.common.comparability
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * The domain and URL strict/lenient pairs, frozen against Unicode 17, declare one comparable form each:
 * leniency relaxes only validation, so both write identical text for a name both accept. Pinned twice: the
 * comparability check, and identical text for the same input.
 */
class LenientPairFormsU17Test {

    private fun comparability(a: String, b: String): Comparability = UbilibetPolicies.comparability(a, 1, b, 1).dataOrThrow()

    @Test
    fun theDomainPairSharesItsForm() {
        assertEquals(Comparability.InForm(DomainForms.AsciiU17), comparability("domain.ascii.u17", "domain.ascii.u17:lenient"))
        for (name in listOf("Example.COM", "caf\u00E9.example")) {
            val strict = normalizeDomain(name, DomainPolicy.AsciiU17) as Outcome.Success
            val lenient = normalizeDomain(name, DomainPolicy.AsciiU17Lenient) as Outcome.Success
            assertEquals(strict.data.canonical, lenient.data.canonical, "FROZEN: <$name>")
        }
    }

    @Test
    fun theUrlPairSharesItsForm() {
        assertEquals(
            Comparability.InForm(DomainForms.UrlRfc3986U17),
            comparability("url.rfc3986:domain.ascii.u17", "url.rfc3986:domain.ascii.u17:lenient"),
        )
        val strict = normalizeUrl("HTTPS://Example.COM/menu", UrlPolicy.Rfc3986U17) as Outcome.Success
        val lenient = normalizeUrl("HTTPS://Example.COM/menu", UrlPolicy.Rfc3986U17Lenient) as Outcome.Success
        assertEquals(strict.data.canonical, lenient.data.canonical)
    }

    @Test
    fun aDomainIsNotAUrl() {
        assertEquals(Comparability.NotComparable, comparability("domain.ascii.u17", "url.rfc3986:domain.ascii.u17"))
    }
}
