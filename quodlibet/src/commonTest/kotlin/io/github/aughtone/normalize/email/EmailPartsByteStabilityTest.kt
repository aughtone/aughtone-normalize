package io.github.aughtone.normalize.email

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * What `normalizeEmailParts` writes for the local part and the domain. Both are tokenized by callers, so
 * both are frozen: a change here silently re-keys every token derived under the old bytes.
 *
 * Adding cases is allowed. Editing one is a new policy version, not an edit.
 */
class EmailPartsByteStabilityTest {

    private fun read(value: String, policy: EmailPolicy = EmailPolicy.Address): NormalizedEmailParts {
        val outcome = normalizeEmailParts(value, policy)
        assertTrue(outcome is Outcome.Success, "FROZEN CORPUS BROKEN: <$value> must normalize, got $outcome")
        return outcome.data
    }

    /** Input to local part, domain and subaddress, read under the anchor. */
    private val corpus: List<Array<String?>> = listOf(
        arrayOf("user@example.com", "user", "example.com", null),
        arrayOf("User@Example.COM", "user", "example.com", null),
        arrayOf("  user@example.com  ", "user", "example.com", null),
        arrayOf("user+work@example.com", "user+work", "example.com", "work"),
        arrayOf("User+Work@EXAMPLE.COM", "user+work", "example.com", "work"),
        arrayOf("user+a+b@example.com", "user+a+b", "example.com", "a+b"),
        // The address says the tag is empty, so the tag is empty - not absent.
        arrayOf("user+@example.com", "user+", "example.com", ""),
        arrayOf("+tag@example.com", "+tag", "example.com", "tag"),
        // The last `@` is the boundary, so a local part may contain one.
        arrayOf("user@name@example.com", "user@name", "example.com", null),
        arrayOf("\"a b\"@example.com", "\"a b\"", "example.com", null),
        arrayOf("a@b", "a", "b", null),
        arrayOf("user@sub.example.co.uk", "user", "sub.example.co.uk", null),
        // Raw bytes, ASCII-lowercased: the ASCII letters fold and nothing else is touched. A domain here
        // is NOT put through ToASCII - that is `domain.ascii.u17`, a different identity.
        arrayOf("user@Bücher.Example", "user", "bücher.example", null),
        arrayOf("user@XN--BCHER-KVA.example", "user", "xn--bcher-kva.example", null),
    )

    @Test
    fun theLocalPartAndDomainAreTheFrozenBytes() {
        for ((input, local, domain, subaddress) in corpus.map { it.toList() }) {
            val parts = read(input!!)
            assertEquals(local, parts.local.canonical, "FROZEN CORPUS BROKEN for <$input>: local part")
            assertEquals(domain, parts.domain.canonical, "FROZEN CORPUS BROKEN for <$input>: domain")
            assertEquals(subaddress, parts.subaddress?.canonical, "FROZEN CORPUS BROKEN for <$input>: subaddress")
        }
    }

    @Test
    fun theIdentitiesAreFrozen() {
        assertEquals("email.local", EmailLocalPolicy.V1.id)
        assertEquals(1, EmailLocalPolicy.V1.version)
        assertEquals("email.domain", EmailDomainPolicy.V1.id)
        assertEquals(1, EmailDomainPolicy.V1.version)

        val parts = read("user+work@example.com")
        assertEquals("email.local", parts.local.policyId)
        assertEquals(1, parts.local.policyVersion)
        assertEquals("email.domain", parts.domain.policyId)
        assertEquals(1, parts.domain.policyVersion)
        assertEquals("email.subaddress", parts.subaddress?.policyId)
        assertEquals("email", parts.mailbox.policyId)
    }

    @Test
    fun neitherPieceDeclaresAComparableForm() {
        // A local part compares with nothing. A domain here is raw bytes and `domain.ascii.u17` is a
        // ToASCII form under a Unicode release: they agree on ASCII input, which is most of it, and differ
        // exactly where a silent mismatch would cost the most.
        assertEquals(emptySet(), EmailLocalPolicy.V1.forms)
        assertEquals(emptySet(), EmailDomainPolicy.V1.forms)
    }
}
