package io.github.aughtone.normalize.email

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmailNormalizeTest {

    // --- helpers -------------------------------------------------------------

    private fun canonical(value: String, policy: EmailPolicy): String =
        when (val o = normalizeEmail(value, policy)) {
            is Outcome.Success -> o.data.canonical
            is Outcome.Failure -> throw AssertionError("expected Success, got Failure: ${o.exception::class.simpleName}")
        }

    private inline fun <reified E : EmailNormalizationError> assertError(value: String, policy: EmailPolicy) {
        when (val o = normalizeEmail(value, policy)) {
            is Outcome.Success -> throw AssertionError("expected Failure, got Success: ${o.data.canonical}")
            is Outcome.Failure -> assertTrue(
                o.exception is E,
                "expected ${E::class.simpleName}, got ${o.exception::class.simpleName}",
            )
        }
    }

    // --- ByteStableV1: the shared byte-level canonical form --------------------

    @Test
    fun canonicalTrimsLowercasesAndStripsSubaddress() {
        // Dots are KEPT (no provider special-casing); the RFC 5233 +subaddress is stripped.
        assertEquals("user.name@gmail.com", canonical("  User.Name+tag@Gmail.COM  ", EmailPolicy.SubaddressRemoved))
    }

    @Test
    fun canonicalKeepsDotsForEveryDomainIncludingGmail() {
        // No provider list: dots are significant everywhere, gmail included. We do not guess.
        assertEquals("u.s.e.r@gmail.com", canonical("U.S.E.R@Gmail.com", EmailPolicy.SubaddressRemoved))
        assertEquals("first.last@example.com", canonical("First.Last@Example.com", EmailPolicy.SubaddressRemoved))
    }

    @Test
    fun canonicalStripsSubaddressUniversally() {
        assertEquals("first.last@example.com", canonical("First.Last+news@Example.com", EmailPolicy.SubaddressRemoved))
        assertEquals("user@googlemail.com", canonical("user+tag@GoogleMail.com", EmailPolicy.SubaddressRemoved))
    }

    @Test
    fun canonicalLeavesNonAsciiUntouchedAndLowercasesOnlyAscii() {
        // Accents are preserved (byte-level, no NFC); only ASCII A-Z is lowercased.
        assertEquals("josé@example.com", canonical("José@Example.com", EmailPolicy.SubaddressRemoved))
        assertEquals("Ä@example.com", canonical("Ä@Example.com", EmailPolicy.SubaddressRemoved)) // non-ASCII case NOT folded
    }

    @Test
    fun canonicalIsIdempotent() {
        val once = canonical("  Mixed.Case+Sub@Gmail.COM ", EmailPolicy.SubaddressRemoved)
        assertEquals(once, canonical(once, EmailPolicy.SubaddressRemoved))
        assertEquals("mixed.case@gmail.com", once)
    }

    @Test
    fun sameInputAndPolicyYieldByteIdenticalOutput() {
        val a = canonical("Repeatable.Case+Sub@Gmail.com", EmailPolicy.SubaddressRemoved)
        val b = canonical("Repeatable.Case+Sub@Gmail.com", EmailPolicy.SubaddressRemoved)
        assertEquals(a, b)
    }

    // --- ByteStableV1Subaddressed: trim + ASCII-lowercase only ------------

    @Test
    fun subaddressedTrimsAndLowercasesOnly() {
        // The subaddress is kept, and there is no dot handling.
        assertEquals("user+tag@gmail.com", canonical("  User+Tag@Gmail.com  ", EmailPolicy.Address))
        assertEquals("u.s.e.r@gmail.com", canonical("U.S.E.R@gmail.com", EmailPolicy.Address))
    }

    // --- explicit, value-free failures ---------------------------------------

    @Test
    fun failsMissingAtSign() = assertError<EmailNormalizationError.MissingAtSign>("not-an-email", EmailPolicy.SubaddressRemoved)

    @Test
    fun failsEmptyLocalPart() = assertError<EmailNormalizationError.EmptyLocalPart>("@example.com", EmailPolicy.SubaddressRemoved)

    @Test
    fun failsEmptyDomain() = assertError<EmailNormalizationError.EmptyDomain>("user@", EmailPolicy.SubaddressRemoved)

    @Test
    fun failsWhenSubaddressStripLeavesEmptyLocalPart() =
        assertError<EmailNormalizationError.EmptyLocalPart>("+tag@example.com", EmailPolicy.SubaddressRemoved)

    // Surrogate inputs are built from code units rather than written as literals: a lone surrogate in
    // source does not survive the JS bundler's UTF-8 round trip, so a literal quietly becomes U+FFFD and
    // the test passes on JS while exercising nothing.
    private val loneHigh = Char(0xD83D)
    private val loneLow = Char(0xDE00)

    @Test
    fun failsOnUnpairedHighSurrogate() =
        assertError<EmailNormalizationError.UnpairedSurrogate>("user" + loneHigh + "@example.com", EmailPolicy.SubaddressRemoved)

    @Test
    fun failsOnUnpairedLowSurrogate() =
        assertError<EmailNormalizationError.UnpairedSurrogate>("user" + loneLow + "@example.com", EmailPolicy.SubaddressRemoved)

    @Test
    fun wellFormedSupplementaryCharacterIsAccepted() {
        // A valid surrogate PAIR (😀 U+1F600) is well-formed and passes through untouched.
        assertEquals("a😀@example.com", canonical("a😀@example.com", EmailPolicy.SubaddressRemoved))
    }

    // --- policy identity + ergonomic entry points ----------------------------

    @Test
    fun policyIdentifiersAreStable() {
        assertEquals("email:subaddress.removed", EmailPolicy.SubaddressRemoved.id)
        assertEquals(1, EmailPolicy.SubaddressRemoved.version)
        assertEquals("email", EmailPolicy.Address.id)
        assertEquals(1, EmailPolicy.Address.version)
    }

    @Test
    fun resultCarriesPolicyIdentity() {
        val o = normalizeEmail("User@Example.com", EmailPolicy.SubaddressRemoved)
        assertTrue(o is Outcome.Success)
        assertEquals("email:subaddress.removed", o.data.policyId)
        assertEquals(1, o.data.policyVersion)
    }

    @Test
    fun orNullConvenienceReturnsCanonicalOrNull() {
        assertEquals("user@example.com", "User@Example.com".normalizeEmailOrNull(EmailPolicy.SubaddressRemoved))
        assertNull("not-an-email".normalizeEmailOrNull(EmailPolicy.SubaddressRemoved))
    }
}
