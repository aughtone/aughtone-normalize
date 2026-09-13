package io.github.aughtone.normalize.mac

import io.github.aughtone.normalize.common.ComparableForm
import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.dataOrElse
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize a MAC address to lowercase colon-separated pairs under [policy].
 *
 * One address has many spellings: `00:00:5E:00:53:01`, `00-00-5e-00-53-01`, Cisco's `0000.5e00.5301`,
 * bare `00005E005301`, and macOS's unpadded `0:0:5e:0:53:1`. Inventory, network access control and device
 * tracking are keyed on these strings, and every source writes them differently, so raw-string matching
 * silently misses. All of them normalize to `00:00:5e:00:53:01`.
 *
 * - **One canonical notation.** Lowercase colon pairs, as most software writes them and consistent with the
 *   suite's other lowercase forms. The IEEE registry's `00-00-5E-00-53-01` is accepted, never produced;
 *   render it for display with [formatMac], which is formatting and never identity.
 * - **Every separated notation may omit leading zeros in a group**, because a group pads to its fixed width
 *   in exactly one way: two digits for a colon or hyphen pair, four for a dotted group.
 * - **Refused:** mixed separators, the wrong number of octets for the policy, an empty or overlong group,
 *   and any character that is not hexadecimal. Nothing is trimmed: an address is not a sentence.
 * - **Not validated:** the multicast, broadcast and locally-administered bits. They describe where an
 *   address came from, not how it is spelled.
 *
 * ```
 * normalizeMac("00-00-5E-00-53-01", MacPolicy.Eui48)
 *     .onSuccess { normalized -> store(normalized.canonical) }   // "00:00:5e:00:53:01"
 *     .onFailure { failure -> log(failure.exception) }           // a typed MacNormalizationError
 * ```
 */
fun normalizeMac(value: String, policy: MacPolicy): Outcome<NormalizedMac> = runOutcome {
    val octets = readOctets(value, policy.octets)
    NormalizedMac(
        canonical = octets.joinToString(":") { it.toString(16).padStart(2, '0') },
        policyId = policy.id,
        policyVersion = policy.version,
    )
}

/**
 * A MAC address notation for display. Rendering is lossless: every notation normalizes back to the same
 * canonical address.
 */
enum class MacNotation {
    /** Lowercase colon pairs, the canonical form: `00:00:5e:00:53:01`. */
    Colon,

    /** The IEEE registry notation, uppercase hyphen pairs: `00-00-5E-00-53-01`. */
    Ieee,

    /** Cisco's dotted groups of four: `0000.5e00.5301`. */
    CiscoDotted,

    /** Hexadecimal with no separators: `00005e005301`. */
    Bare,
}

/**
 * Render a normalized MAC address in [notation] for display.
 *
 * This is formatting, not normalization: the result carries no policy identity and must not be stored in
 * place of [NormalizedMac.canonical]. Store and compare the canonical form; format it when showing it.
 */
fun formatMac(address: NormalizedMac, notation: MacNotation): String {
    val pairs = address.canonical.split(':')
    return when (notation) {
        MacNotation.Colon -> address.canonical
        MacNotation.Ieee -> pairs.joinToString("-") { it.uppercase() }
        MacNotation.CiscoDotted -> pairs.chunked(2).joinToString(".") { it.joinToString("") }
        MacNotation.Bare -> pairs.joinToString("")
    }
}

