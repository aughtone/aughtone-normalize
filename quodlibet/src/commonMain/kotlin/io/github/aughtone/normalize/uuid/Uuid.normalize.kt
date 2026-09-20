package io.github.aughtone.normalize.uuid

import io.github.aughtone.normalize.common.ComparableForm
import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize a UUID to its lowercase, hyphenated 8-4-4-4-12 form under [policy].
 *
 * One UUID has many spellings: any case, `{…}` braces, a `urn:uuid:` prefix, or 32 digits with no hyphens.
 * All of them normalize to one text, such as `919108f7-52d1-4320-9bac-f847db4148a8`. RFC 9562 allows any
 * case in the string form; lowercase matches the suite's other identifiers. Nothing is trimmed.
 *
 * ## Windows GUID byte dumps
 *
 * A Windows GUID stores its first three fields little-endian, so its 16 raw bytes hex-encoded in order look
 * like a different UUID: `6B29FC40-CA47-11D1-…` is `40FC296B47CAD111…` as a byte dump. Nothing in the text
 * says which reading is meant, so it is never guessed. A policy with the `bytes.guid` mode
 * ([UuidPolicy.guidBytes]) declares that the input *is* a byte dump, reverses those fields, and writes the
 * ordinary canonical UUID. Braces and `urn:uuid:` are refused under that mode: only string-form APIs produce
 * them, and those already put the bytes in order, so swapping one would silently produce a different UUID.
 *
 * ```
 * normalizeUuid("{919108F7-52D1-4320-9BAC-F847DB4148A8}", UuidPolicy.Hex)
 *     .onSuccess { normalized -> store(normalized.canonical) }   // "919108f7-52d1-4320-9bac-f847db4148a8"
 *     .onFailure { failure -> log(failure.exception) }           // a typed UuidNormalizationError
 * ```
 */
fun normalizeUuid(value: String, policy: UuidPolicy): Outcome<NormalizedUuid> = runOutcome {
    val bytes = if (policy.guidBytes) readGuidBytes(value) else readUuid(value)
    if (policy.requireRfc9562 && !bytes.isRfc9562()) throw UuidNormalizationError.NotRfc9562()
    NormalizedUuid(canonical = bytes.hyphenated(), policyId = policy.id, policyVersion = policy.version)
}

/**
 * A UUID notation for display. Every notation normalizes back to the same canonical UUID: [GuidBytes]
 * through a policy with the `bytes.guid` mode, the rest through the plain policies.
 */
enum class UuidNotation {
    /** The canonical form: `919108f7-52d1-4320-9bac-f847db4148a8`. */
    Hyphenated,

    /** Windows registry style: `{919108f7-52d1-4320-9bac-f847db4148a8}`. */
    Braces,

    /** The RFC 9562 URN: `urn:uuid:919108f7-52d1-4320-9bac-f847db4148a8`. */
    Urn,

    /** Uppercase hyphenated: `919108F7-52D1-4320-9BAC-F847DB4148A8`. */
    Uppercase,

    /** Thirty-two digits, no hyphens: `919108f752d143209bacf847db4148a8`. */
    Bare,

    /** The bytes in Windows GUID order, as a raw dump: `f7089191d15220439bacf847db4148a8`. */
    GuidBytes,
}

/**
 * Render a normalized UUID in [notation] for display.
 *
 * This is formatting, not normalization: the result carries no policy identity and must not be stored in
 * place of [NormalizedUuid.canonical].
 */
fun formatUuid(uuid: NormalizedUuid, notation: UuidNotation): String {
    val canonical = uuid.canonical
    val bare = canonical.replace("-", "")
    return when (notation) {
        UuidNotation.Hyphenated -> canonical
        UuidNotation.Braces -> "{$canonical}"
        UuidNotation.Urn -> "$URN_PREFIX$canonical"
        UuidNotation.Uppercase -> canonical.uppercase()
        UuidNotation.Bare -> bare
        UuidNotation.GuidBytes -> bare.chunked(2).map { it.toInt(16) }.swapGuidFields().joinToString("") { it.hexPair() }
    }
}

private const val URN_PREFIX = "urn:uuid:"
private const val BYTES = 16
private const val DIGITS = 32
private val HYPHEN_POSITIONS = setOf(8, 13, 18, 23)

