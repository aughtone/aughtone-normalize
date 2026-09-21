package io.github.aughtone.normalize.ipv6

import io.github.aughtone.normalize.common.ComparableForm
import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.ipv4.formatIpv4
import io.github.aughtone.normalize.quodlibet.IpForms
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.dataOrElse
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
 * normalizeIpv6(value, Ipv6Policy.Rfc5952)
 *     .onSuccess { normalized -> store(normalized.canonical) }   // "2001:db8::1"
 *     .onFailure { failure -> log(failure.exception) }           // a typed Ipv6NormalizationError
 * ```
 */
fun normalizeIpv6(value: String, policy: Ipv6Policy): Outcome<NormalizedIpv6> = runOutcome {
    NormalizedIpv6(canonical = policy.readAddress(value).render(), policyId = policy.id, policyVersion = policy.version)
}

/**
 * An address as a policy reads it: IPv6 fields with the zone the policy kept, or the IPv4 address one of
 * its modes folded out of an IPv4-mapped or NAT64 address.
 */
internal sealed class Ipv6Reading {

    abstract fun render(): String

    class V6(val fields: IntArray, val zone: String?) : Ipv6Reading() {
        override fun render(): String = renderIpv6(fields) + (zone?.let { "%$it" } ?: "")
    }

    class V4(val address: Long) : Ipv6Reading() {
        override fun render(): String = formatIpv4(address)
    }
}

/** Read one address under this policy's modes. */
internal fun Ipv6Policy.readAddress(value: String): Ipv6Reading {
    val (fields, zoneId) = readFields(value)
    val ipv4 = embeddedIpv4(fields) ?: return Ipv6Reading.V6(fields, zoneId)
    // A zone names an IPv6 interface; an address folded out to IPv4 has nowhere to put one.
    if (zoneId != null) throw Ipv6NormalizationError.ZoneIdentifier()
    return Ipv6Reading.V4(ipv4)
}

/** Read the fields and, under the zone mode, the zone identifier. Without it a zone is refused. */
internal fun Ipv6Policy.readFields(value: String): Pair<IntArray, String?> {
    val percent = value.indexOf('%')
    if (percent < 0) return parseIpv6Address(value) to null
    if (!zone) throw Ipv6NormalizationError.ZoneIdentifier()
    val zoneId = value.substring(percent + 1)
    // RFC 6874: a zone in a URI is unreserved characters. Anything else would need escaping somewhere,
    // and an identifier that is spelled differently in different places is not a canonical one.
    if (zoneId.isEmpty() || zoneId.any { !it.isZoneCharacter() }) throw Ipv6NormalizationError.InvalidZone()
    return parseIpv6Address(value.substring(0, percent)) to zoneId
}

/** The IPv4 address this policy's modes fold out of [fields], or `null` if none applies. */
internal fun Ipv6Policy.embeddedIpv4(fields: IntArray): Long? {
    val folds = (unmap && fields.isIpv4Mapped()) || (nat64 && fields.isNat64())
    return if (folds) (fields[6].toLong() shl 16) or fields[7].toLong() else null
}

private fun Char.isZoneCharacter(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '.' || this == '_' || this == '~'

/**
 * Read [value] as one address into its eight 16-bit fields, or refuse it with a typed error. Shared by
 * the address normalizer and the network normalizers, so an address inside a network can never be read
 * differently from the same address on its own.
 */
internal fun parseIpv6Address(value: String): IntArray {
    // Every refusal below exists because the alternative would either lie about the address or lose
    // part of it, and this normalizer's output is something a caller matches on.
    if (value.contains('%')) throw Ipv6NormalizationError.ZoneIdentifier()
    if (value.contains('[') || value.contains(']')) throw Ipv6NormalizationError.Bracketed()
    if (value.contains('/')) throw Ipv6NormalizationError.PrefixLength()
    if (!value.contains(':')) throw Ipv6NormalizationError.NotIpv6()
    return parse(value)
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
internal fun renderIpv6(fields: IntArray): String {
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
internal fun IntArray.isIpv4Mapped(): Boolean =
    this[0] == 0 && this[1] == 0 && this[2] == 0 && this[3] == 0 && this[4] == 0 && this[5] == 0xFFFF

/** `64:ff9b::/96`, the well-known NAT64 prefix of RFC 6052. */
internal fun IntArray.isNat64(): Boolean =
    this[0] == 0x64 && this[1] == 0xFF9B && this[2] == 0 && this[3] == 0 && this[4] == 0 && this[5] == 0

/**
 * A frozen IPv6 normalization policy.
 *
 * [Rfc5952] is the default, and deliberately has no lenient variant: every relaxation anyone would
 * propose changes which address the value refers to rather than how it is spelled. What it has instead is
 * **modes**, each named in the id, for systems that need a different reading and choose it on purpose:
 *
 * - [unmap] writes an IPv4-mapped address (`::ffff:0:0/96`) as its IPv4 address, so `::ffff:192.0.2.5`
 *   and `192.0.2.5` are the same text. The obsolete IPv4-compatible form `::192.0.2.5` is not unmapped.
 * - [nat64] does the same for the well-known NAT64 prefix `64:ff9b::/96`. It is separate from [unmap]
 *   because a NAT64 address is a translated path to an IPv4 host, not the same host.
 * - [zone] keeps a zone identifier, `fe80::1%eth0`, verbatim. A zone names an interface on one machine,
 *   so a value carrying one only means something on that machine.
 *
 * Modes combine, and always render in that order: `ipv6.rfc5952:ipv4.mapped:ipv4.nat64:zone.kept`.
 */
class Ipv6Policy internal constructor(
    internal val unmap: Boolean = false,
    internal val nat64: Boolean = false,
    internal val zone: Boolean = false,
) : Policy {

    /** The links this policy is built from: the base, then each mode in its fixed order. */
    internal val links: List<PolicyLink> = buildList {
        add(Base)
        if (unmap) add(UnmapLink)
        if (nat64) add(Nat64Link)
        if (zone) add(ZoneLink)
    }

    override val id: String = PolicyId.of(links)
        .dataOrElse { error("not a valid policy chain: ${it.message}") }
        .rendered

    override val version: Int = 1

    /**
     * Every IPv6 policy writes the IPv6 address form; one that folds IPv4 out also writes the IPv4 address
     * form, so a mapped or NAT64 address is explicitly comparable with the same host under `ipv4.quad.dotted`.
     */
    override val forms: Set<ComparableForm> =
        if (unmap || nat64) setOf(IpForms.Ipv4Address, IpForms.Ipv6Address) else setOf(IpForms.Ipv6Address)

    /** This policy, also writing IPv4-mapped addresses as IPv4: `…:ipv4.mapped`. */
    fun unmap(): Ipv6Policy = Ipv6Policy(unmap = true, nat64 = nat64, zone = zone)

    /** This policy, also writing NAT64 addresses (`64:ff9b::/96`) as IPv4: `…:ipv4.nat64`. */
    fun nat64(): Ipv6Policy = Ipv6Policy(unmap = unmap, nat64 = true, zone = zone)

    /** This policy, also keeping zone identifiers: `…:zone.kept`. */
    fun zone(): Ipv6Policy = Ipv6Policy(unmap = unmap, nat64 = nat64, zone = true)

    /** True when a mode can fold an address out to IPv4, so a block needs an IPv4 prefix as well. */
    internal val foldsIpv4: Boolean get() = unmap || nat64

    override fun equals(other: Any?): Boolean = other is Ipv6Policy && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id

    companion object {
        /** The base link this policy is built on. */
        internal val Base: PolicyLink = PolicyLink("ipv6.rfc5952", LinkKind.Base)
        internal val UnmapLink: PolicyLink = PolicyLink("ipv4.mapped", LinkKind.Parameter)
        internal val Nat64Link: PolicyLink = PolicyLink("ipv4.nat64", LinkKind.Parameter)
        internal val ZoneLink: PolicyLink = PolicyLink("zone.kept", LinkKind.Parameter)

        /** The canonical form of RFC 5952. */
        val Rfc5952: Ipv6Policy = Ipv6Policy()

        /** The default policy and every combination of modes. */
        internal val all: List<Ipv6Policy> = listOf(false, true).flatMap { unmap ->
            listOf(false, true).flatMap { nat64 ->
                listOf(false, true).map { zone -> Ipv6Policy(unmap, nat64, zone) }
            }
        }
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

    /**
     * A zone identifier names a local interface, so the token would be machine-specific. Refused unless
     * the policy's zone mode keeps it, and always refused on an address a mode folds out to IPv4.
     */
    class ZoneIdentifier : Ipv6NormalizationError("ipv6: zone identifier")

    /** A zone identifier that is empty, or carries a character outside the URI-unreserved set. */
    class InvalidZone : Ipv6NormalizationError("ipv6: invalid zone identifier")

    /** Brackets are URL authority syntax and belong to a URL normalizer, not here. */
    class Bracketed : Ipv6NormalizationError("ipv6: bracketed address")

    /** A prefix length describes a network, not an address. */
    class PrefixLength : Ipv6NormalizationError("ipv6: prefix length")

    /** No colon at all: an IPv4 address is a different address family, and widening it is a guess. */
    class NotIpv6 : Ipv6NormalizationError("ipv6: not an IPv6 address")

    /** Not a well-formed address: wrong field count, two `::`, or a field out of range. */
    class MalformedAddress : Ipv6NormalizationError("ipv6: malformed address")

    /** CIDR input with no `/` and prefix length. */
    class MissingPrefix : Ipv6NormalizationError("ipv6: missing prefix length")

    /** A prefix length that is empty, not decimal, or padded with a leading zero. */
    class MalformedPrefix : Ipv6NormalizationError("ipv6: malformed prefix length")

    /** A prefix length longer than the address family allows. */
    class PrefixOutOfRange : Ipv6NormalizationError("ipv6: prefix length out of range")

    /**
     * CIDR input whose address has bits set beyond the prefix - a host inside the network rather than the
     * network - under the policy that refuses it rather than clearing them.
     */
    class HostBitsSet : Ipv6NormalizationError("ipv6: host bits set")
}
