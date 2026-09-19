package io.github.aughtone.normalize.ipv4

import io.github.aughtone.normalize.common.ComparableForm
import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.quodlibet.IpForms
import io.github.aughtone.normalize.quodlibet.NetworkForm
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Derive the IPv4 network [value] falls in, at the prefix [policy] names.
 *
 * `192.0.2.57` under `ipv4.quad.dotted:block.24` is `192.0.2.0/24`. This is how addresses are bucketed
 * into subnets, and it is many-to-one by design: every address in the block produces the same value, so
 * a block is a grouping key and never a stand-in for the address itself.
 *
 * ## One block policy serves both sides of a match
 *
 * To match an address against a range from a list, run both through the **same** block policy: the
 * address as it arrives, and the range's network address (from [normalizeIpv4Cidr]) at the same prefix.
 * Both values then carry one id, and matching stays scoped by policy identity.
 *
 * The address is read under [Ipv4BlockPolicy.address]'s own rules, which is why they appear in the id:
 * `inet-aton` reads `010` as 8 and `dotted-quad` refuses it.
 *
 * ```
 * normalizeIpv4Block("192.0.2.57", Ipv4Policy.DottedQuad.block(24))   // "192.0.2.0/24"
 * ```
 */
fun normalizeIpv4Block(value: String, policy: Ipv4BlockPolicy): Outcome<NormalizedIpv4Network> = runOutcome {
    policy.derive(policy.address.parseAddress(value))
}

/**
 * Derive several blocks for one address at once, one result per entry of [prefixLengths], in that order.
 *
 * The address is parsed once, so every block in the set is derived from the same reading of it. Each
 * result carries its own id, `…+block-24`, `…+block-16`, because values at different prefixes are
 * different claims.
 *
 * ```
 * normalizeIpv4Blocks("192.0.2.57", Ipv4Policy.DottedQuad, listOf(24, 16))
 * // "192.0.2.0/24" under ipv4.quad.dotted:block.24, "192.0.0.0/16" under ipv4.quad.dotted:block.16
 * ```
 */
fun normalizeIpv4Blocks(
    value: String,
    address: Ipv4Policy,
    prefixLengths: List<Int>,
): Outcome<List<NormalizedIpv4Network>> = runOutcome {
    val policies = prefixLengths.map { address.block(it) }
    val parsed = address.parseAddress(value)
    policies.map { it.derive(parsed) }
}

/**
 * Canonicalize an IPv4 network written in CIDR form, such as a range read from a list or a configuration.
 *
 * The address part is read under the address policy's rules and rendered as the address normalizer
 * renders it, and the prefix is a decimal from 0 to 32 with no leading zeros. What happens to host bits -
 * `192.0.2.57/24` spells a host inside a network - is the policy's choice, and its id records it:
 * [Ipv4Policy.cidr] refuses them, [Ipv4Policy.cidrMasked] clears them.
 *
 * The result exposes [NormalizedIpv4Network.network] and [NormalizedIpv4Network.prefixLength], so a range
 * can be matched against addresses by deriving its block at the same prefix - see [normalizeIpv4Block].
 */
fun normalizeIpv4Cidr(value: String, policy: Ipv4CidrPolicy): Outcome<NormalizedIpv4Network> = runOutcome {
    val (addressText, prefixLength) = NetworkForm.split(
        value,
        maxPrefix = BITS,
        missing = { Ipv4NormalizationError.MissingPrefix() },
        malformed = { Ipv4NormalizationError.MalformedPrefix() },
        outOfRange = { Ipv4NormalizationError.PrefixOutOfRange() },
    )
    val address = policy.address.parseAddress(addressText)
    val network = address and maskOf(prefixLength)
    if (network != address && !policy.masked) throw Ipv4NormalizationError.HostBitsSet()
    networkResult(network, prefixLength, policy)
}

/**
 * A frozen block derivation: an address policy and the prefix to bucket at. Build one with
 * [Ipv4Policy.block].
 */
class Ipv4BlockPolicy internal constructor(
    /** The rules the address is read under. */
    val address: Ipv4Policy,
    /** The prefix length every derived block has, 0 to 32. */
    val prefixLength: Int,
    internal val optedIn: Set<ComparableForm> = emptySet(),
) : Policy {

    override val id: String = chain(address, optedIn, blockLink(prefixLength))

    override val forms: Set<ComparableForm> = networkForms(address, optedIn)

    override val offeredForms: Set<ComparableForm> = offeredNetworkForms(address)

    /** This block policy with [forms] opted into, as an [Ipv4BlockPolicy]. */
    override fun withForms(forms: Set<ComparableForm>): Ipv4BlockPolicy {
        requireOffered(forms)
        return if (forms.isEmpty()) this else Ipv4BlockPolicy(address, prefixLength, optedIn + forms)
    }

    override val version: Int = 1

    internal fun derive(address: Long): NormalizedIpv4Network =
        networkResult(address and maskOf(prefixLength), prefixLength, this)

    override fun equals(other: Any?): Boolean = other is Ipv4BlockPolicy && other.id == id && other.version == version

    override fun hashCode(): Int = 31 * id.hashCode() + version

    override fun toString(): String = id
}

/**
 * A frozen CIDR input policy: an address policy, and whether host bits are refused or cleared. Build one
 * with [Ipv4Policy.cidr] or [Ipv4Policy.cidrMasked].
 */
class Ipv4CidrPolicy internal constructor(
    /** The rules the address part is read under. */
    val address: Ipv4Policy,
    /** True if host bits are cleared rather than refused. */
    val masked: Boolean,
    internal val optedIn: Set<ComparableForm> = emptySet(),
) : Policy {

    override val id: String =
        if (masked) chain(address, optedIn, CIDR_LINK, MASKED_LINK) else chain(address, optedIn, CIDR_LINK)

    override val forms: Set<ComparableForm> = networkForms(address, optedIn)

    override val offeredForms: Set<ComparableForm> = offeredNetworkForms(address)

    /** This CIDR policy with [forms] opted into, as an [Ipv4CidrPolicy]. */
    override fun withForms(forms: Set<ComparableForm>): Ipv4CidrPolicy {
        requireOffered(forms)
        return if (forms.isEmpty()) this else Ipv4CidrPolicy(address, masked, optedIn + forms)
    }

    override val version: Int = 1

    override fun equals(other: Any?): Boolean = other is Ipv4CidrPolicy && other.id == id && other.version == version

    override fun hashCode(): Int = 31 * id.hashCode() + version

    override fun toString(): String = id
}

/**
 * The block derivation at [prefixLength] under these address rules: `ipv4.quad.dotted:block.24`.
 *
 * @throws IllegalArgumentException if [prefixLength] is not between 0 and 32.
 */
fun Ipv4Policy.block(prefixLength: Int): Ipv4BlockPolicy {
    require(prefixLength in 0..BITS) { "an IPv4 prefix length is 0 to $BITS, was $prefixLength" }
    return Ipv4BlockPolicy(plain, prefixLength)
}

/** CIDR input under these address rules, refusing host bits set: `ipv4.quad.dotted:cidr`. */
fun Ipv4Policy.cidr(): Ipv4CidrPolicy = Ipv4CidrPolicy(plain, masked = false)

/** CIDR input under these address rules, clearing host bits: `ipv4.quad.dotted:cidr:host.zeroed`. */
fun Ipv4Policy.cidrMasked(): Ipv4CidrPolicy = Ipv4CidrPolicy(plain, masked = true)

/**
 * A canonical IPv4 network plus the policy identity that produced it.
 *
 * @property network The network address alone, host bits clear: `192.0.2.0`.
 * @property prefixLength The prefix length: `24`.
 */
data class NormalizedIpv4Network(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
    val network: String,
    val prefixLength: Int,
) : Normalized

/** The links IPv4 network policies add, published for resolution. */
internal object Ipv4Networks {

    val links: List<PolicyLink> = (0..BITS).map { blockLink(it) } + listOf(CIDR_LINK, MASKED_LINK)

    /** The CIDR policies for every address policy. Blocks are rebuilt from their ids rather than listed. */
    val policies: List<Policy> = Ipv4Policy.all.flatMap { listOf(it.cidr(), it.cidrMasked()) }
}

private const val BITS = 32

internal val CIDR_LINK: PolicyLink = PolicyLink("cidr", LinkKind.Parameter)
internal val MASKED_LINK: PolicyLink = PolicyLink("host.zeroed", LinkKind.Parameter)

internal fun blockLink(prefixLength: Int): PolicyLink = PolicyLink("block.$prefixLength", LinkKind.Parameter)

/** The mask for a prefix: the top [prefixLength] bits set. Shifting by 32 clears every bit, which is `/0`. */
private fun maskOf(prefixLength: Int): Long = (-1L shl (BITS - prefixLength)) and 0xFFFFFFFFL

private fun networkResult(network: Long, prefixLength: Int, policy: Policy): NormalizedIpv4Network {
    val address = formatIpv4(network)
    return NormalizedIpv4Network(
        canonical = NetworkForm.of(address, prefixLength),
        policyId = policy.id,
        policyVersion = policy.version,
        network = address,
        prefixLength = prefixLength,
    )
}

private fun chain(address: Ipv4Policy, optedIn: Set<ComparableForm>, vararg parameters: PolicyLink): String =
    PolicyId.of(listOf(PolicyLink(address.base, LinkKind.Base)) + parameters + optedIn.sorted().map { it.link }).dataOrThrow().rendered

/** `dotted-quad` networks write the IPv4 network form; `inet-aton` networks only once a caller opts in. */
private fun networkForms(address: Ipv4Policy, optedIn: Set<ComparableForm>): Set<ComparableForm> =
    if (address.interpretsShorthand) optedIn else setOf(IpForms.Ipv4Network)

private fun offeredNetworkForms(address: Ipv4Policy): Set<ComparableForm> =
    if (address.interpretsShorthand) setOf(IpForms.Ipv4Network) else emptySet()

private fun Policy.requireOffered(forms: Set<ComparableForm>) {
    val refused = forms.firstOrNull { it !in offeredForms }
    require(refused == null) { "$id does not offer the comparable form $refused" }
}