/** Read a string-form UUID: optional braces or URN prefix around 36 hyphenated or 32 bare digits. */
private fun readUuid(value: String): List<Int> {
    val hasUrn = value.length >= URN_PREFIX.length && value.substring(0, URN_PREFIX.length).lowercase() == URN_PREFIX
    val opens = value.startsWith("{")
    val closes = value.endsWith("}")
    if (opens != closes || (hasUrn && opens)) throw UuidNormalizationError.MalformedWrapper()
    val body = when {
        hasUrn -> value.substring(URN_PREFIX.length)
        opens -> value.substring(1, value.length - 1)
        else -> value
    }
    if (body.any { it == '{' || it == '}' || it == ':' }) throw UuidNormalizationError.MalformedWrapper()
    requireDigits(body, separators = "-")
    return readDigits(body)
}

/** Read the body: 36 characters hyphenated 8-4-4-4-12, or 32 bare digits. */
private fun readDigits(body: String): List<Int> {
    if (body.contains('-')) {
        if (body.length != DIGITS + HYPHEN_POSITIONS.size) {
            throw if (body.replace("-", "").length == DIGITS) UuidNormalizationError.MisplacedHyphen() else UuidNormalizationError.WrongLength()
        }
        for ((index, character) in body.withIndex()) {
            if ((character == '-') != (index in HYPHEN_POSITIONS)) throw UuidNormalizationError.MisplacedHyphen()
        }
        return body.replace("-", "").chunked(2).map { it.toInt(16) }
    }
    if (body.length != DIGITS) throw UuidNormalizationError.WrongLength()
    return body.chunked(2).map { it.toInt(16) }
}

/**
 * Read a Windows GUID byte dump: bare digits, byte pairs with one consistent separator, or 8-4-4-4-12
 * grouping. Braces and a URN prefix prove a string form and are refused.
 */
private fun readGuidBytes(value: String): List<Int> {
    if (value.contains('{') || value.contains('}') || value.lowercase().startsWith(URN_PREFIX)) {
        throw UuidNormalizationError.StringFormNotByteDump()
    }
    requireDigits(value, separators = "-: ")
    val separators = value.filter { it == '-' || it == ':' || it == ' ' }.toSet()
    if (separators.size > 1) throw UuidNormalizationError.MisplacedHyphen()
    val bytes = when (val separator = separators.singleOrNull()) {
        null -> readDigits(value)
        '-' -> if (value.length == DIGITS + HYPHEN_POSITIONS.size && value.indexOf('-') == 8) readDigits(value) else readPairs(value, '-')
        else -> readPairs(value, separator)
    }
    return bytes.swapGuidFields()
}

/** Sixteen two-digit byte pairs separated by [separator]. */
private fun readPairs(value: String, separator: Char): List<Int> {
    val pairs = value.split(separator)
    if (pairs.size != BYTES) throw UuidNormalizationError.WrongLength()
    if (pairs.any { it.length != 2 }) throw UuidNormalizationError.MisplacedHyphen()
    return pairs.map { it.toInt(16) }
}

/**
 * Every character must be a hexadecimal digit or one of [separators]. Checked first, so a stray character is
 * reported as itself rather than as the length it happens to produce.
 */
private fun requireDigits(value: String, separators: String) {
    for (character in value) {
        val digit = character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F'
        if (!digit && character !in separators) throw UuidNormalizationError.NotHexadecimal()
    }
}

/** Reverse the first three fields - 4, 2 and 2 bytes - between RFC order and Windows GUID order. */
private fun List<Int>.swapGuidFields(): List<Int> =
    subList(0, 4).reversed() + subList(4, 6).reversed() + subList(6, 8).reversed() + subList(8, BYTES)

/** Variant bits `10` and a version from 1 to 8, or the Nil or Max UUID, as RFC 9562 defines them. */
private fun List<Int>.isRfc9562(): Boolean {
    if (all { it == 0 } || all { it == 0xFF }) return true
    val version = this[6] shr 4
    val variant = this[8] and 0xC0
    return variant == 0x80 && version in 1..8
}

