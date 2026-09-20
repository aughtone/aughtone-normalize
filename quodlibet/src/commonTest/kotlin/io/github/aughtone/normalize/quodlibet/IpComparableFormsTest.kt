package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.Comparability
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.normalize.common.comparability
import io.github.aughtone.normalize.ipv4.Ipv4Policy
import io.github.aughtone.normalize.ipv4.block
import io.github.aughtone.normalize.ipv4.cidr
import io.github.aughtone.normalize.ipv4.cidrMasked
import io.github.aughtone.normalize.ipv4.normalizeIpv4
import io.github.aughtone.normalize.ipv4.normalizeIpv4Block
import io.github.aughtone.normalize.ipv4.normalizeIpv4Cidr
import io.github.aughtone.normalize.ipv6.Ipv6Policy
import io.github.aughtone.normalize.ipv6.block
import io.github.aughtone.normalize.ipv6.cidrMasked
import io.github.aughtone.normalize.ipv6.normalizeIpv6
import io.github.aughtone.normalize.ipv6.normalizeIpv6Block
import io.github.aughtone.normalize.ipv6.normalizeIpv6Cidr
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * Every comparable form an IP policy declares, pinned twice: the comparability check reports the pair
 * comparable in that form, and the two policies write identical text for the same value. A declaration
 * is a promise a caller matches on, so a pair that stops agreeing, or a form that disappears, is the bug.
 * Forms may be added; none pinned here may be removed.
 *
 * Addresses come from the documentation ranges only.
 */
class IpComparableFormsTest {

    private val resolver = QuodlibetPolicies

    private fun text(outcome: Outcome<Normalized>): String = when (outcome) {
        is Outcome.Success -> outcome.data.canonical
        is Outcome.Failure -> throw AssertionError("FROZEN: must normalize, failed with ${outcome.exception::class.simpleName}")
    }

    private fun comparability(a: String, b: String): Comparability = resolver.comparability(a, 1, b, 1).dataOrThrow()

    @Test
    fun anIpv4AddressAndItsMappedAndNat64SpellingsShareTheIpv4AddressForm() {
        val dottedQuad = Ipv4Policy.DottedQuad
        for (folding in listOf(Ipv6Policy.Rfc5952.unmap(), Ipv6Policy.Rfc5952.nat64())) {
            assertEquals(Comparability.InForm(IpForms.Ipv4Address), comparability(dottedQuad.id, folding.id), "FROZEN: ${folding.id}")
        }
        assertEquals(text(normalizeIpv4("192.0.2.5", dottedQuad)), text(normalizeIpv6("::ffff:192.0.2.5", Ipv6Policy.Rfc5952.unmap())))
        assertEquals(text(normalizeIpv4("192.0.2.5", dottedQuad)), text(normalizeIpv6("64:ff9b::192.0.2.5", Ipv6Policy.Rfc5952.nat64())))
    }

    @Test
    fun ipv6PoliciesShareTheIpv6AddressForm() {
        assertEquals(Comparability.InForm(IpForms.Ipv6Address), comparability("ipv6.rfc5952", "ipv6.rfc5952:zone.kept"))
        assertEquals(Comparability.InForm(IpForms.Ipv6Address), comparability("ipv6.rfc5952", "ipv6.rfc5952:ipv4.mapped"))
        assertEquals(text(normalizeIpv6("2001:DB8::1", Ipv6Policy.Rfc5952)), text(normalizeIpv6("2001:db8::1", Ipv6Policy.Rfc5952.unmap())))
        // The default policy keeps a mapped address as IPv6, so it is never comparable with IPv4.
        assertEquals(Comparability.NotComparable, comparability("ipv4.quad.dotted", "ipv6.rfc5952"))
    }

