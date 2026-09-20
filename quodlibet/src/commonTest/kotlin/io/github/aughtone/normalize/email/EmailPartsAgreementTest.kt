package io.github.aughtone.normalize.email

import io.github.aughtone.normalize.ubilibet.DomainPolicy
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every way of reading an address must answer the same, because they are one reading.
 *
 * `normalizeEmail`, `normalizeEmailWithSubaddress` and `normalizeEmailParts` are three views over
 * `readEmail`. This runs one corpus through all of them and compares, rather than asserting each
 * separately - which is the test that was missing in the phone module when two entry points disagreed
 * about the same input for a week (aughtone/aughtone-normalize#32). Asserting each path against its own
 * expectations cannot catch a disagreement between them; only comparing can.
 */
class EmailPartsAgreementTest {

    private val domainPolicy = DomainPolicy.AsciiU17

    private val corpus: List<String> = listOf(
        "user@example.com",
        "User@Example.COM",
        "  user@example.com  ",
        "user+work@example.com",
        "user+work+more@example.com",
        "user+@example.com",
        "user@sub.example.co.uk",
        "user@name@example.com",
        "a@b",
    )

    private val refused: List<String> = listOf("", "user", "@example.com", "user@")

    @Test
    fun theMailboxIsExactlyWhatNormalizeEmailWrites() {
        for (policy in listOf(EmailPolicy.Address, EmailPolicy.SubaddressRemoved)) {
            for (input in corpus) {
                val direct = normalizeEmail(input, policy)
                val parts = normalizeEmailParts(input, policy, domainPolicy)
                assertTrue(direct is Outcome.Success && parts is Outcome.Success, "<$input> under ${policy.id}")
                assertEquals(direct.data, parts.data.mailbox, "<$input> under ${policy.id}: mailbox")
            }
        }
    }

    @Test
    fun theSubaddressViewSeesTheSameMailboxAndTag() {
        for (input in corpus) {
            val pair = normalizeEmailWithSubaddress(input, EmailSubaddressPolicy.V1)
            val parts = normalizeEmailParts(input, EmailPolicy.SubaddressRemoved, domainPolicy)
            assertTrue(pair is Outcome.Success && parts is Outcome.Success, "<$input>")
            assertEquals(pair.data.mailbox, parts.data.mailbox, "<$input>: mailbox")
            assertEquals(pair.data.subaddress, parts.data.subaddress, "<$input>: subaddress")
        }
    }

    @Test
    fun theDomainDoesNotDependOnTheEmailPolicy() {
        // Removing a subaddress rewrites the local part and never the domain, so the domain token does not
        // depend on which email policy read the address.
        for (input in corpus) {
            val kept = normalizeEmailParts(input, EmailPolicy.Address, domainPolicy)
            val removed = normalizeEmailParts(input, EmailPolicy.SubaddressRemoved, domainPolicy)
            assertTrue(kept is Outcome.Success && removed is Outcome.Success, "<$input>")
            assertEquals(
                (kept.data.domain as Outcome.Success).data,
                (removed.data.domain as Outcome.Success).data,
                "<$input>: domain must not depend on the policy",
            )
            assertEquals(kept.data.local, removed.data.local, "<$input>: the local part is what the address said")
            assertEquals(kept.data.subaddress, removed.data.subaddress, "<$input>: subaddress")
        }
    }

    // REMOVED: `theMailboxIsTheLocalPartAndDomainJoined`. It asserted that local + "@" + domain rebuilds
    // the mailbox canonical, which was never a requirement - it was written in #33 and then treated as a
    // constraint it had invented. It is false now and rightly so: the domain piece is a real domain token
    // (`xn--bcher-kva.example`) while the mailbox keeps the bytes the address carried (`bücher.example`).
    // Keeping the property would have meant keeping a second, weaker domain identity to satisfy it.

    @Test
    fun anAddressThatIsOnlyATagFollowsItsPolicy() {
        // `+tag@example.com` has a local part under the anchor and none once the tag is removed, so the
        // two policies genuinely disagree about it - and the parts reader agrees with whichever it is
        // given rather than having an opinion of its own.
        val kept = normalizeEmailParts("+tag@example.com", EmailPolicy.Address, domainPolicy)
        assertTrue(kept is Outcome.Success, "the anchor keeps the tag, so the local part is not empty")
        assertEquals("+tag@example.com", kept.data.mailbox.canonical)
        assertEquals("+tag", kept.data.local.canonical)
        assertEquals("tag", kept.data.subaddress?.canonical)

        val removed = normalizeEmailParts("+tag@example.com", EmailPolicy.SubaddressRemoved, domainPolicy)
        assertTrue(removed is Outcome.Failure, "removing the tag leaves no local part, got $removed")
        assertEquals(
            (normalizeEmail("+tag@example.com", EmailPolicy.SubaddressRemoved) as Outcome.Failure).exception::class,
            removed.exception::class,
        )
    }

    @Test
    fun aRefusalIsTheSameRefusalEverywhere() {
        for (policy in listOf(EmailPolicy.Address, EmailPolicy.SubaddressRemoved)) {
            for (input in refused) {
                val direct = normalizeEmail(input, policy)
                val parts = normalizeEmailParts(input, policy, domainPolicy)
                assertTrue(direct is Outcome.Failure, "<$input> under ${policy.id} must be refused by normalizeEmail")
                assertTrue(parts is Outcome.Failure, "<$input> under ${policy.id} must be refused, got $parts")
                assertEquals(
                    direct.exception::class,
                    parts.exception::class,
                    "<$input> under ${policy.id}: refused for a different reason",
                )
            }
        }
    }
}