private fun List<Int>.hyphenated(): String {
    val hex = joinToString("") { it.hexPair() }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

private fun Int.hexPair(): String = toString(16).padStart(2, '0')

/**
 * A frozen UUID normalization policy.
 *
 * [Hex] accepts any 128-bit value written as a UUID; [Rfc9562] also requires the variant and version bits
 * RFC 9562 defines. Both write the comparable form [UuidForms.Uuid], because the stricter one only refuses
 * more. [guidBytes] adds the mode that reads Windows GUID byte dumps; a policy with that mode only *offers*
 * the form, because the swapped text is the real UUID only if the caller is right about the input.
 */
class UuidPolicy internal constructor(
    internal val requireRfc9562: Boolean,
    internal val guidBytes: Boolean,
    internal val optedIn: Set<ComparableForm> = emptySet(),
) : Policy {

    override val id: String = PolicyId.of(
        buildList {
            add(if (requireRfc9562) Rfc9562Base else HexBase)
            if (guidBytes) add(GuidBytesLink)
            addAll(optedIn.sorted().map { it.link })
        },
    ).dataOrThrow().rendered

    override val version: Int = 1

    override val forms: Set<ComparableForm> = if (guidBytes) optedIn else setOf(UuidForms.Uuid)

    override val offeredForms: Set<ComparableForm> = if (guidBytes) setOf(UuidForms.Uuid) else emptySet()

    /** This policy, reading its input as a Windows GUID byte dump: `…+guid-bytes`. */
    fun guidBytes(): UuidPolicy = UuidPolicy(requireRfc9562, guidBytes = true)

    /** This policy with [forms] opted into, as a [UuidPolicy] so `normalizeUuid` stores the opted-in id. */
    override fun withForms(forms: Set<ComparableForm>): UuidPolicy {
        val refused = forms.firstOrNull { it !in offeredForms }
        require(refused == null) { "$id does not offer the comparable form $refused" }
        return if (forms.isEmpty()) this else UuidPolicy(requireRfc9562, guidBytes, optedIn + forms)
    }

    override fun equals(other: Any?): Boolean = other is UuidPolicy && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = id

    companion object {
        internal val HexBase: PolicyLink = PolicyLink("uuid.hex", LinkKind.Base)
        internal val Rfc9562Base: PolicyLink = PolicyLink("uuid.rfc9562", LinkKind.Base)
        internal val GuidBytesLink: PolicyLink = PolicyLink("bytes.guid", LinkKind.Parameter)

        /** Any 128-bit value written as a UUID. */
        val Hex: UuidPolicy = UuidPolicy(requireRfc9562 = false, guidBytes = false)

        /** A UUID as RFC 9562 defines it: variant bits `10` and a version from 1 to 8, or Nil or Max. */
        val Rfc9562: UuidPolicy = UuidPolicy(requireRfc9562 = true, guidBytes = false)

        internal val all: List<UuidPolicy> = listOf(Hex, Rfc9562, Hex.guidBytes(), Rfc9562.guidBytes())

        internal val links: List<PolicyLink> = listOf(HexBase, Rfc9562Base, GuidBytesLink)
    }
}

/** The canonical UUID plus the policy identity that produced it. */
data class NormalizedUuid(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/** Why a UUID could not be normalized. No message carries any part of the input. */
sealed class UuidNormalizationError(message: String) : Exception(message) {

    /** A character that is not hexadecimal and not part of an accepted notation. */
    class NotHexadecimal : UuidNormalizationError("uuid: not hexadecimal")

    /** The wrong number of digits or byte pairs. */
    class WrongLength : UuidNormalizationError("uuid: wrong length")

    /** Hyphens, or byte-pair separators, anywhere but the positions the notation defines. */
    class MisplacedHyphen : UuidNormalizationError("uuid: misplaced separator")

    /** Unbalanced braces, braces combined with a URN prefix, or wrapper characters inside the value. */
    class MalformedWrapper : UuidNormalizationError("uuid: malformed braces or urn prefix")

    /**
     * Braces or a `urn:uuid:` prefix under the `bytes.guid` mode. Those come only from string-form APIs,
     * whose bytes are already in order, so the value is a UUID string: normalize it with a plain policy.
     */
    class StringFormNotByteDump : UuidNormalizationError("uuid: a uuid string, not a guid byte dump")

    /** Under `uuid.rfc9562`, variant bits other than `10` or a version outside 1 to 8. */
    class NotRfc9562 : UuidNormalizationError("uuid: not an rfc 9562 uuid")
}

/** The comparable form UUID policies write: [Uuid], the lowercase hyphenated string. */
object UuidForms {
    /** The canonical 8-4-4-4-12 lowercase hex form, whichever spelling or byte order was read. */
    val Uuid: ComparableForm = ComparableForm("uuid")
}
