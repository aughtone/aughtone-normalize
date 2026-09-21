package io.github.aughtone.normalize.email

import io.github.aughtone.normalize.ubilibet.DomainPolicy
import io.github.aughtone.normalize.ubilibet.normalizeDomain
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

    private val domainPolicy = DomainPolicy.AsciiU17

    private fun read(value: String, policy: EmailPolicy = EmailPolicy.Address): NormalizedEmailParts {
        val outcome = normalizeEmailParts(value, policy, domainPolicy)
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
        // The domain is normalized as a domain, so the two spellings of one name give ONE token - which
        // is the whole point of not having a second domain identity. Read as raw bytes these differ.
        arrayOf("user@Bücher.Example", "user", "xn--bcher-kva.example", null),
        arrayOf("user@XN--BCHER-KVA.example", "user", "xn--bcher-kva.example", null),
    )

    @Test
    fun theLocalPartAndDomainAreTheFrozenBytes() {
        for ((input, local, domain, subaddress) in corpus.map { it.toList() }) {
            val parts = read(input!!)
            assertEquals(local, parts.local.canonical, "FROZEN CORPUS BROKEN for <$input>: local part")
            val domainToken = parts.domain
            assertTrue(domainToken is Outcome.Success, "FROZEN CORPUS BROKEN for <$input>: domain, got $domainToken")
            assertEquals(domain, domainToken.data.canonical, "FROZEN CORPUS BROKEN for <$input>: domain")
            assertEquals(subaddress, parts.subaddress?.canonical, "FROZEN CORPUS BROKEN for <$input>: subaddress")
        }
    }

    @Test
    fun theIdentitiesAreFrozen() {
        assertEquals("email.local", EmailLocalPolicy.V1.id)
        assertEquals(1, EmailLocalPolicy.V1.version)

        val parts = read("user+work@example.com")
        assertEquals("email.local", parts.local.policyId)
        assertEquals(1, parts.local.policyVersion)
        assertEquals("email.subaddress", parts.subaddress?.policyId)
        assertEquals("email", parts.mailbox.policyId)
    }

    @Test
    fun theDomainCarriesTheDomainIdentityAndNothingEmailSpecific() {
        // The point of this issue: one domain, one identity. A domain from an address is byte-identical
        // and identity-identical to the same domain read anywhere else, so tokens match.
        val parts = read("user@Bücher.Example")
        val fromAddress = parts.domain
        val direct = normalizeDomain("Bücher.Example", domainPolicy)
        assertTrue(fromAddress is Outcome.Success && direct is Outcome.Success)
        assertEquals(direct.data, fromAddress.data, "an address's domain must be the domain")
        assertEquals("domain.ascii.u17", fromAddress.data.policyId)
    }

    @Test
    fun aDomainThatIsNotADomainIsReportedRatherThanRefusingTheAddress() {
        // An address literal is not a domain. The mailbox is still valid and still the thing to match on,
        // so the address reads and the domain says why it has no token.
        val parts = read("user@[192.0.2.1]")
        assertEquals("user@[192.0.2.1]", parts.mailbox.canonical)
        assertTrue(parts.domain is Outcome.Failure, "an address literal must not yield a domain token")
    }

    @Test
    fun theLocalPartDeclaresNoComparableForm() {
        // A local part compares with nothing else this suite writes.
        assertEquals(emptySet(), EmailLocalPolicy.V1.forms)
    }
}
