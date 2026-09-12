package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize a URL under [policy], changing only what cannot change which resource is addressed.
 *
 * ## What it does
 *
 * Lowercases the scheme; normalizes the host through this module's UTS-46 policy and renders it as an
 * A-label; removes the default port for the scheme; uppercases percent-encoding and decodes the
 * unreserved characters; resolves `.` and `..` in the path; renders an empty path as `/`.
 *
 * ## What it deliberately does not do, and why that is the design
 *
 * **The query survives byte for byte.** Sorting its parameters is the biggest apparent win here and the
 * worst idea: repeated and positional parameters mean different things to different servers, so sorting
 * changes what the URL asks for - silently, for values that have already been tokenized. Dropping empty
 * parameters, stripping tracking parameters, removing `www.`, upgrading `http` to `https`, and adding or
 * removing trailing slashes are all out for the same reason. The fragment stays too: it is client-side,
 * but it is sometimes the whole address a person means.
 *
 * Treat additions here as regressions rather than improvements. A normalizer that makes more URLs
 * compare equal is only useful if every pair it merges really is the same resource, and beyond the list
 * above the library cannot know that.
 *
 * ## An address host is accepted, never rewritten
 *
 * A host that is an IPv4 address is accepted only in canonical dotted-quad form. Every other spelling -
 * `192.000.002.001`, `0x7f.1`, `3221225985` - is refused rather than interpreted, because those forms
 * mean different things to different stacks and a URL normalizer is the worst place to hide that choice.
 * Normalize such an address with `normalizeIpv4` under a policy you pick, then rebuild the URL.
 *
 * ## The host policy is part of the identity
 *
 * A URL policy names the domain policy it uses, so `url.rfc3986+domain.ascii.u17` and
 * `url.rfc3986+domain.ascii.u17+lenient` are different identities - as they must be, since they can
 * produce different bytes for the same input.
 *
 * ```
 * when (val outcome = normalizeUrl(value, UrlPolicy.Rfc3986U17)) {
 *     is Outcome.Success -> outcome.data.canonical   // "https://xn--caf-dma.fr/menu"
 *     is Outcome.Failure -> outcome.exception        // a typed, value-free UrlNormalizationError
 * }
 * ```
 */
fun normalizeUrl(value: String, policy: UrlPolicy): Outcome<NormalizedUrl> = runOutcome {
    val schemeEnd = value.indexOf(':')
    if (schemeEnd <= 0) throw UrlNormalizationError.MissingScheme()
    val scheme = value.substring(0, schemeEnd).asciiLowercase()
    if (!scheme.isValidScheme()) throw UrlNormalizationError.MissingScheme()

    var rest = value.substring(schemeEnd + 1)
    val canonical = StringBuilder(value.length)
    canonical.append(scheme).append(':')

    if (rest.startsWith("//")) {
        rest = rest.substring(2)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        rest = rest.substring(authorityEnd)

        // Credential material has no place in a canonical form, or in an error, or in a log.
        if (authority.contains('@')) throw UrlNormalizationError.UserinfoNotSupported()
        // An IPv6 literal is a different kind of host with its own canonical form; this module does not
        // carry that normalizer, and guessing at one would produce a second spelling of the same address.
        if (authority.startsWith('[')) throw UrlNormalizationError.UnsupportedHost()

        val portSeparator = authority.lastIndexOf(':')
        val hostText = if (portSeparator >= 0) authority.substring(0, portSeparator) else authority
        val portText = if (portSeparator >= 0) authority.substring(portSeparator + 1) else ""

        // A host that is an address rather than a name takes a different path: this normalizer accepts
        // the one unambiguous spelling and refuses the rest, because rewriting an address means choosing
        // between readings that disagree, and burying that choice inside a URL is the worst place for it.
        val host = if (hostText.looksLikeAddress()) {
            if (!hostText.isCanonicalDottedQuad()) throw UrlNormalizationError.AmbiguousAddressHost()
            hostText
        } else {
            when (val outcome = normalizeDomain(hostText, policy.domainPolicy)) {
                is Outcome.Success -> outcome.data.canonical
                is Outcome.Failure -> throw UrlNormalizationError.InvalidHost()
            }
        }
        canonical.append("//").append(host)

        if (portText.isNotEmpty()) {
            if (portText.any { it !in '0'..'9' }) throw UrlNormalizationError.InvalidPort()
            val port = portText.trimStart('0').ifEmpty { "0" }
            if (port != defaultPortFor(scheme)) canonical.append(':').append(port)
        }

        val pathEnd = rest.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val path = rest.substring(0, pathEnd)
        rest = rest.substring(pathEnd)
        canonical.append(if (path.isEmpty()) "/" else removeDotSegments(path).normalizePercentEncoding())
    } else {
        val pathEnd = rest.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        canonical.append(rest.substring(0, pathEnd).normalizePercentEncoding())
        rest = rest.substring(pathEnd)
    }

    if (rest.startsWith('?')) {
        val fragmentStart = rest.indexOf('#').let { if (it < 0) rest.length else it }
        // Preserved as written, apart from the percent-encoding rule: order and emptiness are meaning.
        canonical.append(rest.substring(0, fragmentStart).normalizePercentEncoding())
        rest = rest.substring(fragmentStart)
    }
    if (rest.startsWith('#')) canonical.append(rest.normalizePercentEncoding())

    NormalizedUrl(canonical = canonical.toString(), policyId = policy.id, policyVersion = policy.version)
}

