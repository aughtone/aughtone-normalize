package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * Half of this file pins what the normalizer **does not** do. Those cases matter more than the positive
 * ones: every one of them is a transform that looks like an improvement, makes more URLs compare equal,
 * and would change which resource some of them address. If one starts failing because the normalizer
 * grew a new rule, the rule is the bug.
 */
class UrlByteStabilityTest {

    private fun canonical(value: String, policy: UrlPolicy = UrlPolicy.Rfc3986U17): String =
        when (val outcome = normalizeUrl(value, policy)) {
            is Outcome.Success -> outcome.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN: <$value> must normalize, failed with ${outcome.exception::class.simpleName}",
            )
        }

    @Test
    fun theCanonicalFormIsFrozen() {
        val corpus = listOf(
            // scheme and host are case-insensitive; the path is not
            "HTTPS://Example.COM/Path" to "https://example.com/Path",
            "https://example.com/path" to "https://example.com/path",
            // an internationalized host becomes its A-label, through the named domain policy
            "https://café.fr/menu" to "https://xn--caf-dma.fr/menu",
            // the default port is redundant; any other port is not
            "https://example.com:443/a" to "https://example.com/a",
            "http://example.com:80/a" to "http://example.com/a",
            "https://example.com:8443/a" to "https://example.com:8443/a",
            // an empty path becomes the root
            "https://example.com" to "https://example.com/",
            // percent-encoding: hex uppercased, unreserved characters decoded
            "https://example.com/a%2fb" to "https://example.com/a%2Fb",
            "https://example.com/%7Euser" to "https://example.com/~user",
            // dot segments resolve, because they describe the same path
            "https://example.com/a/./b/../c" to "https://example.com/a/c",
        )
        for ((input, expected) in corpus) {
            assertEquals(expected, canonical(input), "FROZEN: <$input>")
        }
    }

    @Test
    fun theQueryAndFragmentAreLeftAlone() {
        // The important half. Sorting query parameters would make more URLs compare equal and would
        // change what some of them request - repeated and positional parameters mean different things to
        // different servers - so it is not done, and this pins that it stays not done.
        assertEquals("https://example.com/?b=2&a=1", canonical("https://example.com/?b=2&a=1"))
        assertEquals("https://example.com/?a=1&a=2", canonical("https://example.com/?a=1&a=2"))
        assertEquals("https://example.com/?a=&b=1", canonical("https://example.com/?a=&b=1"))
        assertEquals("https://example.com/?utm_source=x", canonical("https://example.com/?utm_source=x"))
        assertEquals("https://example.com/#section", canonical("https://example.com/#section"))
        assertEquals("https://example.com/?q=1#frag", canonical("https://example.com/?q=1#frag"))
    }

    @Test
    fun theThingsItDeliberatelyDoesNotChangeAreFrozen() {
        // `www.` stays, the scheme is not upgraded, and a trailing slash is neither added nor removed
        // beyond the empty-path rule. Each of these merges URLs that may be different resources.
        assertEquals("https://www.example.com/", canonical("https://www.example.com/"))
        assertEquals("http://example.com/a", canonical("http://example.com/a"))
        assertEquals("https://example.com/a/", canonical("https://example.com/a/"))
        assertEquals("https://example.com/a", canonical("https://example.com/a"))
    }

    @Test
    fun refusalsAreFrozen() {
        assertRefused<UrlNormalizationError.MissingScheme>("example.com/path")
        assertRefused<UrlNormalizationError.MissingScheme>("//example.com/path")
        assertRefused<UrlNormalizationError.UserinfoNotSupported>("https://user:pass@example.com/")
        assertRefused<UrlNormalizationError.UnsupportedHost>("https://[2001:db8::1]/")
        assertRefused<UrlNormalizationError.InvalidPort>("https://example.com:80x/")
        assertRefused<UrlNormalizationError.InvalidHost>("https://exa_mple.com/")
    }

    @Test
    fun anAddressHostIsAcceptedOnlyInItsUnambiguousForm() {
        // Accepted as written, never rewritten.
        assertEquals("https://192.0.2.1/", canonical("https://192.0.2.1/"))
        assertEquals("https://192.0.2.1:8443/a", canonical("https://192.0.2.1:8443/a"))

        // Every other address spelling is refused rather than interpreted: these are read differently by
        // different stacks, and a URL normalizer is the worst place to bury that choice.
        assertRefused<UrlNormalizationError.AmbiguousAddressHost>("https://192.000.002.001/")
        assertRefused<UrlNormalizationError.AmbiguousAddressHost>("https://192.168.0.010/")
        assertRefused<UrlNormalizationError.AmbiguousAddressHost>("https://192.0.2/")
        assertRefused<UrlNormalizationError.AmbiguousAddressHost>("https://3221225985/")
        assertRefused<UrlNormalizationError.AmbiguousAddressHost>("https://192.0.2.256/")
        assertRefused<UrlNormalizationError.AmbiguousAddressHost>("https://0x7f.1/")
        assertRefused<UrlNormalizationError.AmbiguousAddressHost>("https://0xC0.0x00.0x02.0x01/")
    }

    @Test
    fun aNameThatMerelyContainsDigitsIsStillAName() {
        // The address test must not swallow ordinary hostnames, so the boundary is pinned from both
        // sides: a trailing label of digits makes it an address, anything else does not.
        assertEquals("https://example1.com/", canonical("https://example1.com/"))
        assertEquals("https://1.example.com/", canonical("https://1.example.com/"))
        assertEquals("https://192.0.2.example/", canonical("https://192.0.2.example/"))
    }

    @Test
    fun theHostPolicyIsPartOfTheIdentity() {
        // Two URL policies that differ only in how the host is validated are different identities,
        // because they can produce different bytes for the same input.
        assertEquals("url.rfc3986+domain.ascii.u17", UrlPolicy.Rfc3986U17.id)
        assertEquals("url.rfc3986+domain.ascii.u17+lenient", UrlPolicy.Rfc3986U17Lenient.id)

        // An underscore in a host is refused by the strict policy and accepted by the lenient one.
        assertRefused<UrlNormalizationError.InvalidHost>("https://exa_mple.com/")
        assertEquals("https://exa_mple.com/", canonical("https://exa_mple.com/", UrlPolicy.Rfc3986U17Lenient))
    }

    @Test
    fun normalizingIsIdempotent() {
        val inputs = listOf(
            "HTTPS://Example.COM/Path",
            "https://café.fr/menu",
            "https://example.com/a/./b/../c",
            "https://example.com/?b=2&a=1#frag",
        )
        for (input in inputs) {
            val once = canonical(input)
            assertEquals(once, canonical(once), "FROZEN: not idempotent for <$input>")
        }
    }

    private inline fun <reified E : UrlNormalizationError> assertRefused(value: String) {
        when (val outcome = normalizeUrl(value, UrlPolicy.Rfc3986U17)) {
            is Outcome.Success -> throw AssertionError("FROZEN: <$value> must be refused, produced <${outcome.data.canonical}>")
            is Outcome.Failure -> assertTrue(
                outcome.exception is E,
                "FROZEN: <$value> refused for the wrong reason (${outcome.exception::class.simpleName})",
            )
        }
    }
}
