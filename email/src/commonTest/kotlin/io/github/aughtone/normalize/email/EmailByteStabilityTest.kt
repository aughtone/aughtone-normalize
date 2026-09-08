package io.github.aughtone.normalize.email

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * ## If a test in this file fails, you have changed the canonical form. That is the bug.
 *
 * The expectations below are not a description of what the code currently does — they are the
 * published contract of [EmailPolicy.ByteStableV1], and they are correct by definition. A failure
 * here means the implementation moved away from them, never that the expectations are stale.
 *
 * ## Why you must not "fix" this test
 *
 * Callers hash [NormalizedEmail.canonical] into a token and **discard the original value**. They
 * cannot recompute it. If the canonical bytes change for any input, every token already derived
 * from that input becomes unmatchable — silently, with no error, and with no way to find the
 * affected records afterwards, because the inputs are gone.
 *
 * So the damage is done at the moment the bytes change, not at the moment anyone notices. This
 * test is the only thing standing between a plausible-looking improvement and that outcome.
 *
 * ## What to do instead, when the rule genuinely needs to change
 *
 * Mint a NEW policy: a new named constant with its own `id`, or a bumped `version` on the existing
 * one. Add its expectations here as a new corpus alongside these. [EmailPolicy.ByteStableV1] keeps
 * producing exactly what it produces below, forever, for as long as anyone might hold a token
 * derived under it. Two policies coexisting is the supported outcome; one policy quietly changing
 * meaning is not.
 *
 * See `docs/knowledge/specifications/SPEC-0001-normalization-suite.md`. Adding NFC, IDNA/`ToASCII`,
 * or any provider-specific rule to this policy is exactly the change this file exists to prevent.
 *
 * ## Changes that ARE allowed here
 *
 * Adding cases. If you find an input class that is not pinned, pin it — this corpus is a floor,
 * not a ceiling. Deleting or editing an existing expectation is not an allowed change.
 */
class EmailByteStabilityTest {

