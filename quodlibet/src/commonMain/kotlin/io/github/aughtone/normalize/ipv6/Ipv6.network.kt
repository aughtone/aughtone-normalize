package io.github.aughtone.normalize.ipv6

import io.github.aughtone.normalize.common.ComparableForm
import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.ipv4.CIDR_LINK
import io.github.aughtone.normalize.ipv4.MASKED_LINK
import io.github.aughtone.normalize.ipv4.blockLink
import io.github.aughtone.normalize.ipv4.formatIpv4
import io.github.aughtone.normalize.quodlibet.IpForms
import io.github.aughtone.normalize.quodlibet.NetworkForm
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Derive the network [value] falls in, at the prefix [policy] names.
 *
 * `2001:db8::1` under `ipv6.rfc5952+block-64` is `2001:db8::/64`. Many-to-one by design, so a block is a
 * grouping key and never a stand-in for the address. To match an address against a range from a list,
 * run both through the same block policy at the same prefix - see `normalizeIpv4Block` for the reasoning.
 *
 * Under the default policy an IPv4-mapped address (`::ffff:192.0.2.5`) stays IPv6, so it never matches
 * `192.0.2.5` under an IPv4 policy. A policy with the `unmap` or `nat64` mode folds those addresses out to
 * IPv4 instead, which is why its block policy carries two prefixes: `ipv6.rfc5952+unmap+block-v4-24+block-v6-64`
 * buckets a mapped address at `/24` as IPv4 and any other address at `/64` as IPv6.
 */
fun normalizeIpv6Block(value: String, policy: Ipv6BlockPolicy): Outcome<NormalizedIpv6Network> = runOutcome {
    policy.derive(policy.address.readAddress(value))
}

/**
 * Derive several blocks for one address at once, one result per entry of [prefixLengths], in that order,
 * each under its own id and all from a single reading of the address. For a policy without a mode that
 * folds IPv4 out; see the overload taking block policies for one that does.
 */
fun normalizeIpv6Blocks(
    value: String,
    address: Ipv6Policy,
    prefixLengths: List<Int>,
): Outcome<List<NormalizedIpv6Network>> = runOutcome {
    val policies = prefixLengths.map { address.block(it) }
    val reading = address.readAddress(value)
    policies.map { it.derive(reading) }
}

/**
 * Derive a block under each of [policies] for one address, in order, from a single reading of it. Every
 * policy must be built on the same address policy, so the set is always derived from one reading.
 */
fun normalizeIpv6Blocks(value: String, policies: List<Ipv6BlockPolicy>): Outcome<List<NormalizedIpv6Network>> = runOutcome {
    require(policies.isNotEmpty()) { "no block policies given" }
    val address = policies.first().address
    require(policies.all { it.address == address }) { "every block policy must share one address policy" }
    val reading = address.readAddress(value)
    policies.map { it.derive(reading) }
}

/**
 * Canonicalize a network written in CIDR form. The address part is read under the address policy's modes
 * and rendered as the address normalizer renders it, and the prefix is a decimal from 0 to 128 with no
 * leading zeros. Host bits are refused by [Ipv6Policy.cidr] and cleared by [Ipv6Policy.cidrMasked].
 *
 * Under `unmap` or `nat64`, a network wholly inside the folded range - a prefix of 96 or longer - is
 * written as the IPv4 network it contains: `::ffff:192.0.2.0/120` is `192.0.2.0/24`, the same text a
 * block derivation at `/24` produces for an address in it. A shorter prefix covers more than the folded
 * range and stays IPv6.
 */