/**
 * Whether a host is meant as an address rather than a name.
 *
 * The trigger is the WHATWG URL one - a last label that is all digits - plus any label announcing
 * hexadecimal. That catches `192.000.002.001`, `3221225985` and `0x7f.1` while leaving ordinary names
 * that merely contain digits, like `example1.com` or `1.example.com`, on the hostname path where they
 * belong.
 */
private fun String.looksLikeAddress(): Boolean {
    if (isEmpty()) return false
    val labels = split('.')
    if (labels.any { it.startsWith("0x") || it.startsWith("0X") }) return true
    return labels.last().isNotEmpty() && labels.last().all { it in '0'..'9' }
}

/**
 * Four decimal octets, no leading zeros: the only address spelling this normalizer accepts.
 *
 * This validates rather than normalizes, which is why it does not reach for `:quodlibet`'s IPv4
 * normalizer and why `:ubilibet` gains no dependency. A URL is never rewritten to change which host it
 * names; a caller holding another spelling normalizes it themselves, under a policy they choose.
 */
private fun String.isCanonicalDottedQuad(): Boolean {
    val parts = split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
            !(part.length > 1 && part[0] == '0') && part.toInt() <= 255
    }
}

/** The port a scheme uses by default, which is therefore redundant in a canonical form. */
private fun defaultPortFor(scheme: String): String? = when (scheme) {
    "http", "ws" -> "80"
    "https", "wss" -> "443"
    else -> null
}

private fun String.isValidScheme(): Boolean =
    isNotEmpty() && this[0] in 'a'..'z' && all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }

private fun String.asciiLowercase(): String {
    if (none { it in 'A'..'Z' }) return this
    val builder = StringBuilder(length)
    for (character in this) builder.append(if (character in 'A'..'Z') character + 32 else character)
    return builder.toString()
}

/**
 * Percent-encoding, normalized: hexadecimal digits uppercased, and the unreserved characters decoded.
 * Both are defined by RFC 3986 as producing an equivalent URL, so neither can change what is addressed.
 */
private fun String.normalizePercentEncoding(): String {
    if (!contains('%')) return this
    val builder = StringBuilder(length)
    var index = 0
    while (index < length) {
        val character = this[index]
        if (character != '%' || index + 2 >= length) {
            builder.append(character)
            index++
            continue
        }
        val high = this[index + 1].hexValue()
        val low = this[index + 2].hexValue()
        if (high == null || low == null) {
            builder.append(character)
            index++
            continue
        }
        val decoded = high * 16 + low
        if (decoded.toChar().isUnreserved()) {
            builder.append(decoded.toChar())
        } else {
            builder.append('%').append(HEX[high]).append(HEX[low])
        }
        index += 3
    }
    return builder.toString()
}

