package io.github.aughtone.normalize.quodlibet

/**
 * The one spelling of a network, shared by every IP network normalizer in this module.
 *
 * The canonical address exactly as its address normalizer renders it, then `/`, then the prefix length
 * in decimal with no leading zeros: `192.0.2.0/24`, `2001:db8::/32`. Block derivation and CIDR input both
 * go through [of], so the same network can never be written two ways - a derived block and a range read
 * from a list are the same text whenever they are the same network.
 */
internal object NetworkForm {

    /** Render a network from its already-canonical [address] and [prefixLength]. */
    fun of(address: String, prefixLength: Int): String = "$address/$prefixLength"

    /**
     * Split a CIDR string into its address text and prefix length, refusing a missing, empty, non-decimal,
     * zero-padded or out-of-range prefix. The error constructors are the caller's, so each address family
     * reports refusals in its own typed error.
     */
    fun split(
        value: String,
        maxPrefix: Int,
        missing: () -> Throwable,
        malformed: () -> Throwable,
        outOfRange: () -> Throwable,
    ): Pair<String, Int> {
        val slash = value.lastIndexOf('/')
        if (slash < 0) throw missing()
        val address = value.substring(0, slash)
        val prefix = value.substring(slash + 1)
        if (prefix.isEmpty() || prefix.any { it !in '0'..'9' }) throw malformed()
        // `/024` would parse to 24, and accepting it would give one network two spellings.
        if (prefix.length > 1 && prefix[0] == '0') throw malformed()
        if (prefix.length > 3) throw outOfRange()
        val length = prefix.toInt()
        if (length > maxPrefix) throw outOfRange()
        return address to length
    }
}
