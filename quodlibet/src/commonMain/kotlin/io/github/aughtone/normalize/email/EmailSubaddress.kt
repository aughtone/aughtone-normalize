package io.github.aughtone.normalize.email

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.dataOrElse
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize an address into its mailbox and its RFC 5233 subaddress, from one reading of it.
 *
 * `email:subaddress.removed` removes the subaddress, so every address for one mailbox yields one token. A
 * caller that also wants to match on the tag - to tell `user:work@` from `user:home@` without losing
 * mailbox matching - needs the tag as a second token, and cutting it out by hand means reproducing the
 * exact rules the mailbox was read by: the last `@`, the first `+`, ASCII-only lowercasing and trimming.
 * This returns both pieces from the same reading, so they cannot drift apart.
 *
 * - **The mailbox is exactly [normalizeEmail] under [EmailPolicy.SubaddressRemoved]:** the same bytes, id
 *   and version, so a mailbox token derived here matches one derived there.
 * - **The subaddress is everything after the first `+`** of the local part, further `+` included, and
 *   ASCII-lowercased like the rest of it. `null` when the address has no `+`; the empty string for
 *   `user+@example.com`, because that is what the address says.
 * - **Refusals are [normalizeEmail]'s**, including an address with nothing before the `+`.
 *
 * ```
 * normalizeEmailWithSubaddress(value, EmailSubaddressPolicy.V1)
 *     .onSuccess { parts ->
 *         store(hash(parts.mailbox.canonical), parts.mailbox.policyId, parts.mailbox.policyVersion)
 *         parts.subaddress?.let { tag -> store(hash(tag.canonical), tag.policyId, tag.policyVersion) }
 *     }
 * ```
 */
fun normalizeEmailWithSubaddress(
    value: String,
    policy: EmailSubaddressPolicy,
): Outcome<NormalizedEmailWithSubaddress> = runOutcome {
    val reading = readEmail(value, policy.mailbox)
    NormalizedEmailWithSubaddress(
        mailbox = reading.mailbox,
        subaddress = reading.subaddress?.let { tag ->
            NormalizedEmailSubaddress(canonical = tag, policyId = policy.id, policyVersion = policy.version)
        },
    )
}

/**
 * A frozen policy for reading an address into a mailbox and a subaddress.
 *
 * Its [id] and [version] are the **subaddress's** identity, stored beside a tag token so the tag records
 * what it is. The mailbox keeps the identity of the email policy it is read under, so its tokens match
 * those from [normalizeEmail]. The two are paired inside the constant rather than chosen separately:
 * only a policy that removes the subaddress has one to return, so there is no combination to get wrong.
 *
 * The subaddress version moves whenever the mailbox policy's reading rules do, because a tag is only
 * meaningful against the reading that separated it.
 */
class EmailSubaddressPolicy internal constructor(
    override val id: String,
    override val version: Int,
    internal val mailbox: EmailPolicy,
) : Policy {

    override fun toString(): String = id

    companion object {
        internal val Base: PolicyLink = PolicyLink("email.subaddress", LinkKind.Base)

        /**
         * The subaddress `email.subaddress` version 1, read beside the mailbox of
         * [EmailPolicy.SubaddressRemoved] - the address with the tag taken off, which is what a mailbox is.
         */
        val V1: EmailSubaddressPolicy = EmailSubaddressPolicy(
            id = PolicyId.of(listOf(Base)).dataOrElse { error("not a valid policy chain: ${it.message}") }.rendered,
            version = 1,
            mailbox = EmailPolicy.SubaddressRemoved,
        )

        internal val all: List<EmailSubaddressPolicy> = listOf(V1)
    }
}

/** An address's mailbox and, when it has one, its subaddress, each with the identity that produced it. */
data class NormalizedEmailWithSubaddress(
    val mailbox: NormalizedEmail,
    val subaddress: NormalizedEmailSubaddress?,
)

/** A normalized subaddress plus its policy identity. Store all three beside anything derived from it. */
data class NormalizedEmailSubaddress(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized
