package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.normalize.ipv4.Ipv4Networks
import io.github.aughtone.normalize.ipv4.Ipv4Policy
import io.github.aughtone.normalize.ipv4.block
import io.github.aughtone.normalize.ipv4.cidr
import io.github.aughtone.normalize.ipv4.cidrMasked
import io.github.aughtone.normalize.ipv6.Ipv6Networks
import io.github.aughtone.normalize.ipv6.Ipv6Policy
import io.github.aughtone.normalize.ipv6.block
import io.github.aughtone.normalize.ipv6.cidr
import io.github.aughtone.normalize.ipv6.cidrMasked

/**
 * Rebuilds IP address, block and CIDR policies from their ids.
 *
 * Block policies are configured rather than enumerated - an `unmap` policy alone has 33 IPv4 prefixes
 * times 129 IPv6 ones - so a stored id is turned back into its policy by reading the configuration out of
 * it and building that policy through the same factories a caller uses. The rebuilt policy's id must be
 * exactly the one given, so no second spelling of any policy is ever accepted.
 */
internal object IpPolicyIds {

    private val known: Set<String> by lazy {
        (Ipv4Networks.links + Ipv6Networks.links)
            .mapTo(HashSet()) { it.name }
    }

    /**
     * The policy [id] names, `null` when it is not a configured IP policy this object rebuilds (a plain
     * address policy, or another normalizer's id), or a [PolicyIdentityError.NotCanonical] failure when it
     * is one but spelled any way its policy would not render.
     */
    fun rebuild(id: String): Policy? {
        val names = id.split('+')
        val base = names.first()
        val parameters = names.drop(1)
        if (parameters.isEmpty() || parameters.any { it !in known }) return null
        val policy = try {
            when (base) {
                Ipv4Policy.DottedQuad.id -> ipv4(Ipv4Policy.DottedQuad, parameters)
                Ipv4Policy.InetAton.id -> ipv4(Ipv4Policy.InetAton, parameters)
                "ipv6.rfc5952" -> ipv6(parameters)
                else -> return null
            }
        } catch (refused: IllegalArgumentException) {
            throw PolicyIdentityError.NotCanonical(id)
        }
        if (policy.id != id) throw PolicyIdentityError.NotCanonical(id)
        return policy
    }

    private fun ipv4(address: Ipv4Policy, parameters: List<String>): Policy = when {
        parameters == listOf("cidr") -> address.cidr()
        parameters == listOf("cidr", "masked") -> address.cidrMasked()
        parameters.size == 1 -> address.block(prefix(parameters.single(), "block-"))
        else -> throw IllegalArgumentException("not an IPv4 network policy")
    }

    private fun ipv6(parameters: List<String>): Policy {
        var address = Ipv6Policy.Rfc5952
        var rest = parameters
        if (rest.firstOrNull() == "unmap") { address = address.unmap(); rest = rest.drop(1) }
        if (rest.firstOrNull() == "nat64") { address = address.nat64(); rest = rest.drop(1) }
        if (rest.firstOrNull() == "zone") { address = address.zone(); rest = rest.drop(1) }
        return when {
            rest.isEmpty() -> address
            rest == listOf("cidr") -> address.cidr()
            rest == listOf("cidr", "masked") -> address.cidrMasked()
            rest.size == 1 -> address.block(prefix(rest.single(), "block-"))
            rest.size == 2 -> address.block(prefix(rest[0], "block-v4-"), prefix(rest[1], "block-v6-"))
            else -> throw IllegalArgumentException("not an IPv6 policy")
        }
    }

    /** The number after [label], refusing anything the link grammar allows but a prefix does not. */
    private fun prefix(link: String, label: String): Int {
        require(link.startsWith(label)) { "expected $label" }
        val digits = link.removePrefix(label)
        require(digits.isNotEmpty() && digits.all { it in '0'..'9' } && (digits.length == 1 || digits[0] != '0')) { "not a prefix" }
        return digits.toInt()
    }
}