fun normalizeIpv6Cidr(value: String, policy: Ipv6CidrPolicy): Outcome<NormalizedIpv6Network> = runOutcome {
    val (addressText, prefixLength) = NetworkForm.split(
        value,
        maxPrefix = BITS,
        missing = { Ipv6NormalizationError.MissingPrefix() },
        malformed = { Ipv6NormalizationError.MalformedPrefix() },
        outOfRange = { Ipv6NormalizationError.PrefixOutOfRange() },
    )
    val (fields, zoneId) = policy.address.readFields(addressText)
    val ipv4 = if (prefixLength >= FOLDED_PREFIX) policy.address.embeddedIpv4(fields) else null
    if (ipv4 != null) {
        if (zoneId != null) throw Ipv6NormalizationError.ZoneIdentifier()
        val ipv4Prefix = prefixLength - FOLDED_PREFIX
        val network = ipv4 and ipv4Mask(ipv4Prefix)
        if (network != ipv4 && !policy.masked) throw Ipv6NormalizationError.HostBitsSet()
        return@runOutcome networkResult(formatIpv4(network), ipv4Prefix, policy)
    }
    val network = mask(fields, prefixLength)
    if (!network.contentEquals(fields) && !policy.masked) throw Ipv6NormalizationError.HostBitsSet()
    networkResult(Ipv6Reading.V6(network, zoneId).render(), prefixLength, policy)
}

/**
 * A frozen IPv6 block derivation. Build one with [Ipv6Policy.block].
 *
 * A policy whose address policy folds IPv4 out (`unmap`, `nat64`) carries an [ipv4PrefixLength] for the
 * addresses it folds, and [prefixLength] for every other address.
 */
class Ipv6BlockPolicy internal constructor(
    /** The rules the address is read under. */
    val address: Ipv6Policy,
    /** The IPv6 prefix length, 0 to 128. */
    val prefixLength: Int,
    /** The IPv4 prefix length, 0 to 32, when the address policy folds IPv4 out; otherwise `null`. */
    val ipv4PrefixLength: Int?,
) : Policy {

    override val id: String = PolicyId.of(
        address.links + if (ipv4PrefixLength == null) {
            listOf(blockLink(prefixLength))
        } else {
            listOf(ipv4BlockLink(ipv4PrefixLength), ipv6BlockLink(prefixLength))
        },
    ).dataOrThrow().rendered

    override val version: Int = 1

    override val forms: Set<ComparableForm> = networkForms(address)

    internal fun derive(reading: Ipv6Reading): NormalizedIpv6Network = when (reading) {
        is Ipv6Reading.V4 -> {
            val prefix = ipv4PrefixLength ?: error("an address folded to IPv4 needs an IPv4 prefix")
            networkResult(formatIpv4(reading.address and ipv4Mask(prefix)), prefix, this)
        }
        is Ipv6Reading.V6 -> networkResult(Ipv6Reading.V6(mask(reading.fields, prefixLength), reading.zone).render(), prefixLength, this)
    }

    override fun equals(other: Any?): Boolean = other is Ipv6BlockPolicy && other.id == id && other.version == version

    override fun hashCode(): Int = 31 * id.hashCode() + version

    override fun toString(): String = id
}

/** A frozen IPv6 CIDR input policy. Build one with [Ipv6Policy.cidr] or [Ipv6Policy.cidrMasked]. */
class Ipv6CidrPolicy internal constructor(
    /** The rules the address part is read under. */
    val address: Ipv6Policy,
    /** True if host bits are cleared rather than refused. */
    val masked: Boolean,
) : Policy {

    override val id: String =
        PolicyId.of(address.links + if (masked) listOf(CIDR_LINK, MASKED_LINK) else listOf(CIDR_LINK)).dataOrThrow().rendered

    override val version: Int = 1

    override val forms: Set<ComparableForm> = networkForms(address)

    override fun equals(other: Any?): Boolean = other is Ipv6CidrPolicy && other.id == id && other.version == version

    override fun hashCode(): Int = 31 * id.hashCode() + version

    override fun toString(): String = id
}

/**
 * The block derivation at [prefixLength]: `ipv6.rfc5952+block-64`.
 *
 * @throws IllegalArgumentException if [prefixLength] is not between 0 and 128, or if this policy folds
 * IPv4 out and so needs an IPv4 prefix as well - use the overload taking both.
 */
fun Ipv6Policy.block(prefixLength: Int): Ipv6BlockPolicy {
    require(!foldsIpv4) { "$id folds IPv4 out, so a block needs an IPv4 and an IPv6 prefix: block(ipv4PrefixLength, ipv6PrefixLength)" }
    require(prefixLength in 0..BITS) { "an IPv6 prefix length is 0 to $BITS, was $prefixLength" }
    return Ipv6BlockPolicy(this, prefixLength, ipv4PrefixLength = null)
}