    private fun canonical(value: String, policy: EmailPolicy): String =
        when (val o = normalizeEmail(value, policy)) {
            is Outcome.Success -> o.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN CORPUS BROKEN: an input that must normalize now fails with " +
                    "${o.exception::class.simpleName}. Do not change the expectation — the " +
                    "implementation regressed.",
            )
        }

    /**
     * Input to canonical output under [EmailPolicy.ByteStableV1]. Every pair is frozen. The comment
     * above each group says which rule it pins, so a later reader can tell what a failure means
     * without reverse-engineering the implementation.
     */
    private val byteStableV1Corpus: List<Pair<String, String>> = listOf(
        // already canonical — the identity case
        "user@example.com" to "user@example.com",

        // ASCII case folding, both sides of the separator
        "User@Example.COM" to "user@example.com",
        "USER@EXAMPLE.COM" to "user@example.com",

        // ASCII whitespace trimmed — exactly space, tab, LF, CR, VT, FF and nothing else
        "  user@example.com  " to "user@example.com",
        "\tuser@example.com\r\n" to "user@example.com",
        "\u000Buser@example.com\u000C" to "user@example.com",

        // RFC 5233 subaddress stripped, from the FIRST plus in the local part
        "user+tag@example.com" to "user@example.com",
        "user+a+b@example.com" to "user@example.com",
        "USER+TAG@EXAMPLE.COM" to "user@example.com",

        // a plus in the DOMAIN is not a subaddress and is left alone
        "user@exa+mple.com" to "user@exa+mple.com",

        // dots are significant EVERYWHERE, gmail included — there are no provider rules
        "u.s.e.r@gmail.com" to "u.s.e.r@gmail.com",
        "First.Last@Example.com" to "first.last@example.com",
        "U.S.E.R+tag@GoogleMail.com" to "u.s.e.r@googlemail.com",

        // non-ASCII is preserved, and non-ASCII case is NOT folded
        "José@Example.com" to "josé@example.com",
        "Ä@example.com" to "Ä@example.com",
        "ÄÖÜ@example.com" to "ÄÖÜ@example.com",

        // a well-formed supplementary character passes through untouched
        "a😀@example.com" to "a😀@example.com",

        // the separator is the LAST one, so a local part may legally contain one
        "A@B@C.com" to "a@b@c.com",

        // Unicode whitespace is NOT trimmed. The trim is ASCII-only on purpose: Char.isWhitespace()
        // is Unicode-version dependent and would drift between platforms and over time.
        "\u3000user@example.com" to "\u3000user@example.com",
        "user@example.com\u00A0" to "user@example.com\u00A0",

        // no IDNA, no ToASCII, no punycode — the domain is raw bytes, ASCII-lowercased only
        "user@café.fr" to "user@café.fr",
        "User@CAFÉ.fr" to "user@cafÉ.fr",
    )

    @Test
    fun byteStableV1ProducesTheFrozenCanonicalBytes() {
        for ((input, expected) in byteStableV1Corpus) {
            assertEquals(
                expected,
                canonical(input, EmailPolicy.ByteStableV1),
                "FROZEN CORPUS BROKEN for input <$input>. The canonical form of ByteStableV1 has " +
                    "changed. Do NOT update this expectation — mint a new policy version instead. " +
                    "See the class KDoc.",
            )
        }
    }

    @Test
    fun byteStableV1CorpusIsIdempotent() {
        // An already-canonical value must survive a second pass unchanged, or a caller that
        // normalizes twice derives a different token from one that normalizes once.
        for ((_, expected) in byteStableV1Corpus) {
            assertEquals(
                expected,
                canonical(expected, EmailPolicy.ByteStableV1),
                "FROZEN CORPUS BROKEN: canonical output <$expected> is not stable under a second " +
                    "pass. Do NOT update this expectation.",
            )
        }
    }

    /**
     * Frozen too. The policy identity is stored beside every derived token and is what a caller
     * matches on later, so renaming it orphans stored data exactly as a byte change would.
     */
    @Test
    fun policyIdentityIsFrozen() {
        assertEquals(
            "email.byte-stable",
            EmailPolicy.ByteStableV1.id,
            "FROZEN: this id is stored beside every derived token. Renaming it orphans them.",
        )
        assertEquals(
            1,
            EmailPolicy.ByteStableV1.version,
            "FROZEN: bump this only by minting a NEW policy, never by editing ByteStableV1.",
        )
        assertEquals("email.lenient", EmailPolicy.Lenient.id, "FROZEN: published policy id.")
        assertEquals(1, EmailPolicy.Lenient.version, "FROZEN: published policy version.")
    }

    /**
     * [EmailPolicy.Lenient] is published too, so its bytes are equally frozen. Smaller corpus
     * because it is not meant for tokenization — but a caller who used it anyway still cannot
     * recompute what they hashed.
     */
    @Test
    fun lenientProducesTheFrozenCanonicalBytes() {
        val corpus = listOf(
            // trim + ASCII-lowercase ONLY — the subaddress is deliberately kept
            "  User+Tag@Gmail.com  " to "user+tag@gmail.com",
            "U.S.E.R@gmail.com" to "u.s.e.r@gmail.com",
            "José+x@Example.com" to "josé+x@example.com",
        )
        for ((input, expected) in corpus) {
            assertEquals(
                expected,
                canonical(input, EmailPolicy.Lenient),
                "FROZEN CORPUS BROKEN for Lenient, input <$input>. Do NOT update this expectation.",
            )
        }
    }

    /**
     * These inputs are refused today. A refusal quietly becoming an acceptance is a lesser problem
     * than a byte change — nothing already stored moves — but it is still a contract change, and
     * the typed reason is part of the published API.
     */
    @Test
    fun refusalsAreFrozen() {
        val refusals = listOf<Pair<String, (Throwable) -> Boolean>>(
            "not-an-email" to { it is EmailNormalizationError.MissingAtSign },
            "@example.com" to { it is EmailNormalizationError.EmptyLocalPart },
            "user@" to { it is EmailNormalizationError.EmptyDomain },
            "+tag@example.com" to { it is EmailNormalizationError.EmptyLocalPart },
            "user\uD83D@example.com" to { it is EmailNormalizationError.UnpairedSurrogate },
            "user\uDE00@example.com" to { it is EmailNormalizationError.UnpairedSurrogate },
        )
        for ((input, isExpected) in refusals) {
            when (val o = normalizeEmail(input, EmailPolicy.ByteStableV1)) {
                is Outcome.Success -> throw AssertionError(
                    "FROZEN: input <$input> must be refused, but normalized to <${o.data.canonical}>.",
                )
                is Outcome.Failure -> assertTrue(
                    isExpected(o.exception),
                    "FROZEN: input <$input> is refused with the wrong typed reason " +
                        "(${o.exception::class.simpleName}). The reason is part of the API.",
                )
            }
        }
    }
}
