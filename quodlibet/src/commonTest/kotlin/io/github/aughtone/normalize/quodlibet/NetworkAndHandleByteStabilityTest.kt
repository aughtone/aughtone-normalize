package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.ipv6.Ipv6NormalizationError
import io.github.aughtone.normalize.ipv6.Ipv6Policy
import io.github.aughtone.normalize.ipv6.normalizeIpv6
import io.github.aughtone.normalize.username.UsernameNormalizationError
import io.github.aughtone.normalize.username.UsernamePolicy
import io.github.aughtone.normalize.username.normalizeUsername
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * Addresses use the documentation ranges (`2001:db8::/32`, `192.0.2.0/24`) reserved by the RFCs for
 * exactly this, so nothing here points at a real host.
 *
 * If a test fails, the canonical form changed and every token derived under that policy is orphaned.
 * Mint a new policy version rather than editing an expectation.
 */
class NetworkAndHandleByteStabilityTest {

    @Test
    fun ipv6CanonicalFormIsFrozen() {
        val corpus = listOf(
            // lowercase hex and leading zeros suppressed
            "2001:0DB8:0000:0000:0000:0000:0000:0001" to "2001:db8::1",
            "2001:db8::1" to "2001:db8::1",
            "2001:DB8:0:0:1:0:0:1" to "2001:db8::1:0:0:1",
            // the leftmost of two equal-length runs wins
            "2001:0:0:1:0:0:0:1" to "2001:0:0:1::1",
            // a single zero field is written out rather than compressed
            "2001:db8:0:1:1:1:1:1" to "2001:db8:0:1:1:1:1:1",
            // the special addresses
            "0:0:0:0:0:0:0:0" to "::",
            "::" to "::",
            "0:0:0:0:0:0:0:1" to "::1",
            // an IPv4-mapped address keeps its dotted-quad tail
            "::ffff:192.0.2.1" to "::ffff:192.0.2.1",
            "0:0:0:0:0:ffff:c000:0201" to "::ffff:192.0.2.1",
        )
        for ((input, expected) in corpus) {
            val outcome = normalizeIpv6(input, Ipv6Policy.Rfc5952)
            assertTrue(
                outcome is Outcome.Success,
                "FROZEN: <$input> must normalize, failed with ${(outcome as? Outcome.Failure)?.exception?.let { it::class.simpleName }}",
            )
            assertEquals(expected, outcome.data.canonical, "FROZEN: <$input>")
        }
    }

    @Test
    fun ipv6RefusalsAreFrozen() {
        // Each of these would need a guess to accept, and the guess would change which address the
        // value refers to.
        assertRefusedIpv6<Ipv6NormalizationError.ZoneIdentifier>("fe80::1%eth0")
        assertRefusedIpv6<Ipv6NormalizationError.Bracketed>("[2001:db8::1]")
        assertRefusedIpv6<Ipv6NormalizationError.PrefixLength>("2001:db8::/32")
        assertRefusedIpv6<Ipv6NormalizationError.NotIpv6>("192.0.2.1")
        assertRefusedIpv6<Ipv6NormalizationError.MalformedAddress>("2001:db8::1::2")
        assertRefusedIpv6<Ipv6NormalizationError.MalformedAddress>("2001:db8:0:0:0:0:0:0:1")
        assertRefusedIpv6<Ipv6NormalizationError.MalformedAddress>("2001:db8::g")
        assertRefusedIpv6<Ipv6NormalizationError.MalformedAddress>("::ffff:192.0.2.256")
        // A leading zero in a dotted quad has meant octal in enough software to be a security problem.
        assertRefusedIpv6<Ipv6NormalizationError.MalformedAddress>("::ffff:192.0.2.01")
    }

    @Test
    fun ipv6NormalizingIsIdempotent() {
        for (input in listOf("2001:0DB8::0001", "::ffff:192.0.2.1", "0:0:0:0:0:0:0:0")) {
            val once = (normalizeIpv6(input, Ipv6Policy.Rfc5952) as Outcome.Success).data.canonical
            val twice = (normalizeIpv6(once, Ipv6Policy.Rfc5952) as Outcome.Success).data.canonical
            assertEquals(once, twice, "FROZEN: not idempotent for <$input>")
        }
    }

    @Test
    fun usernameCanonicalFormIsFrozen() {
        val corpus = listOf(
            "Alice" to "alice",
            "  Alice  " to "alice",
            "a.l.i.c.e" to "a.l.i.c.e",     // dots are significant: no provider rules here
            "@alice" to "@alice",           // the sigil is an ordinary character
            "alice_01-x" to "alice_01-x",
            "Ärger" to "Ärger",             // non-ASCII case is NOT folded, exactly as email does
            "ПРИМЕР" to "ПРИМЕР",
            "a".repeat(300) to "a".repeat(300), // no length cap
        )
        for ((input, expected) in corpus) {
            val outcome = normalizeUsername(input, UsernamePolicy.Basic)
            assertTrue(outcome is Outcome.Success, "FROZEN: <$input> must normalize")
            assertEquals(expected, outcome.data.canonical, "FROZEN: <$input>")
        }
    }

    @Test
    fun usernameRefusalsAreFrozen() {
        for (empty in listOf("", "   ", "\t\n")) {
            val outcome = normalizeUsername(empty, UsernamePolicy.Basic)
            assertTrue(
                outcome is Outcome.Failure && outcome.exception is UsernameNormalizationError.Empty,
                "FROZEN: an empty handle must be refused",
            )
        }
        val broken = "a" + Char(0xD800) + "b"
        val outcome = normalizeUsername(broken, UsernamePolicy.Basic)
        assertTrue(
            outcome is Outcome.Failure && outcome.exception is UsernameNormalizationError.UnpairedSurrogate,
            "FROZEN: an unpaired surrogate must be refused",
        )
    }

    @Test
    fun policyIdentitiesAreFrozen() {
        assertEquals("ipv6.rfc5952", Ipv6Policy.Rfc5952.id)
        assertEquals("username.basic", UsernamePolicy.Basic.id)
        assertEquals(1, Ipv6Policy.Rfc5952.version)
        assertEquals(1, UsernamePolicy.Basic.version)
    }

    private inline fun <reified E : Ipv6NormalizationError> assertRefusedIpv6(value: String) {
        when (val outcome = normalizeIpv6(value, Ipv6Policy.Rfc5952)) {
            is Outcome.Success -> throw AssertionError("FROZEN: <$value> must be refused, produced <${outcome.data.canonical}>")
            is Outcome.Failure -> assertTrue(
                outcome.exception is E,
                "FROZEN: <$value> refused for the wrong reason (${outcome.exception::class.simpleName})",
            )
        }
    }
}
