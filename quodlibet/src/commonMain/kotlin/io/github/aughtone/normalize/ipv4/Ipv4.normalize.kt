package io.github.aughtone.normalize.ipv4

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize an IPv4 address to dotted-quad decimal under [policy].
 *
 * ## The problem this solves is disagreement, not spelling
 *
 * One IPv4 address has more legal spellings than anything else in this suite: `192.0.2.1`,
 * `192.000.002.001`, `0xC0.0x00.0x02.0x01`, the 32-bit integer `3221225985`, and the three-part
 * `192.0.2`. The trouble is that stacks disagree about them. `192.168.0.010` is 8 under `inet_aton` and
 * glibc, 10 under a plain decimal reading, refused outright by Go and by Python, and accepted by
 * browsers through the WHATWG URL rules.
 *
 * So there is no single correct interpretation to freeze, and picking one silently would be the worst
 * outcome: a token naming one host while the eventual connection goes to another, with nothing to
 * reveal the mismatch. This normalizer therefore accepts only the spelling every stack agrees on, and
 * refuses the rest rather than guessing which reader the caller had in mind.
 *
 * - [Ipv4Policy.DottedQuad] accepts only the unambiguous form and refuses the rest.
 * - [Ipv4Policy.InetAton] applies the classic rules deliberately, so data from a system that parses that
 *   way can be matched - and its id records that an interpretation took place.
 *
 * A value normalized under one never matches a value normalized under the other. That is the point: they
 * are claims about different things, and the suite settles disagreement by identity rather than by
 * choosing a winner on the caller's behalf.
 *
 * ```
 * normalizeIpv4("192.0.2.1", Ipv4Policy.DottedQuad)          // "192.0.2.1"
 * normalizeIpv4("192.0.2.01", Ipv4Policy.DottedQuad)         // refused: ambiguous
 * normalizeIpv4("0xC0.0x00.0x02.0x01", Ipv4Policy.InetAton)  // "192.0.2.1"
 * ```
 */
fun normalizeIpv4(value: String, policy: Ipv4Policy): Outcome<NormalizedIpv4> = runOutcome {
    if (value.isEmpty()) throw Ipv4NormalizationError.MalformedAddress()
    // Not trimmed: an address is not a sentence, and trimming is a rule that invites more of them.
    val parts = value.split('.')
    if (parts.isEmpty() || parts.size > 4) throw Ipv4NormalizationError.MalformedAddress()
    // Fewer than four parts is a shorthand: the interpreting policy spreads the final part across the
    // bytes the earlier ones left, and the strict one will not guess.
    if (parts.size < 4 && !policy.interpretsShorthand) throw Ipv4NormalizationError.ShorthandNotSupported()

    val values = parts.map { part -> policy.readPart(part) }
    val leading = values.dropLast(1)
    if (leading.any { it > 0xFF }) throw Ipv4NormalizationError.PartOutOfRange()

    // The last part absorbs whatever bytes the earlier ones did not: `192.0.2` is 192.0.0.2, and a bare
    // integer is the whole address.
    val trailingBytes = 4 - leading.size
    val trailing = values.last()
    if (trailing > (1L shl (8 * trailingBytes)) - 1) throw Ipv4NormalizationError.PartOutOfRange()

    var address = 0L
    for (part in leading) address = (address shl 8) or part
    address = (address shl (8 * trailingBytes)) or trailing

    val canonical = "${(address shr 24) and 0xFF}.${(address shr 16) and 0xFF}." +
        "${(address shr 8) and 0xFF}.${address and 0xFF}"
    NormalizedIpv4(canonical = canonical, policyId = policy.id, policyVersion = policy.version)
}

/**
 * A frozen IPv4 normalization policy.
 *
 * Neither policy has a lenient variant, and [InetAton] is deliberately not named one. Leniency in this
 * suite relaxes a rule while keeping the value's meaning; reading `010` as 8 changes the value, so it is
 * a different rule-set with its own identity rather than a loosened version of the other.
 */
