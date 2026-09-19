package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.PublishedPolicies
import io.github.aughtone.normalize.common.plus
import io.github.aughtone.normalize.ipv4.Ipv4Policy
import io.github.aughtone.normalize.ipv4.block
import io.github.aughtone.normalize.ipv4.cidrMasked
import io.github.aughtone.normalize.ipv6.Ipv6Policy
import io.github.aughtone.normalize.ipv6.block
import io.github.aughtone.normalize.ipv6.cidrMasked as ipv6CidrMasked
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** How network policies are created and resolved. The bytes are pinned in `IpNetworkByteStabilityTest`. */
class IpNetworkPolicyTest {

    @Test
    fun aPrefixOutsideTheFamilyFailsWhenThePolicyIsCreated() {
        assertFailsWith<IllegalArgumentException> { Ipv4Policy.DottedQuad.block(33) }
        assertFailsWith<IllegalArgumentException> { Ipv4Policy.DottedQuad.block(-1) }
        assertFailsWith<IllegalArgumentException> { Ipv6Policy.Rfc5952.block(129) }
        val many = io.github.aughtone.normalize.ipv4.normalizeIpv4Blocks("192.0.2.1", Ipv4Policy.DottedQuad, listOf(24, 40))
        assertTrue(many is Outcome.Failure, "one bad prefix fails the whole set rather than returning part of it")
    }

    @Test
    fun everyNetworkPolicyResolvesFromItsId() {
        val policies = listOf(0, 16, 24, 32).map { Ipv4Policy.DottedQuad.block(it) } +
            listOf(Ipv4Policy.InetAton.block(24), Ipv4Policy.DottedQuad.cidrMasked()) +
            listOf(0, 48, 64, 128).map { Ipv6Policy.Rfc5952.block(it) }
        val otherModule = object : PublishedPolicies() {
            override val policies: List<Policy> = emptyList()
            override val links: List<PolicyLink> = emptyList()
        }
        for (policy in policies) {
            for (resolver in listOf(QuodlibetPolicies, otherModule + QuodlibetPolicies)) {
                val outcome = resolver.resolve(policy.id, policy.version)
                assertTrue(outcome is Outcome.Success, "<${policy.id}> must resolve")
                assertEquals(policy, outcome.data)
            }
        }
    }

    @Test
    fun anyOtherSpellingIsRefused() {
        val reordered = QuodlibetPolicies.resolve("ipv4.quad.dotted:host.zeroed:cidr", 1)
        assertTrue(reordered is Outcome.Failure && reordered.exception is PolicyIdentityError, "got $reordered")
        val padded = QuodlibetPolicies.resolve("ipv4.quad.dotted:block.024", 1)
        assertTrue(padded is Outcome.Failure && padded.exception is PolicyIdentityError.UnknownLink, "got $padded")
        val tooLong = QuodlibetPolicies.resolve("ipv4.quad.dotted:block.33", 1)
        assertTrue(tooLong is Outcome.Failure && tooLong.exception is PolicyIdentityError, "got $tooLong")
        val v6TooLong = QuodlibetPolicies.resolve("ipv6.rfc5952:block.129", 1)
        assertTrue(v6TooLong is Outcome.Failure && v6TooLong.exception is PolicyIdentityError.UnknownLink, "got $v6TooLong")
    }

    @Test
    fun aBlockTakesThePrefixesItsModesNeed() {
        assertFailsWith<IllegalArgumentException> { Ipv6Policy.Rfc5952.unmap().block(64) }
        assertFailsWith<IllegalArgumentException> { Ipv6Policy.Rfc5952.block(24, 64) }
        assertFailsWith<IllegalArgumentException> { Ipv6Policy.Rfc5952.nat64().block(33, 64) }
    }

    @Test
    fun modePoliciesResolveAndOtherSpellingsAreRefused() {
        val policies = listOf(
            Ipv6Policy.Rfc5952.unmap(),
            Ipv6Policy.Rfc5952.unmap().nat64().zone(),
            Ipv6Policy.Rfc5952.unmap().block(24, 64),
            Ipv6Policy.Rfc5952.nat64().zone().ipv6CidrMasked(),
            Ipv6Policy.Rfc5952.zone().block(64),
        )
        for (policy in policies) {
            val outcome = QuodlibetPolicies.resolve(policy.id, 1)
            assertTrue(outcome is Outcome.Success, "<${policy.id}> must resolve")
            assertEquals(policy, outcome.data)
        }
        for (id in listOf(
            "ipv6.rfc5952:zone.kept:ipv4.mapped",
            "ipv6.rfc5952:ipv4.mapped:block.64",
            "ipv6.rfc5952:block.v4.24:block.v6.64",
            "ipv6.rfc5952:ipv4.mapped:block.v6.64:block.v4.24",
            "ipv6.rfc5952:ipv4.mapped:block.v4.33:block.v6.64",
        )) {
            val outcome = QuodlibetPolicies.resolve(id, 1)
            assertTrue(outcome is Outcome.Failure && outcome.exception is PolicyIdentityError, "<$id> must be refused, got $outcome")
        }
    }
}
