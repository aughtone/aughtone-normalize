package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.ipv4.Ipv4NormalizationError
import io.github.aughtone.normalize.ipv4.Ipv4Policy
import io.github.aughtone.normalize.ipv4.block
import io.github.aughtone.normalize.ipv4.cidr
import io.github.aughtone.normalize.ipv4.cidrMasked
import io.github.aughtone.normalize.ipv4.normalizeIpv4
import io.github.aughtone.normalize.ipv4.normalizeIpv4Block
import io.github.aughtone.normalize.ipv4.normalizeIpv4Blocks
import io.github.aughtone.normalize.ipv4.normalizeIpv4Cidr
import io.github.aughtone.normalize.ipv6.Ipv6NormalizationError
import io.github.aughtone.normalize.ipv6.Ipv6Policy
import io.github.aughtone.normalize.ipv6.block
import io.github.aughtone.normalize.ipv6.cidr
import io.github.aughtone.normalize.ipv6.cidrMasked
import io.github.aughtone.normalize.ipv6.normalizeIpv6
import io.github.aughtone.normalize.ipv6.normalizeIpv6Block
import io.github.aughtone.normalize.ipv6.normalizeIpv6Blocks
import io.github.aughtone.normalize.ipv6.normalizeIpv6Cidr
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * ## If a test here fails, a network's canonical form changed. That is the bug.
 *
 * Callers key subnet buckets and blocklist ranges on these strings and discard the addresses, so a byte
 * that moves makes every bucket already derived unmatchable. The ids pinned here are stored beside them.
 *
 * Every address comes from the ranges reserved for documentation - `192.0.2.0/24`, `198.51.100.0/24`,
 * `203.0.113.0/24`, `2001:db8::/32` - so nothing here names a real network. None of these rules uses
 * Unicode data, so this corpus is unversioned.
 *
 * ## Changes that ARE allowed
 *
 * Adding cases. Deleting or editing an existing expectation is not.
 */
class IpNetworkByteStabilityTest {

    private fun <T : Normalized> canonical(outcome: Outcome<T>, label: String): String = when (outcome) {
        is Outcome.Success -> outcome.data.canonical
        is Outcome.Failure -> throw AssertionError("FROZEN: <$label> must normalize, failed with ${outcome.exception::class.simpleName}")
    }

    private inline fun <reified E : Throwable> assertRefused(outcome: Outcome<*>, label: String) {
        assertTrue(
            outcome is Outcome.Failure && outcome.exception is E,
            "FROZEN: <$label> must be refused with ${E::class.simpleName}, got $outcome",
        )
    }

    @Test
    fun ipv4BlocksAreFrozen() {
        val dottedQuad = Ipv4Policy.DottedQuad
        val cases = listOf(
            Triple("192.0.2.57", 24, "192.0.2.0/24"),
            Triple("192.0.2.57", 16, "192.0.0.0/16"),
            Triple("192.0.2.57", 32, "192.0.2.57/32"),
            Triple("192.0.2.57", 0, "0.0.0.0/0"),
            Triple("203.0.113.200", 25, "203.0.113.128/25"),
            Triple("198.51.100.77", 23, "198.51.100.0/23"),
        )
        for ((address, prefix, expected) in cases) {
            assertEquals(expected, canonical(normalizeIpv4Block(address, dottedQuad.block(prefix)), "$address /$prefix"))
        }
    }

    @Test
    fun ipv4BlocksReadTheAddressUnderTheirOwnRules() {
        // The address rules are in the id because they change what is read.
        assertEquals("192.0.2.0/24", canonical(normalizeIpv4Block("0300.0.2.010", Ipv4Policy.InetAton.block(24)), "inet-aton"))
        assertEquals("192.0.2.0/24", canonical(normalizeIpv4Block("192.0.513", Ipv4Policy.InetAton.block(24)), "inet-aton shorthand"))
        assertRefused<Ipv4NormalizationError.AmbiguousLeadingZero>(normalizeIpv4Block("192.0.2.010", Ipv4Policy.DottedQuad.block(24)), "dotted-quad octal")
        // A block policy takes an address, never a network.
        assertRefused<Ipv4NormalizationError.MalformedAddress>(normalizeIpv4Block("192.0.2.0/24", Ipv4Policy.DottedQuad.block(24)), "prefix on block input")
    }

    @Test
    fun ipv4DeriveManyIsFrozen() {
        val outcome = normalizeIpv4Blocks("198.51.100.77", Ipv4Policy.DottedQuad, listOf(24, 16))
        assertTrue(outcome is Outcome.Success)
        assertEquals(listOf("198.51.100.0/24", "198.51.0.0/16"), outcome.data.map { it.canonical })
        assertEquals(listOf("ipv4.dotted-quad+block-24", "ipv4.dotted-quad+block-16"), outcome.data.map { it.policyId })
    }

