package io.github.aughtone.normalize.email

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
                val parts = normalizeEmailParts(input, policy)
                assertTrue(direct is Outcome.Success && parts is Outcome.Success, "<$input> under ${policy.id}")
                assertEquals(direct.data, parts.data.mailbox, "<$input> under ${policy.id}: mailbox")
            }
        }
    }

    @Test
    fun theSubaddressViewSeesTheSameMailboxAndTag() {
        for (input in corpus) {
            val pair = normalizeEmailWithSubaddress(input, EmailSubaddressPolicy.V1)
            val parts = normalizeEmailParts(input, EmailPolicy.SubaddressRemoved)
            assertTrue(pair is Outcome.Success && parts is Outcome.Success, "<$input>")
            assertEquals(pair.data.mailbox, parts.data.mailbox, "<$input>: mailbox")
            assertEquals(pair.data.subaddress, parts.data.subaddress, "<$input>: subaddress")
        }
    }

    @Test
    fun theDomainDoesNotDependOnTheEmailPolicy() {
        // Removing a subaddress rewrites the local part and never the domain, which is why there is one
        // `email.domain` identity rather than one per base.
        for (input in corpus) {
            val kept = normalizeEmailParts(input, EmailPolicy.Address)
            val removed = normalizeEmailParts(input, EmailPolicy.SubaddressRemoved)
            assertTrue(kept is Outcome.Success && removed is Outcome.Success, "<$input>")
            assertEquals(kept.data.domain, removed.data.domain, "<$input>: domain must not depend on the policy")
            assertEquals(kept.data.local, removed.data.local, "<$input>: the local part is what the address said")
            assertEquals(kept.data.subaddress, removed.data.subaddress, "<$input>: subaddress")
        }
    }

    @Test
    fun theMailboxIsTheLocalPartAndDomainJoined() {
        // Under the anchor nothing is removed, so the pieces have to reassemble into the mailbox. That is
        // what makes a hand-split from the canonical unnecessary rather than merely discouraged.
        for (input in corpus) {
            val parts = normalizeEmailParts(input, EmailPolicy.Address)
            assertTrue(parts is Outcome.Success, "<$input>")
            assertEquals(
                parts.data.mailbox.canonical,
                "${parts.data.local.canonical}@${parts.data.domain.canonical}",
                "<$input>: local + domain must rebuild the anchor mailbox",
            )
        }
    }

    @Test
    fun anAddressThatIsOnlyATagFollowsItsPolicy() {
        // `+tag@example.com` has a local part under the anchor and none once the tag is removed, so the
        // two policies genuinely disagree about it - and the parts reader agrees with whichever it is
        // given rather than having an opinion of its own.
        val kept = normalizeEmailParts("+tag@example.com", EmailPolicy.Address)
        assertTrue(kept is Outcome.Success, "the anchor keeps the tag, so the local part is not empty")
        assertEquals("+tag@example.com", kept.data.mailbox.canonical)
        assertEquals("+tag", kept.data.local.canonical)
        assertEquals("tag", kept.data.subaddress?.canonical)

        val removed = normalizeEmailParts("+tag@example.com", EmailPolicy.SubaddressRemoved)
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
                val parts = normalizeEmailParts(input, policy)
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