/** Read [value] as exactly [count] octets, in any accepted notation. */
private fun readOctets(value: String, count: Int): List<Int> {
    if (value.isEmpty()) throw MacNormalizationError.WrongLength()
    // Characters first: a space or a stray letter is the more useful thing to report than the length it
    // happens to produce.
    if (value.any { !it.isHexDigit() && it != ':' && it != '-' && it != '.' }) throw MacNormalizationError.NotHexadecimal()
    val separators = value.filter { it == ':' || it == '-' || it == '.' }.toSet()
    if (separators.size > 1) throw MacNormalizationError.MixedSeparators()

    return when (separators.singleOrNull()) {
        null -> {
            if (value.length != count * PAIR) throw MacNormalizationError.WrongLength()
            value.chunked(PAIR).map { it.toHex() }
        }

        '.' -> {
            val groups = value.split('.')
            if (groups.size != count / 2) throw MacNormalizationError.WrongLength()
            groups.flatMap { group ->
                if (group.isEmpty() || group.length > DOTTED_GROUP) throw MacNormalizationError.MalformedGroup()
                group.padStart(DOTTED_GROUP, '0').chunked(PAIR).map { it.toHex() }
            }
        }

        else -> {
            val groups = value.split(separators.single())
            if (groups.size != count) throw MacNormalizationError.WrongLength()
            groups.map { group ->
                if (group.isEmpty() || group.length > PAIR) throw MacNormalizationError.MalformedGroup()
                group.toHex()
            }
        }
    }
}

private const val PAIR = 2
private const val DOTTED_GROUP = 4

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun String.toHex(): Int {
    var result = 0
    for (character in this) {
        val digit = when (character) {
            in '0'..'9' -> character - '0'
            in 'a'..'f' -> character - 'a' + 10
            in 'A'..'F' -> character - 'A' + 10
            else -> throw MacNormalizationError.NotHexadecimal()
        }
        result = result * 16 + digit
    }
    return result
}

/**
 * A frozen MAC address normalization policy: EUI-48 or EUI-64.
 *
 * The two are never widened into each other. An EUI-48 address given to [Eui64] is refused, and the
 * reverse, because padding or truncating one to fit the other would invent or discard part of an address.
 */
class MacPolicy internal constructor(
    override val id: String,
    override val version: Int,
    internal val octets: Int,
    private val form: ComparableForm,
) : Policy {

    override val forms: Set<ComparableForm> = setOf(form)

    override fun toString(): String = id

    companion object {
        internal val Eui48Base: PolicyLink = PolicyLink("mac.eui48", LinkKind.Base)
        internal val Eui64Base: PolicyLink = PolicyLink("mac.eui64", LinkKind.Base)

        /** A six-octet address: the ordinary MAC address of Ethernet and Wi-Fi. */
        val Eui48: MacPolicy = MacPolicy(chainOf(Eui48Base), version = 1, octets = 6, form = MacForms.Eui48)

        /** An eight-octet address, as FireWire, ZigBee and IEEE 802.15.4 use. */
        val Eui64: MacPolicy = MacPolicy(chainOf(Eui64Base), version = 1, octets = 8, form = MacForms.Eui64)

        internal val all: List<MacPolicy> = listOf(Eui48, Eui64)

        internal val links: List<PolicyLink> = listOf(Eui48Base, Eui64Base)

        private fun chainOf(vararg links: PolicyLink): String =
            PolicyId.of(links.toList())
                .dataOrElse { error("not a valid policy chain: ${it.message}") }
                .rendered
    }
}

/** The canonical address plus the policy identity that produced it. */
data class NormalizedMac(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/** Why a MAC address could not be normalized. No message carries any part of the input. */
sealed class MacNormalizationError(message: String) : Exception(message) {

    /** More than one kind of separator, such as `00:00-5e…`. */
    class MixedSeparators : MacNormalizationError("mac: mixed separators")

    /** The wrong number of octets for the policy, or bare hexadecimal of the wrong length. */
    class WrongLength : MacNormalizationError("mac: wrong length")

    /** A group that is empty, or longer than its notation allows. */
    class MalformedGroup : MacNormalizationError("mac: malformed group")

    /** A character that is not hexadecimal and not the notation's separator. */
    class NotHexadecimal : MacNormalizationError("mac: not hexadecimal")
}

/** The comparable forms MAC policies write: [Eui48] and [Eui64], in the canonical colon notation. */
object MacForms {
    val Eui48: ComparableForm = ComparableForm("mac.eui48")
    val Eui64: ComparableForm = ComparableForm("mac.eui64")
}