    @Test
    fun ipv4CidrInputIsFrozen() {
        val strict = Ipv4Policy.DottedQuad.cidr()
        val masked = Ipv4Policy.DottedQuad.cidrMasked()
        val result = normalizeIpv4Cidr("192.0.2.0/24", strict)
        assertTrue(result is Outcome.Success)
        assertEquals("192.0.2.0/24", result.data.canonical)
        assertEquals("192.0.2.0", result.data.network)
        assertEquals(24, result.data.prefixLength)

        assertEquals("0.0.0.0/0", canonical(normalizeIpv4Cidr("0.0.0.0/0", strict), "/0"))
        assertEquals("192.0.2.57/32", canonical(normalizeIpv4Cidr("192.0.2.57/32", strict), "/32"))
        assertEquals("192.0.2.0/24", canonical(normalizeIpv4Cidr("192.0.2.57/24", masked), "masked"))
        assertEquals("0.0.0.0/0", canonical(normalizeIpv4Cidr("192.0.2.57/0", masked), "masked /0"))
        assertEquals("192.0.2.0/24", canonical(normalizeIpv4Cidr("0xC0.0.2.0/24", Ipv4Policy.InetAton.cidr()), "inet-aton cidr"))

        assertRefused<Ipv4NormalizationError.HostBitsSet>(normalizeIpv4Cidr("192.0.2.57/24", strict), "host bits")
        assertRefused<Ipv4NormalizationError.HostBitsSet>(normalizeIpv4Cidr("192.0.2.57/0", strict), "host bits /0")
        assertRefused<Ipv4NormalizationError.MissingPrefix>(normalizeIpv4Cidr("192.0.2.0", strict), "no prefix")
        assertRefused<Ipv4NormalizationError.MalformedPrefix>(normalizeIpv4Cidr("192.0.2.0/", strict), "empty prefix")
        assertRefused<Ipv4NormalizationError.MalformedPrefix>(normalizeIpv4Cidr("192.0.2.0/2a", strict), "non-decimal prefix")
        assertRefused<Ipv4NormalizationError.MalformedPrefix>(normalizeIpv4Cidr("192.0.2.0/024", strict), "zero-padded prefix")
        assertRefused<Ipv4NormalizationError.PrefixOutOfRange>(normalizeIpv4Cidr("192.0.2.0/33", strict), "/33")
        assertRefused<Ipv4NormalizationError.PrefixOutOfRange>(normalizeIpv4Cidr("192.0.2.0/1000", strict), "/1000")
        assertRefused<Ipv4NormalizationError.AmbiguousLeadingZero>(normalizeIpv4Cidr("192.0.02.0/24", strict), "leading zero")
        assertRefused<Ipv4NormalizationError.ShorthandNotSupported>(normalizeIpv4Cidr("192.0.2/24", strict), "shorthand")
        assertRefused<Ipv4NormalizationError.MalformedAddress>(normalizeIpv4Cidr("192.0.2.0/24/24", strict), "two prefixes")
    }

    @Test
    fun ipv6BlocksAreFrozen() {
        val rfc5952 = Ipv6Policy.Rfc5952
        val cases = listOf(
            Triple("2001:DB8:abcd:12::1", 64, "2001:db8:abcd:12::/64"),
            Triple("2001:DB8:abcd:12::1", 48, "2001:db8:abcd::/48"),
            Triple("2001:db8:abcd:1234::1", 56, "2001:db8:abcd:1200::/56"),
            Triple("2001:db8:abcd:12::1", 128, "2001:db8:abcd:12::1/128"),
            Triple("2001:db8:abcd:12::1", 0, "::/0"),
            // An IPv4-mapped address stays IPv6, and keeps its mapped spelling while the prefix covers it.
            Triple("::ffff:192.0.2.5", 120, "::ffff:192.0.2.0/120"),
        )
        for ((address, prefix, expected) in cases) {
            assertEquals(expected, canonical(normalizeIpv6Block(address, rfc5952.block(prefix)), "$address /$prefix"))
        }
        assertRefused<Ipv6NormalizationError.PrefixLength>(normalizeIpv6Block("2001:db8::/64", rfc5952.block(64)), "prefix on block input")
        assertRefused<Ipv6NormalizationError.ZoneIdentifier>(normalizeIpv6Block("fe80::1%eth0", rfc5952.block(64)), "zone")
    }

