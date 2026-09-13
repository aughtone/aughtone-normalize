package io.github.aughtone.normalize.ipv6

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.ipv4.CIDR_LINK
import io.github.aughtone.normalize.ipv4.MASKED_LINK
import io.github.aughtone.normalize.ipv4.blockLink
import io.github.aughtone.normalize.quodlibet.NetworkForm
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Derive the IPv6 network [value] falls in, at the prefix [policy] names.
 *
 * `2001:db8::1` under `ipv6.rfc5952+block-64` is `2001:db8::/64`. Many-to-one by design, so a block is a
 * grouping key and never a stand-in for the address. To match an address against a range from a list,
 * run both through the same block policy at the same prefix - see `normalizeIpv4Block` for the reasoning.
 *
 * An IPv4-mapped address (`::ffff:192.0.2.5`) stays IPv6 here, exactly as the address normalizer treats
 * it. The consequence is deliberate and worth knowing: it never matches `192.0.2.5` under an IPv4 policy,
 * so an IPv4 list does not catch the mapped spelling of the same host.
 */
fun normalizeIpv6Block(value: String, policy: Ipv6BlockPolicy): Outcome<NormalizedIpv6Network> = runOutcome {
    policy.derive(parseIpv6Address(value))
}

/**
 * Derive several blocks for one address at once, one result per entry of [prefixLengths], in that order,
 * each under its own id and all from a single parse of the address.
 */
fun normalizeIpv6Blocks(
    value: String,
    address: Ipv6Policy,
    prefixLengths: List<Int>,
): Outcome<List<NormalizedIpv6Network>> = runOutcome {
    val policies = prefixLengths.map { address.block(it) }
    val parsed = parseIpv6Address(value)
    policies.map { it.derive(parsed) }
}

/**
 * Canonicalize an IPv6 network written in CIDR form. The address part is rendered as the address
 * normalizer renders it, and the prefix is a decimal from 0 to 128 with no leading zeros. Host bits are
 * refused by [Ipv6Policy.cidr] and cleared by [Ipv6Policy.cidrMasked].
 */
fun normalizeIpv6Cidr(value: String, policy: Ipv6CidrPolicy): Outcome<NormalizedIpv6Network> = runOutcome {
    val (addressText, prefixLength) = NetworkForm.split(
        value,
        maxPrefix = BITS,
        missing = { Ipv6NormalizationError.MissingPrefix() },
        malformed = { Ipv6NormalizationError.MalformedPrefix() },
        outOfRange = { Ipv6NormalizationError.PrefixOutOfRange() },
    )
    val address = parseIpv6Address(addressText)
    val network = mask(address, prefixLength)
    if (!network.contentEquals(address) && !policy.masked) throw Ipv6NormalizationError.HostBitsSet()
    networkResult(network, prefixLength, policy)
}

/** A frozen IPv6 block derivation. Build one with [Ipv6Policy.block]. */
class Ipv6BlockPolicy internal constructor(
    /** The rules the address is read under. */
    val address: Ipv6Policy,
    /** The prefix length every derived block has, 0 to 128. */
    val prefixLength: Int,
) : Policy {

    override val id: String = chain(address, blockLink(prefixLength))

    override val version: Int = 1

    internal fun derive(address: IntArray): NormalizedIpv6Network =
        networkResult(mask(address, prefixLength), prefixLength, this)

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
        if (masked) chain(address, CIDR_LINK, MASKED_LINK) else chain(address, CIDR_LINK)

    override val version: Int = 1

    override fun equals(other: Any?): Boolean = other is Ipv6CidrPolicy && other.id == id && other.version == version

    override fun hashCode(): Int = 31 * id.hashCode() + version

    override fun toString(): String = id
}

/**
 * The block derivation at [prefixLength]: `ipv6.rfc5952+block-64`.
 *
 * @throws IllegalArgumentException if [prefixLength] is not between 0 and 128.
 */
fun Ipv6Policy.block(prefixLength: Int): Ipv6BlockPolicy {
    require(prefixLength in 0..BITS) { "an IPv6 prefix length is 0 to $BITS, was $prefixLength" }
    return Ipv6BlockPolicy(this, prefixLength)
}

/** CIDR input, refusing host bits set: `ipv6.rfc5952+cidr`. */
fun Ipv6Policy.cidr(): Ipv6CidrPolicy = Ipv6CidrPolicy(this, masked = false)

/** CIDR input, clearing host bits: `ipv6.rfc5952+cidr+masked`. */
fun Ipv6Policy.cidrMasked(): Ipv6CidrPolicy = Ipv6CidrPolicy(this, masked = true)

/**
 * A canonical IPv6 network plus the policy identity that produced it.
 *
 * @property network The network address alone, host bits clear, in RFC 5952 form: `2001:db8::`.
 * @property prefixLength The prefix length: `64`.
 */
data class NormalizedIpv6Network(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
    val network: String,
    val prefixLength: Int,
) : Normalized

/** Every block and CIDR policy, and the links they add, published for resolution. */
internal object Ipv6Networks {

    val links: List<PolicyLink> = (0..BITS).map { blockLink(it) } + listOf(CIDR_LINK, MASKED_LINK)

    val policies: List<Policy> = Ipv6Policy.all.flatMap { address ->
        (0..BITS).map { address.block(it) } + listOf(address.cidr(), address.cidrMasked())
    }
}

private const val BITS = 128
private const val FIELD_BITS = 16

/** Clear every bit after the first [prefixLength], field by field. */
private fun mask(fields: IntArray, prefixLength: Int): IntArray = IntArray(fields.size) { index ->
    val kept = (prefixLength - index * FIELD_BITS).coerceIn(0, FIELD_BITS)
    val fieldMask = if (kept == 0) 0 else (0xFFFF shl (FIELD_BITS - kept)) and 0xFFFF
    fields[index] and fieldMask
}

private fun networkResult(network: IntArray, prefixLength: Int, policy: Policy): NormalizedIpv6Network {
    val address = renderIpv6(network)
    return NormalizedIpv6Network(
        canonical = NetworkForm.of(address, prefixLength),
        policyId = policy.id,
        policyVersion = policy.version,
        network = address,
        prefixLength = prefixLength,
    )
}

private fun chain(address: Ipv6Policy, vararg parameters: PolicyLink): String =
    PolicyId.of(listOf(PolicyLink(address.id, LinkKind.Base)) + parameters).dataOrThrow().rendered