    @Test
    fun inetAtonIsComparableOnlyOnceACallerOptsIn() {
        assertEquals(Comparability.NotComparable, comparability("ipv4.quad.dotted", "ipv4.inet.aton"))
        assertEquals(Comparability.InForm(IpForms.Ipv4Address), comparability("ipv4.quad.dotted", "ipv4.inet.aton:form.ipv4.address"))

        val optedIn = Ipv4Policy.InetAton.withForms(setOf(IpForms.Ipv4Address))
        assertEquals("ipv4.inet.aton:form.ipv4.address", optedIn.id)
        val stored = normalizeIpv4("0300.0.2.010", optedIn)
        assertTrue(stored is Outcome.Success)
        // The opt-in changes the identity the normalizer stores, and never the bytes.
        assertEquals("ipv4.inet.aton:form.ipv4.address", stored.data.policyId)
        assertEquals(text(normalizeIpv4("0300.0.2.010", Ipv4Policy.InetAton)), stored.data.canonical)
        assertEquals(text(normalizeIpv4("192.0.2.8", Ipv4Policy.DottedQuad)), stored.data.canonical)

        val notOffered = resolver.resolve("ipv4.quad.dotted:form.ipv4.address", 1)
        assertTrue(notOffered is Outcome.Failure && notOffered.exception is PolicyIdentityError.FormNotOffered, "got $notOffered")
    }

    @Test
    fun blocksAndCidrRangesShareTheNetworkForm() {
        val block = Ipv4Policy.DottedQuad.block(24)
        for (cidr in listOf(Ipv4Policy.DottedQuad.cidr(), Ipv4Policy.DottedQuad.cidrMasked())) {
            assertEquals(Comparability.InForm(IpForms.Ipv4Network), comparability(block.id, cidr.id), "FROZEN: ${cidr.id}")
        }
        assertEquals(text(normalizeIpv4Block("198.51.100.77", block)), text(normalizeIpv4Cidr("198.51.100.0/24", Ipv4Policy.DottedQuad.cidr())))
        assertEquals(text(normalizeIpv4Block("198.51.100.77", block)), text(normalizeIpv4Cidr("198.51.100.77/24", Ipv4Policy.DottedQuad.cidrMasked())))

        val v6Block = Ipv6Policy.Rfc5952.block(48)
        assertEquals(Comparability.InForm(IpForms.Ipv6Network), comparability(v6Block.id, Ipv6Policy.Rfc5952.cidrMasked().id))
        assertEquals(text(normalizeIpv6Block("2001:db8:abcd:12::1", v6Block)), text(normalizeIpv6Cidr("2001:db8:abcd:12::1/48", Ipv6Policy.Rfc5952.cidrMasked())))

        // Blocks at different prefixes still share the form; their texts simply never collide.
        assertEquals(Comparability.InForm(IpForms.Ipv4Network), comparability(Ipv4Policy.DottedQuad.block(24).id, Ipv4Policy.DottedQuad.block(16).id))
        // An address is not a network.
        assertEquals(Comparability.NotComparable, comparability("ipv4.quad.dotted", block.id))
    }

    @Test
    fun anUnmappedBlockSharesTheIpv4NetworkForm() {
        val unmapBlock = Ipv6Policy.Rfc5952.unmap().block(24, 64)
        assertEquals(Comparability.InForm(IpForms.Ipv4Network), comparability(Ipv4Policy.DottedQuad.block(24).id, unmapBlock.id))
        assertEquals(Comparability.InForm(IpForms.Ipv6Network), comparability(Ipv6Policy.Rfc5952.block(64).id, unmapBlock.id))
        assertEquals(text(normalizeIpv4Block("192.0.2.57", Ipv4Policy.DottedQuad.block(24))), text(normalizeIpv6Block("::ffff:192.0.2.57", unmapBlock)))
    }

    @Test
    fun inetAtonNetworksAreComparableOnlyOnceACallerOptsIn() {
        val plain = Ipv4Policy.InetAton.block(24)
        val optedIn = plain.withForms(setOf(IpForms.Ipv4Network))
        assertEquals("ipv4.inet.aton:block.24:form.ipv4.network", optedIn.id)
        assertEquals(Comparability.NotComparable, comparability("ipv4.quad.dotted:block.24", plain.id))
        assertEquals(Comparability.InForm(IpForms.Ipv4Network), comparability("ipv4.quad.dotted:block.24", optedIn.id))
        val resolved = resolver.resolve(optedIn.id, 1)
        assertTrue(resolved is Outcome.Success && resolved.data == optedIn, "got $resolved")
    }
}
