package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.ipv4.Ipv4NormalizationError
import io.github.aughtone.normalize.ipv4.Ipv4Policy
import io.github.aughtone.normalize.ipv4.normalizeIpv4
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * Addresses come from the documentation ranges the RFCs reserve for examples, so nothing here points at
 * a real host.
 *
 * The pair of policies is the point of this file: the same input is refused by one and interpreted by
 * the other, and the two results must never be treated as the same claim.
 */
class Ipv4ByteStabilityTest {

    private fun canonical(value: String, policy: Ipv4Policy): String =
        when (val outcome = normalizeIpv4(value, policy)) {
            is Outcome.Success -> outcome.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN: <$value> must normalize under ${policy.id}, failed with ${outcome.exception::class.simpleName}",
            )
        }

    private inline fun <reified E : Ipv4NormalizationError> assertRefused(value: String, policy: Ipv4Policy) {
        when (val outcome = normalizeIpv4(value, policy)) {
            is Outcome.Success -> throw AssertionError("FROZEN: <$value> must be refused, produced <${outcome.data.canonical}>")
            is Outcome.Failure -> assertTrue(
                outcome.exception is E,
                "FROZEN: <$value> refused for the wrong reason (${outcome.exception::class.simpleName})",
            )
        }
    }

    @Test
    fun theUnambiguousFormIsFrozen() {
        val corpus = listOf(
            "192.0.2.1" to "192.0.2.1",
            "0.0.0.0" to "0.0.0.0",
            "255.255.255.255" to "255.255.255.255",
            "203.0.113.42" to "203.0.113.42",
            "198.51.100.7" to "198.51.100.7",
        )
        for ((input, expected) in corpus) {
            for (policy in Ipv4Policy.all) {
                assertEquals(expected, canonical(input, policy), "FROZEN: <$input> under ${policy.id}")
            }
        }
    }

    @Test
    fun theStrictPolicyRefusesEveryAmbiguousSpelling() {
        // Each of these is read differently by different stacks, so interpreting one would mint a token
        // naming a host the caller may not have meant.
        assertRefused<Ipv4NormalizationError.AmbiguousLeadingZero>("192.0.2.01", Ipv4Policy.DottedQuad)
        assertRefused<Ipv4NormalizationError.AmbiguousLeadingZero>("192.168.0.010", Ipv4Policy.DottedQuad)
        assertRefused<Ipv4NormalizationError.MalformedAddress>("0xC0.0x00.0x02.0x01", Ipv4Policy.DottedQuad)
        assertRefused<Ipv4NormalizationError.ShorthandNotSupported>("192.0.2", Ipv4Policy.DottedQuad)
        assertRefused<Ipv4NormalizationError.ShorthandNotSupported>("3221225985", Ipv4Policy.DottedQuad)
        assertRefused<Ipv4NormalizationError.PartOutOfRange>("192.0.2.256", Ipv4Policy.DottedQuad)
        assertRefused<Ipv4NormalizationError.MalformedAddress>("192.0.2.1.5", Ipv4Policy.DottedQuad)
        assertRefused<Ipv4NormalizationError.PartOutOfRange>("192.0.2.1234", Ipv4Policy.DottedQuad)
        assertRefused<Ipv4NormalizationError.MalformedAddress>("192.0..1", Ipv4Policy.DottedQuad)
        assertRefused<Ipv4NormalizationError.MalformedAddress>("192.0.2.a", Ipv4Policy.DottedQuad)
        // Not trimmed: an address is not a sentence.
        assertRefused<Ipv4NormalizationError.MalformedAddress>(" 192.0.2.1", Ipv4Policy.DottedQuad)
    }

    @Test
    fun theInterpretingPolicyAppliesTheClassicRules() {
        val corpus = listOf(
            "0xC0.0x00.0x02.0x01" to "192.0.2.1",   // hexadecimal parts
            "0300.0.2.1" to "192.0.2.1",            // octal: 0300 is 192
            "192.0.2" to "192.0.0.2",               // three parts: the last absorbs two bytes
            "192.0.513" to "192.0.2.1",             // 513 spread across the final two bytes
            "3221225985" to "192.0.2.1",            // the whole address as one integer
            "192.0.2.01" to "192.0.2.1",            // octal 01 happens to equal decimal 1
            "192.0.2.1" to "192.0.2.1",             // and the ordinary form still works
        )
        for ((input, expected) in corpus) {
            assertEquals(expected, canonical(input, Ipv4Policy.InetAton), "FROZEN: <$input>")
        }
        // Out of range even under the classic rules: a part may not exceed the bytes it occupies.
        assertRefused<Ipv4NormalizationError.PartOutOfRange>("192.0.65536", Ipv4Policy.InetAton)
        assertRefused<Ipv4NormalizationError.PartOutOfRange>("4294967296", Ipv4Policy.InetAton)
        // `0900` claims to be octal and is not.
        assertRefused<Ipv4NormalizationError.MalformedAddress>("0900.0.2.1", Ipv4Policy.InetAton)
    }

    @Test
    fun theTwoPoliciesDisagreeAboutOctalAndSaySo() {
        // The case the design turns on. Under the classic rules `010` is eight; a plain decimal reading
        // makes it ten; Go and Python refuse it. One policy refuses, the other commits - and the identity
        // stored beside a value records which happened, so the two can never be compared by accident.
        assertRefused<Ipv4NormalizationError.AmbiguousLeadingZero>("192.168.0.010", Ipv4Policy.DottedQuad)
        assertEquals("192.168.0.8", canonical("192.168.0.010", Ipv4Policy.InetAton))
        assertNotEquals(Ipv4Policy.DottedQuad.id, Ipv4Policy.InetAton.id)
    }

    @Test
    fun mixedRadixIsAcceptedByTheInterpretingPolicyBecauseThatIsTheRule() {
        // Three decimal parts and one octal one, which is exactly as odd as it looks - and exactly what
        // inet_aton does. A tidier rule would model no real parser, which would defeat the point.
        assertEquals("192.0.2.8", canonical("192.0.2.010", Ipv4Policy.InetAton))
        assertEquals("192.0.2.1", canonical("0300.0.2.1", Ipv4Policy.InetAton))
    }

    @Test
    fun normalizingIsIdempotent() {
        for (policy in Ipv4Policy.all) {
            for (input in listOf("192.0.2.1", "255.255.255.255", "0.0.0.0")) {
                val once = canonical(input, policy)
                assertEquals(once, canonical(once, policy), "FROZEN: ${policy.id} is not idempotent for <$input>")
            }
        }
    }

    @Test
    fun policyIdentitiesAreFrozen() {
        assertEquals("ipv4.dotted-quad", Ipv4Policy.DottedQuad.id)
        assertEquals("ipv4.inet-aton", Ipv4Policy.InetAton.id)
        assertEquals(1, Ipv4Policy.DottedQuad.version)
        assertEquals(1, Ipv4Policy.InetAton.version)
    }

    @Test
    fun bothPoliciesResolveFromTheirIdentities() {
        for (policy in Ipv4Policy.all) {
            val outcome = QuodlibetPolicies.resolve(policy.id, policy.version)
            assertTrue(outcome is Outcome.Success, "<${policy.id}> must resolve; add it to QuodlibetPolicies")
            assertEquals(policy.id, outcome.data.id)
        }
    }
}
