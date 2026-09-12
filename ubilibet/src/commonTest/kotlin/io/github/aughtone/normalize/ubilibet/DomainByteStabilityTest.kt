package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * ## If a test here fails, the canonical form changed. That is the bug.
 *
 * The conformance suite proves this module implements UTS-46. This file proves it keeps producing the
 * same bytes as the day it shipped, which is a different promise: a caller hashes the A-label and
 * discards the input, so a byte that moves makes every token already derived from it unmatchable.
 *
 * ## What to do instead
 *
 * A new Unicode release mints a NEW policy - `AsciiU18` - with its own corpus alongside this one.
 * `AsciiU17` keeps producing exactly what it produces below, for as long as anyone holds a value
 * derived under it.
 *
 * Adding cases is allowed. Editing or deleting one is not.
 */
class DomainByteStabilityTest {

    private fun canonical(value: String, policy: DomainPolicy): String =
        when (val outcome = normalizeDomain(value, policy)) {
            is Outcome.Success -> outcome.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN CORPUS BROKEN: <$value> must normalize, failed with " +
                    "${outcome.exception::class.simpleName}. Do not change the expectation.",
            )
        }

    private inline fun <reified E : DomainNormalizationError> assertRefused(value: String, policy: DomainPolicy) {
        when (val outcome = normalizeDomain(value, policy)) {
            is Outcome.Success -> throw AssertionError(
                "FROZEN: <$value> must be refused, produced <${outcome.data.canonical}>",
            )

            is Outcome.Failure -> assertTrue(
                outcome.exception is E,
                "FROZEN: <$value> refused for the wrong reason (${outcome.exception::class.simpleName})",
            )
        }
    }

    @Test
    fun theStrictPolicyIsFrozen() {
        val corpus = listOf(
            // ASCII passes through, and case is folded by the mapping table rather than by us
            "example.com" to "example.com",
            "EXAMPLE.COM" to "example.com",
            "ExAmPlE.CoM" to "example.com",
            // an internationalized label becomes its A-label
            "café.fr" to "xn--caf-dma.fr",
            "münchen.de" to "xn--mnchen-3ya.de",
            // one script per row: Cyrillic, Greek, Arabic, Han
            "пример.рф" to "xn--e1afmkfd.xn--p1ai",
            "παράδειγμα.ελ" to "xn--hxajbheg2az3al.xn--qxam",
            "مثال.السعودية" to "xn--mgbh0fb.xn--mgberp4a5d4ar",
            "例え.テスト" to "xn--r8jz45g.xn--zckzah",
            // an A-label that arrives already encoded round-trips to itself
            "xn--caf-dma.fr" to "xn--caf-dma.fr",
            // a label that needs NFC: e + combining acute becomes the composed form, then encodes
            "café.fr" to "xn--caf-dma.fr",
            // the deviation characters are not mapped under nontransitional processing
            "faß.de" to "xn--fa-hia.de",
            // a 63-octet label is the boundary and is allowed
            ("a".repeat(63) + ".com") to ("a".repeat(63) + ".com"),
        )
        for ((input, expected) in corpus) {
            assertEquals(expected, canonical(input, DomainPolicy.AsciiU17), "FROZEN: <$input>")
        }
    }

    @Test
    fun theStrictPolicyRefusalsAreFrozen() {
        // A refusal is part of the published contract too: a caller stores a token only for what was
        // accepted, so an input that starts being accepted changes what the policy means.
        assertRefused<DomainNormalizationError.LabelTooLong>("a".repeat(64) + ".com", DomainPolicy.AsciiU17)
        assertRefused<DomainNormalizationError.EmptyLabel>("example..com", DomainPolicy.AsciiU17)
        assertRefused<DomainNormalizationError.EmptyLabel>("example.com.", DomainPolicy.AsciiU17)
        assertRefused<DomainNormalizationError.HyphenRule>("-example.com", DomainPolicy.AsciiU17)
        assertRefused<DomainNormalizationError.HyphenRule>("ab--cd.com", DomainPolicy.AsciiU17)
        assertRefused<DomainNormalizationError.DisallowedCodePoint>("exa_mple.com", DomainPolicy.AsciiU17)
        assertRefused<DomainNormalizationError.PunycodeDecodeFailed>("xn--zzzzzz.com", DomainPolicy.AsciiU17)
        // Built from a code unit rather than a literal: a lone surrogate in source is corrupted to
        // U+FFFD by the JS bundler, which would leave this expectation passing while testing nothing
        // on that target. The expectation is unchanged - this input is refused.
        assertRefused<DomainNormalizationError.UnpairedSurrogate>("exa" + Char(0xD800) + "mple.com", DomainPolicy.AsciiU17)
        // a name longer than 253 octets, built from legal 63-octet labels
        assertRefused<DomainNormalizationError.NameTooLong>(
            List(4) { "a".repeat(63) }.joinToString("."),
            DomainPolicy.AsciiU17,
        )
    }

    @Test
    fun theLenientPolicyIsFrozen() {
        // It relaxes hyphen placement, the STD3 character set and DNS length - and nothing else.
        assertEquals("ab--cd.com", canonical("ab--cd.com", DomainPolicy.AsciiU17Lenient))
        assertEquals("-example.com", canonical("-example.com", DomainPolicy.AsciiU17Lenient))
        assertEquals("exa_mple.com", canonical("exa_mple.com", DomainPolicy.AsciiU17Lenient))
        assertEquals("example.com.", canonical("example.com.", DomainPolicy.AsciiU17Lenient))
        assertEquals("a".repeat(64) + ".com", canonical("a".repeat(64) + ".com", DomainPolicy.AsciiU17Lenient))
        // and it still produces the same A-label for an ordinary internationalized name
        assertEquals("xn--caf-dma.fr", canonical("café.fr", DomainPolicy.AsciiU17Lenient))
    }

    @Test
    fun bothPoliciesKeepTheRulesThatPreventAnUnrepresentableName() {
        // Bidi and joiner rules are not relaxed by leniency: a name that displays in one order and
        // resolves in another, or that hides an invisible character, is not a name at all.
        for (policy in DomainPolicy.all) {
            // an RTL label ending in a Latin letter breaks the bidi rule
            assertRefused<DomainNormalizationError.BidiRule>("א1a.com", policy)
            // a zero-width joiner with no virama before it
            assertRefused<DomainNormalizationError.JoinerRule>("ab‍cd.com", policy)
        }
    }

    @Test
    fun normalizingIsIdempotent() {
        val inputs = listOf("example.com", "café.fr", "xn--caf-dma.fr", "пример.рф", "faß.de")
        for (policy in DomainPolicy.all) {
            for (input in inputs) {
                val once = canonical(input, policy)
                assertEquals(once, canonical(once, policy), "FROZEN: ${policy.id} is not idempotent for <$input>")
            }
        }
    }

    @Test
    fun policyIdentitiesAreFrozen() {
        assertEquals("domain.ascii.u17", DomainPolicy.AsciiU17.id)
        assertEquals("domain.ascii.u17+lenient", DomainPolicy.AsciiU17Lenient.id)
        assertEquals(1, DomainPolicy.AsciiU17.version)
        assertEquals(1, DomainPolicy.AsciiU17Lenient.version)
    }
}