private const val HEX = "0123456789ABCDEF"

private fun Char.hexValue(): Int? = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> null
}

private fun Char.isUnreserved(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '.' || this == '_' || this == '~'

/** RFC 3986's remove_dot_segments, which is defined as preserving what the path refers to. */
private fun removeDotSegments(path: String): String {
    val output = mutableListOf<String>()
    val absolute = path.startsWith('/')
    val trailingSlash = path.endsWith('/') || path.endsWith("/.") || path.endsWith("/..")
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (output.isNotEmpty()) output.removeAt(output.size - 1)
            else -> output += segment
        }
    }
    val joined = output.joinToString("/")
    return buildString {
        if (absolute) append('/')
        append(joined)
        if (trailingSlash && joined.isNotEmpty()) append('/')
    }
}

/**
 * A frozen URL normalization policy, naming the host policy it uses.
 *
 * The URL rules themselves are fixed - there is nothing here to relax that would not change meaning - so
 * the only difference between these two is how strictly the host is validated.
 */
class UrlPolicy internal constructor(
    override val id: String,
    override val version: Int,
    internal val domainPolicy: DomainPolicy,
) : Policy {

    override fun toString(): String = id

    companion object {
        /** The base link both policies are built on. */
        internal val Base: PolicyLink = PolicyLink("url.rfc3986", LinkKind.Base)

        /** Strict host validation: every UTS-46 check applies to the host. */
        val Rfc3986U17: UrlPolicy = UrlPolicy(
            id = chainOf(Base, DomainPolicy.Base),
            version = 1,
            domainPolicy = DomainPolicy.AsciiU17,
        )

        /** The host validated under the lenient domain policy; the URL rules are unchanged. */
        val Rfc3986U17Lenient: UrlPolicy = UrlPolicy(
            id = chainOf(Base, DomainPolicy.Base, PolicyLink.Lenient),
            version = 1,
            domainPolicy = DomainPolicy.AsciiU17Lenient,
        )

        internal val all: List<UrlPolicy> = listOf(Rfc3986U17, Rfc3986U17Lenient)

        private fun chainOf(vararg links: PolicyLink): String =
            when (val outcome = PolicyId.of(links.toList())) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            }
    }
}

/** The canonical URL plus the policy identity that produced it. */
data class NormalizedUrl(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/**
 * Why a URL could not be normalized. No message carries any part of the input - a URL query routinely
 * holds personal data, and sometimes credentials.
 */
sealed class UrlNormalizationError(message: String) : Exception(message) {

    /** No scheme, or one that is not a scheme. A relative reference has no canonical form on its own. */
    class MissingScheme : UrlNormalizationError("url: missing or invalid scheme")

    /** The host is not a hostname this suite can normalize. */
    class InvalidHost : UrlNormalizationError("url: invalid host")

    /** An IPv6 literal or other non-hostname authority, which this module does not normalize. */
    class UnsupportedHost : UrlNormalizationError("url: unsupported host form")

    /**
     * A host that is an address in a spelling this normalizer will not rewrite: leading zeros, a
     * hexadecimal or octal part, fewer than four parts, or a bare integer.
     *
     * Those spellings are read differently by different stacks, so rewriting one here would bury a
     * choice of interpretation inside a URL. **Normalize the address deliberately instead** - with
     * `normalizeIpv4` under `ipv4.dotted-quad` or `ipv4.inet-aton` - and rebuild the URL from the
     * result, so the identity records which reading was applied.
     */
    class AmbiguousAddressHost : UrlNormalizationError("url: ambiguous address host")

    /** A port that is not a number. */
    class InvalidPort : UrlNormalizationError("url: invalid port")

    /** Userinfo in the authority: credential material, which is refused rather than carried. */
    class UserinfoNotSupported : UrlNormalizationError("url: userinfo is not supported")
}