/**
 * The block derivation for a policy that folds IPv4 out: `ipv6.rfc5952+unmap+block-v4-24+block-v6-64`.
 *
 * @throws IllegalArgumentException if this policy does not fold IPv4 out, or a prefix is out of range.
 */
fun Ipv6Policy.block(ipv4PrefixLength: Int, ipv6PrefixLength: Int): Ipv6BlockPolicy {
    require(foldsIpv4) { "$id does not fold IPv4 out, so a block takes one prefix: block(prefixLength)" }
    require(ipv4PrefixLength in 0..IPV4_BITS) { "an IPv4 prefix length is 0 to $IPV4_BITS, was $ipv4PrefixLength" }
    require(ipv6PrefixLength in 0..BITS) { "an IPv6 prefix length is 0 to $BITS, was $ipv6PrefixLength" }
    return Ipv6BlockPolicy(this, ipv6PrefixLength, ipv4PrefixLength)
}

/** CIDR input, refusing host bits set: `ipv6.rfc5952+cidr`. */
fun Ipv6Policy.cidr(): Ipv6CidrPolicy = Ipv6CidrPolicy(this, masked = false)

/** CIDR input, clearing host bits: `ipv6.rfc5952+cidr+masked`. */
fun Ipv6Policy.cidrMasked(): Ipv6CidrPolicy = Ipv6CidrPolicy(this, masked = true)

/**
 * A canonical network plus the policy identity that produced it.
 *
 * @property network The network address alone, host bits clear: `2001:db8::`, or `192.0.2.0` for an
 * address a mode folded out to IPv4.
 * @property prefixLength The prefix length of [network]: an IPv4 prefix when the network is IPv4.
 */
data class NormalizedIpv6Network(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
    val network: String,
    val prefixLength: Int,
) : Normalized

/** The links IPv6 network policies add, published for resolution. */
internal object Ipv6Networks {

    val links: List<PolicyLink> = (0..BITS).map { blockLink(it) } +
        (0..IPV4_BITS).map { ipv4BlockLink(it) } +
        (0..BITS).map { ipv6BlockLink(it) } +
        listOf(Ipv6Policy.UnmapLink, Ipv6Policy.Nat64Link, Ipv6Policy.ZoneLink)

    /** The CIDR policies for every address policy. Blocks are rebuilt from their ids rather than listed. */
    val policies: List<Policy> = Ipv6Policy.all.flatMap { listOf(it.cidr(), it.cidrMasked()) }
}

private const val BITS = 128
private const val IPV4_BITS = 32
private const val FIELD_BITS = 16

/** The prefix length at which a network sits wholly inside `::ffff:0:0/96` or `64:ff9b::/96`. */
private const val FOLDED_PREFIX = 96

internal fun ipv4BlockLink(prefixLength: Int): PolicyLink = PolicyLink("block-v4-$prefixLength", LinkKind.Parameter)

internal fun ipv6BlockLink(prefixLength: Int): PolicyLink = PolicyLink("block-v6-$prefixLength", LinkKind.Parameter)

/** Clear every bit after the first [prefixLength], field by field. */
private fun mask(fields: IntArray, prefixLength: Int): IntArray = IntArray(fields.size) { index ->
    val kept = (prefixLength - index * FIELD_BITS).coerceIn(0, FIELD_BITS)
    val fieldMask = if (kept == 0) 0 else (0xFFFF shl (FIELD_BITS - kept)) and 0xFFFF
    fields[index] and fieldMask
}

/** Every IPv6 network writes the IPv6 network form; one whose modes fold IPv4 out also writes the IPv4 one. */
private fun networkForms(address: Ipv6Policy): Set<ComparableForm> =
    if (address.foldsIpv4) setOf(IpForms.Ipv4Network, IpForms.Ipv6Network) else setOf(IpForms.Ipv6Network)

private fun ipv4Mask(prefixLength: Int): Long = (-1L shl (IPV4_BITS - prefixLength)) and 0xFFFFFFFFL

private fun networkResult(address: String, prefixLength: Int, policy: Policy): NormalizedIpv6Network =
    NormalizedIpv6Network(
        canonical = NetworkForm.of(address, prefixLength),
        policyId = policy.id,
        policyVersion = policy.version,
        network = address,
        prefixLength = prefixLength,
    )
