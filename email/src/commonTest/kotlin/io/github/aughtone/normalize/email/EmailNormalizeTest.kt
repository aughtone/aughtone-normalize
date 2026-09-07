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
            is Outcome.Error -> throw AssertionError("expected Success, got Error: ${o.exception::class.simpleName}")
        }

    private inline fun <reified E : EmailNormalizationError> assertError(value: String, policy: EmailPolicy) {
        when (val o = normalizeEmail(value, policy)) {
            is Outcome.Success -> throw AssertionError("expected Error, got Success: ${o.data.canonical}")
            is Outcome.Error -> assertTrue(
                o.exception is E,
                "expected ${E::class.simpleName}, got ${o.exception::class.simpleName}",
            )
        }
    }

    // --- ByteStableV1: the shared byte-level canonical form --------------------

    @Test
    fun canonicalTrimsLowercasesAndStripsSubaddress() {
        // Dots are KEPT (no provider special-casing); the RFC 5233 +subaddress is stripped.
        assertEquals("user.name@gmail.com", canonical("  User.Name+tag@Gmail.COM  ", EmailPolicy.ByteStableV1))
    }

    @Test
    fun canonicalKeepsDotsForEveryDomainIncludingGmail() {
        // No provider list: dots are significant everywhere, gmail included. We do not guess.
        assertEquals("u.s.e.r@gmail.com", canonical("U.S.E.R@Gmail.com", EmailPolicy.ByteStableV1))
        assertEquals("first.last@example.com", canonical("First.Last@Example.com", EmailPolicy.ByteStableV1))
    }

    @Test
    fun canonicalStripsSubaddressUniversally() {
        assertEquals("first.last@example.com", canonical("First.Last+news@Example.com", EmailPolicy.ByteStableV1))
        assertEquals("user@googlemail.com", canonical("user+tag@GoogleMail.com", EmailPolicy.ByteStableV1))
    }

    @Test
    fun canonicalLeavesNonAsciiUntouchedAndLowercasesOnlyAscii() {
        // Accents are preserved (byte-level, no NFC); only ASCII A-Z is lowercased.
        assertEquals("josé@example.com", canonical("José@Example.com", EmailPolicy.ByteStableV1))
        assertEquals("Ä@example.com", canonical("Ä@Example.com", EmailPolicy.ByteStableV1)) // non-ASCII case NOT folded
    }

    @Test
    fun canonicalIsIdempotent() {
        val once = canonical("  Mixed.Case+Sub@Gmail.COM ", EmailPolicy.ByteStableV1)
        assertEquals(once, canonical(once, EmailPolicy.ByteStableV1))
        assertEquals("mixed.case@gmail.com", once)
    }

    @Test
    fun sameInputAndPolicyYieldByteIdenticalOutput() {
        val a = canonical("Repeatable.Case+Sub@Gmail.com", EmailPolicy.ByteStableV1)
        val b = canonical("Repeatable.Case+Sub@Gmail.com", EmailPolicy.ByteStableV1)
        assertEquals(a, b)
    }

    // --- Lenient: trim + ASCII-lowercase only ---------------------------

    @Test
    fun lenientTrimsAndLowercasesOnly() {
        // No +subaddress strip and no dot handling for the loose policy.
        assertEquals("user+tag@gmail.com", canonical("  User+Tag@Gmail.com  ", EmailPolicy.Lenient))
        assertEquals("u.s.e.r@gmail.com", canonical("U.S.E.R@gmail.com", EmailPolicy.Lenient))
    }

    // --- explicit, value-free failures ---------------------------------------

    @Test
    fun failsMissingAtSign() = assertError<EmailNormalizationError.MissingAtSign>("not-an-email", EmailPolicy.ByteStableV1)

    @Test
    fun failsEmptyLocalPart() = assertError<EmailNormalizationError.EmptyLocalPart>("@example.com", EmailPolicy.ByteStableV1)

    @Test
    fun failsEmptyDomain() = assertError<EmailNormalizationError.EmptyDomain>("user@", EmailPolicy.ByteStableV1)

    @Test
    fun failsWhenSubaddressStripLeavesEmptyLocalPart() =
        assertError<EmailNormalizationError.EmptyLocalPart>("+tag@example.com", EmailPolicy.ByteStableV1)

    @Test
    fun failsOnUnpairedHighSurrogate() =
        assertError<EmailNormalizationError.UnpairedSurrogate>("user\uD83D@example.com", EmailPolicy.ByteStableV1)

    @Test
    fun failsOnUnpairedLowSurrogate() =
        assertError<EmailNormalizationError.UnpairedSurrogate>("user\uDE00@example.com", EmailPolicy.ByteStableV1)

    @Test
    fun wellFormedSupplementaryCharacterIsAccepted() {
        // A valid surrogate PAIR (😀 U+1F600) is well-formed and passes through untouched.
        assertEquals("a😀@example.com", canonical("a😀@example.com", EmailPolicy.ByteStableV1))
    }

    // --- policy identity + ergonomic entry points ----------------------------

    @Test
    fun policyIdentifiersAreStable() {
        assertEquals("email.byte-stable", EmailPolicy.ByteStableV1.id)
        assertEquals(1, EmailPolicy.ByteStableV1.version)
        assertEquals("email.lenient", EmailPolicy.Lenient.id)
        assertEquals(1, EmailPolicy.Lenient.version)
    }

    @Test
    fun resultCarriesPolicyIdentity() {
        val o = normalizeEmail("User@Example.com", EmailPolicy.ByteStableV1)
        assertTrue(o is Outcome.Success)
        assertEquals("email.byte-stable", o.data.policyId)
        assertEquals(1, o.data.policyVersion)
    }

    @Test
    fun orNullConvenienceReturnsCanonicalOrNull() {
        assertEquals("user@example.com", "User@Example.com".normalizeEmailOrNull(EmailPolicy.ByteStableV1))
        assertNull("not-an-email".normalizeEmailOrNull(EmailPolicy.ByteStableV1))
    }
}