    @Test
    fun ipv6DeriveManyIsFrozen() {
        val outcome = normalizeIpv6Blocks("2001:db8:abcd:12::1", Ipv6Policy.Rfc5952, listOf(64, 48))
        assertTrue(outcome is Outcome.Success)
        assertEquals(listOf("2001:db8:abcd:12::/64", "2001:db8:abcd::/48"), outcome.data.map { it.canonical })
        assertEquals(listOf("ipv6.rfc5952+block-64", "ipv6.rfc5952+block-48"), outcome.data.map { it.policyId })
    }

    @Test
    fun ipv6CidrInputIsFrozen() {
        val strict = Ipv6Policy.Rfc5952.cidr()
        val masked = Ipv6Policy.Rfc5952.cidrMasked()
        val result = normalizeIpv6Cidr("2001:DB8:0:0::/32", strict)
        assertTrue(result is Outcome.Success)
        assertEquals("2001:db8::/32", result.data.canonical)
        assertEquals("2001:db8::", result.data.network)
        assertEquals(32, result.data.prefixLength)

        assertEquals("2001:db8::/64", canonical(normalizeIpv6Cidr("2001:db8::1/64", masked), "masked"))
        assertRefused<Ipv6NormalizationError.HostBitsSet>(normalizeIpv6Cidr("2001:db8::1/64", strict), "host bits")
        assertRefused<Ipv6NormalizationError.MissingPrefix>(normalizeIpv6Cidr("2001:db8::", strict), "no prefix")
        assertRefused<Ipv6NormalizationError.MalformedPrefix>(normalizeIpv6Cidr("2001:db8::/064", strict), "zero-padded prefix")
        assertRefused<Ipv6NormalizationError.PrefixOutOfRange>(normalizeIpv6Cidr("2001:db8::/129", strict), "/129")
        assertRefused<Ipv6NormalizationError.Bracketed>(normalizeIpv6Cidr("[2001:db8::]/32", strict), "brackets")
        assertRefused<Ipv6NormalizationError.PrefixLength>(normalizeIpv6Cidr("2001:db8::/32/32", strict), "two prefixes")
    }

    @Test
    fun maskedCidrInputAndBlockDerivationWriteTheSameNetworkTheSameWay() {
        // Both go through one formatter. A range read from a list and a block derived from an address
        // are the same text whenever they are the same network.
        assertEquals(
            canonical(normalizeIpv4Cidr("198.51.100.77/24", Ipv4Policy.DottedQuad.cidrMasked()), "cidr"),
            canonical(normalizeIpv4Block("198.51.100.77", Ipv4Policy.DottedQuad.block(24)), "block"),
        )
        assertEquals(
            canonical(normalizeIpv6Cidr("2001:db8:abcd:12::1/48", Ipv6Policy.Rfc5952.cidrMasked()), "cidr"),
            canonical(normalizeIpv6Block("2001:db8:abcd:12::1", Ipv6Policy.Rfc5952.block(48)), "block"),
        )
    }

    @Test
    fun theNetworkAddressIsWhatTheAddressNormalizerWrites() {
        val v4 = normalizeIpv4Cidr("203.0.113.0/24", Ipv4Policy.DottedQuad.cidr()) as Outcome.Success
        assertEquals(canonical(normalizeIpv4("203.0.113.0", Ipv4Policy.DottedQuad), "address"), v4.data.network)
        val v6 = normalizeIpv6Cidr("2001:0DB8::/32", Ipv6Policy.Rfc5952.cidr()) as Outcome.Success
        assertEquals(canonical(normalizeIpv6("2001:0DB8::", Ipv6Policy.Rfc5952), "address"), v6.data.network)
    }

    @Test
    fun networkPolicyIdentitiesAreFrozen() {
        assertEquals("ipv4.dotted-quad+block-24", Ipv4Policy.DottedQuad.block(24).id)
        assertEquals("ipv4.inet-aton+block-24", Ipv4Policy.InetAton.block(24).id)
        assertEquals("ipv4.dotted-quad+cidr", Ipv4Policy.DottedQuad.cidr().id)
        assertEquals("ipv4.dotted-quad+cidr+masked", Ipv4Policy.DottedQuad.cidrMasked().id)
        assertEquals("ipv6.rfc5952+block-64", Ipv6Policy.Rfc5952.block(64).id)
        assertEquals("ipv6.rfc5952+cidr", Ipv6Policy.Rfc5952.cidr().id)
        assertEquals("ipv6.rfc5952+cidr+masked", Ipv6Policy.Rfc5952.cidrMasked().id)
        assertEquals(1, Ipv4Policy.DottedQuad.block(24).version)
        assertEquals(1, Ipv6Policy.Rfc5952.cidrMasked().version)
    }
}
