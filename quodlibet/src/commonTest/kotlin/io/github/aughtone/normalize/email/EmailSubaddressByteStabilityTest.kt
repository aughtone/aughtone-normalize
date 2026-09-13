package io.github.aughtone.normalize.email

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * The contract of [EmailSubaddressPolicy.ByteStableV1]: the subaddress a caller tokenizes, and the proof
 * that the mailbox beside it is the one [normalizeEmail] produces under [EmailPolicy.ByteStableV1]. A
 * failure here means the implementation moved - a tag token or a mailbox token derived today would no
 * longer match one derived before - never that the expectations are stale. A rule change is a new policy
 * version, not an edit. See `EmailByteStabilityTest` and DOC-0001.
 *
 * Adding cases is allowed. Editing or deleting an expectation is not.
 */
class EmailSubaddressByteStabilityTest {

    private val policy = EmailSubaddressPolicy.ByteStableV1

    private fun read(value: String): NormalizedEmailWithSubaddress {
        val outcome = normalizeEmailWithSubaddress(value, policy)
        assertTrue(outcome is Outcome.Success, "FROZEN CORPUS BROKEN: <$value> must normalize. Do NOT update this expectation.")
        return outcome.data
    }

    /** Input to the frozen subaddress, `null` where the address has none. */
    private val corpus: List<Pair<String, String?>> = listOf(
        // no plus: no subaddress, not an empty one
        "user@example.com" to null,

        // a plus with nothing after it: the empty subaddress, which is what the address says
        "user+@example.com" to "",

        // an ordinary tag
        "user+tag@example.com" to "tag",

        // everything after the FIRST plus, further pluses included
        "user+a+b@example.com" to "a+b",

        // ASCII-lowercased like the rest of the local part
        "USER+TAG@EXAMPLE.COM" to "tag",

        // ASCII whitespace trimmed from the address, not from inside the tag
        "  user+Tag@example.com\t\r\n" to "tag",

        // a plus in the DOMAIN is not a subaddress
        "user@exa+mple.com" to null,

        // the separator is the LAST @, so the local part, and its tag, may contain one
        "a+b@c@example.com" to "b@c",

        // non-ASCII is preserved and its case is not folded
        "user+Äé@example.com" to "Äé",
    )

    @Test
    fun subaddressesAreTheFrozenBytes() {
        for ((input, expected) in corpus) {
            assertEquals(
                expected,
                read(input).subaddress?.canonical,
                "FROZEN CORPUS BROKEN for input <$input>. Do NOT update this expectation.",
            )
        }
    }

    @Test
    fun theMailboxIsExactlyTheByteStableV1Normalization() {
        val inputs = corpus.map { it.first } + listOf(
            "u.s.e.r@gmail.com",
            "user@example.com",
            "\u3000user+x@example.com",
            "user+x@café.fr",
        )
        for (input in inputs) {
            val direct = normalizeEmail(input, EmailPolicy.ByteStableV1)
            assertTrue(direct is Outcome.Success, "<$input>")
            assertEquals(direct.data, read(input).mailbox, "mailbox for <$input> differs from normalizeEmail")
        }
    }

    @Test
    fun theSubaddressCarriesTheFrozenIdentity() {
        assertEquals("email.subaddress", policy.id)
        assertEquals(1, policy.version)
        val tag = read("user+tag@example.com").subaddress
        assertEquals("email.subaddress", tag?.policyId)
        assertEquals(1, tag?.policyVersion)
        assertEquals("email.byte-stable", read("user+tag@example.com").mailbox.policyId)
    }

    @Test
    fun refusalsAreThoseOfNormalizeEmail() {
        val refused = listOf(
            "userexample.com" to EmailNormalizationError.MissingAtSign::class,
            "user@" to EmailNormalizationError.EmptyDomain::class,
            "@example.com" to EmailNormalizationError.EmptyLocalPart::class,
            "+tag@example.com" to EmailNormalizationError.EmptyLocalPart::class,
            "user\ud800@example.com" to EmailNormalizationError.UnpairedSurrogate::class,
        )
        for ((input, expected) in refused) {
            val outcome = normalizeEmailWithSubaddress(input, policy)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused")
            assertEquals(expected, outcome.exception::class, "<$input>")
            val direct = normalizeEmail(input, EmailPolicy.ByteStableV1)
            assertTrue(direct is Outcome.Failure)
            assertEquals(direct.exception::class, outcome.exception::class, "<$input> refused differently")
            assertFalse(outcome.exception.message.orEmpty().contains("tag"), "<$input> error carries the input")
        }
    }

    @Test
    fun anAddressWithoutASubaddressHasNone() {
        assertNull(read("user@example.com").subaddress)
        assertIs<NormalizedEmailSubaddress>(read("user+@example.com").subaddress)
    }
}