class Ipv4Policy internal constructor(
    override val id: String,
    override val version: Int,
    internal val interpretsShorthand: Boolean,
) : Policy {

    /** Read one part under this policy's rules, or refuse it. */
    internal fun readPart(part: String): Long {
        if (part.isEmpty()) throw Ipv4NormalizationError.MalformedAddress()

        if (interpretsShorthand) {
            // Each part carries its own radix, which is what makes `192.0.2.010` three decimal parts and
            // one octal one. That is genuinely odd, and it is what `inet_aton` does - a tidier rule would
            // model no real parser, which would defeat the only reason this policy exists.
            return when {
                part.startsWith("0x") || part.startsWith("0X") -> part.substring(2).toRadix(16)
                part.length > 1 && part[0] == '0' -> part.substring(1).toRadix(8)
                else -> part.toRadix(10)
            }
        }

        // Order matters: a hexadecimal part like `0xC0` is malformed decimal, not an ambiguous zero, and
        // saying so is more useful than reporting the `0` it happens to start with.
        if (part.any { it !in '0'..'9' }) throw Ipv4NormalizationError.MalformedAddress()
        // A leading zero has meant both decimal and octal, in software people still run. It gets its own
        // refusal rather than being folded into "malformed", because it is the case that has actually
        // caused security bugs.
        if (part.length > 1 && part[0] == '0') throw Ipv4NormalizationError.AmbiguousLeadingZero()
        if (part.length > 3) throw Ipv4NormalizationError.PartOutOfRange()
        return part.toLong().also { if (it > 0xFF) throw Ipv4NormalizationError.PartOutOfRange() }
    }

    override fun toString(): String = id

    companion object {
        /**
         * Four decimal octets, no leading zeros: the one spelling every stack agrees on. Anything else
         * is refused rather than interpreted, so a token minted here cannot mean two different hosts.
         */
        val DottedQuad: Ipv4Policy = Ipv4Policy(
            id = chainOf(PolicyLink("ipv4.dotted-quad", LinkKind.Base)),
            version = 1,
            interpretsShorthand = false,
        )

        /**
         * The classic `inet_aton` rules, applied deliberately: one to four parts with the last absorbing
         * the remaining bytes, `0x` for hexadecimal, a leading zero for octal, each part carrying its own
         * radix.
         *
         * **Its output is an interpretation, and the id says so.** Other stacks read the same input
         * differently - Go and Python refuse the octal forms outright - so this policy is a statement
         * that these particular rules were applied. Values normalized here never match values normalized
         * under [DottedQuad].
         *
         * **Do not reach for this to accept more input.** Canonicalizing an ambiguous address turns an
         * attacker's choice of spelling into a token that may name a host the caller never intended,
         * which is how address-based filters get bypassed. Use it when the data you are matching *came
         * from* a system with these semantics, not to be permissive at the edge.
         */
        val InetAton: Ipv4Policy = Ipv4Policy(
            id = chainOf(PolicyLink("ipv4.inet-aton", LinkKind.Base)),
            version = 1,
            interpretsShorthand = true,
        )

        /** The base links these policies are built on, published for resolution. */
        internal val links: List<PolicyLink> = listOf(
            PolicyLink("ipv4.dotted-quad", LinkKind.Base),
            PolicyLink("ipv4.inet-aton", LinkKind.Base),
        )

        internal val all: List<Ipv4Policy> = listOf(DottedQuad, InetAton)

        private fun chainOf(vararg links: PolicyLink): String =
            when (val outcome = PolicyId.of(links.toList())) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            }
    }
}

/** Parse a part in the given radix, refusing anything that radix does not allow. */
private fun String.toRadix(radix: Int): Long {
    if (isEmpty()) return 0
    var result = 0L
    for (character in this) {
        val digit = when (character) {
            in '0'..'9' -> character - '0'
            in 'a'..'f' -> character - 'a' + 10
            in 'A'..'F' -> character - 'A' + 10
            else -> throw Ipv4NormalizationError.MalformedAddress()
        }
        if (digit >= radix) throw Ipv4NormalizationError.MalformedAddress()
        result = result * radix + digit
        if (result > 0xFFFFFFFFL) throw Ipv4NormalizationError.PartOutOfRange()
    }
    return result
}

/** The canonical dotted-quad address plus the policy identity that produced it. */
data class NormalizedIpv4(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/** Why an address could not be normalized. No message carries any part of the input. */
sealed class Ipv4NormalizationError(message: String) : Exception(message) {

    /** Not four parts of digits: wrong shape, an empty part, or a character that is not a digit. */
    class MalformedAddress : Ipv4NormalizationError("ipv4: malformed address")

    /** A part larger than the bytes it may occupy. */
    class PartOutOfRange : Ipv4NormalizationError("ipv4: part out of range")

    /**
     * A leading zero, under the policy that refuses to guess. `010` has meant both eight and ten in
     * software people still run, and picking one would produce a token for a host the caller did not
     * name.
     */
    class AmbiguousLeadingZero : Ipv4NormalizationError("ipv4: ambiguous leading zero")

    /** A shorthand form: fewer than four parts, a hexadecimal part, or a bare integer. */
    class ShorthandNotSupported : Ipv4NormalizationError("ipv4: shorthand form not supported")
}
