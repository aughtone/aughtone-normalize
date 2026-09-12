package io.github.aughtone.normalize.ipv6

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize an IPv6 address to the canonical form of RFC 5952 under [policy].
 *
 * This is the clearest case in the suite for why byte-stable normalization matters: one address has
 * many valid spellings - `2001:db8::1`, `2001:0DB8:0000:0000:0000:0000:0000:0001`, upper or lower hex -
 * so any system hashing or matching raw address strings is silently missing matches it should find.
 *
 * The canonical form is the RFC's, adopted rather than invented: lowercase hex, leading zeros in each
 * field suppressed, the longest run of zero fields compressed to `::` with the leftmost run winning a
 * tie, and a single zero field never compressed.
 *
 * ```
 * when (val outcome = normalizeIpv6(value, Ipv6Policy.Rfc5952)) {
 *     is Outcome.Success -> outcome.data.canonical   // "2001:db8::1"
 *     is Outcome.Failure -> outcome.exception        // a typed Ipv6NormalizationError
 * }
 * ```
 */
fun normalizeIpv6(value: String, policy: Ipv6Policy): Outcome<NormalizedIpv6> = runOutcome {
    // Every refusal below exists because the alternative would either lie about the address or lose
    // part of it, and this normalizer's output is something a caller matches on.
    if (value.contains('%')) throw Ipv6NormalizationError.ZoneIdentifier()
    if (value.contains('[') || value.contains(']')) throw Ipv6NormalizationError.Bracketed()
    if (value.contains('/')) throw Ipv6NormalizationError.PrefixLength()
    if (!value.contains(':')) throw Ipv6NormalizationError.NotIpv6()

    val fields = parse(value)
    NormalizedIpv6(canonical = render(fields), policyId = policy.id, policyVersion = policy.version)
}

/** Parse into eight 16-bit fields, expanding `::` and any trailing dotted-quad. */
private fun parse(value: String): IntArray {
    val doubleColon = value.indexOf("::")
    if (doubleColon >= 0 && value.indexOf("::", doubleColon + 1) >= 0) {
        throw Ipv6NormalizationError.MalformedAddress()
    }

    val head: List<String>
    val tail: List<String>
    if (doubleColon >= 0) {
        head = value.substring(0, doubleColon).split(':').filter { it.isNotEmpty() }
        tail = value.substring(doubleColon + 2).split(':').filter { it.isNotEmpty() }
    } else {
        head = value.split(':')
        tail = emptyList()
    }

    val headFields = head.flatMap { it.toFields() }
    val tailFields = tail.flatMap { it.toFields() }
    val total = headFields.size + tailFields.size

    if (doubleColon < 0) {
        if (total != 8) throw Ipv6NormalizationError.MalformedAddress()
        return headFields.toIntArray()
    }
    // `::` stands for at least one zero field; an address that is already eight fields long has no
    // room for it, and accepting one would make two different strings mean the same address.
    if (total >= 8) throw Ipv6NormalizationError.MalformedAddress()
    return (headFields + List(8 - total) { 0 } + tailFields).toIntArray()
}

/** One token: either a hex field, or a dotted quad that fills the last two fields. */
private fun String.toFields(): List<Int> {
    if (contains('.')) {
        val parts = split('.')
        if (parts.size != 4) throw Ipv6NormalizationError.MalformedAddress()
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) {
                throw Ipv6NormalizationError.MalformedAddress()
            }
            // A leading zero in a dotted quad has meant octal in enough software to be a security
            // problem, so it is refused rather than interpreted.
            if (part.length > 1 && part[0] == '0') throw Ipv6NormalizationError.MalformedAddress()
            part.toInt().also { if (it > 255) throw Ipv6NormalizationError.MalformedAddress() }
        }
        return listOf((octets[0] shl 8) or octets[1], (octets[2] shl 8) or octets[3])
    }
    if (isEmpty() || length > 4) throw Ipv6NormalizationError.MalformedAddress()
    var field = 0
    for (character in this) {
        val digit = when (character) {
            in '0'..'9' -> character - '0'
            in 'a'..'f' -> character - 'a' + 10
            in 'A'..'F' -> character - 'A' + 10
            else -> throw Ipv6NormalizationError.MalformedAddress()
        }
        field = (field shl 4) or digit
    }
    return listOf(field)
}

/** Render the RFC 5952 form: lowercase, no leading zeros, longest zero run compressed. */
private fun render(fields: IntArray): String {
    if (fields.isIpv4Mapped()) {
        val high = fields[6]
        val low = fields[7]
        return "::ffff:${high shr 8}.${high and 0xFF}.${low shr 8}.${low and 0xFF}"
    }

    var bestStart = -1
    var bestLength = 0
    var start = -1
    var length = 0
    for (index in fields.indices) {
        if (fields[index] == 0) {
            if (start < 0) start = index
            length++
            // Strictly greater, so the leftmost run wins a tie, as the RFC requires.
            if (length > bestLength) {
                bestStart = start
                bestLength = length
            }
        } else {
            start = -1
            length = 0
        }
    }
    // A single zero field is written out: `::` for one field would be shorter to type and is not the
    // canonical form.
    if (bestLength < 2) return fields.joinToString(":") { it.toString(16) }

    val before = fields.take(bestStart).joinToString(":") { it.toString(16) }
    val after = fields.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
    return "$before::$after"
}

/** `::ffff:0:0/96`, the IPv4-mapped range, whose last 32 bits stay in dotted-quad form. */
private fun IntArray.isIpv4Mapped(): Boolean =
    this[0] == 0 && this[1] == 0 && this[2] == 0 && this[3] == 0 && this[4] == 0 && this[5] == 0xFFFF

/**
 * A frozen IPv6 normalization policy.
 *
 * There is one, and deliberately no lenient variant: every relaxation anyone would propose - keeping a
 * zone identifier, accepting a bare IPv4 address, tolerating brackets - changes which address the value
 * refers to rather than how it is spelled. A lenient policy in this suite relaxes a rule; it never
 * relaxes what the value means.
 */
class Ipv6Policy internal constructor(
    override val id: String,
    override val version: Int,
) : Policy {

    override fun toString(): String = id

    companion object {
        /** The base link this policy is built on. */
        internal val Base: PolicyLink = PolicyLink("ipv6.rfc5952", LinkKind.Base)

        /** The canonical form of RFC 5952. */
        val Rfc5952: Ipv6Policy = Ipv6Policy(
            id = when (val outcome = PolicyId.of(listOf(Base))) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            },
            version = 1,
        )

        internal val all: List<Ipv6Policy> = listOf(Rfc5952)
    }
}

/** The canonical address plus the policy identity that produced it. */
data class NormalizedIpv6(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/** Why an address could not be normalized. No message carries any part of the input. */
sealed class Ipv6NormalizationError(message: String) : Exception(message) {

    /** A zone identifier names a local interface, so the token would be machine-specific. */
    class ZoneIdentifier : Ipv6NormalizationError("ipv6: zone identifier")

    /** Brackets are URL authority syntax and belong to a URL normalizer, not here. */
    class Bracketed : Ipv6NormalizationError("ipv6: bracketed address")

    /** A prefix length describes a network, not an address. */
    class PrefixLength : Ipv6NormalizationError("ipv6: prefix length")

    /** No colon at all: an IPv4 address is a different address family, and widening it is a guess. */
    class NotIpv6 : Ipv6NormalizationError("ipv6: not an IPv6 address")

    /** Not a well-formed address: wrong field count, two `::`, or a field out of range. */
    class MalformedAddress : Ipv6NormalizationError("ipv6: malformed address")
}
