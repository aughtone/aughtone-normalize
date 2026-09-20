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
 * Read an address once and get every piece of it, each with its own identity.
 *
 * A caller that tokenizes more than the mailbox has to get the pieces from somewhere, and splitting the
 * canonical string by hand means reproducing rules this module already owns - which `@` is the boundary,
 * that the domain is raw bytes rather than an IDNA form, that lowercasing is ASCII-only. It is also how
 * pieces drift: a hand-split domain records no policy, so nothing says which reading produced the token
 * and nothing can re-derive it. This returns all four pieces from one reading of the address.
 *
 * - **[NormalizedEmailParts.mailbox] is exactly [normalizeEmail] under the same [EmailPolicy]** - same
 *   bytes, id and version. It is the only piece the policy changes.
 * - **[NormalizedEmailParts.local] is the whole local part**, subaddress included, because that is what
 *   the address says. Under [EmailPolicy.SubaddressRemoved] the mailbox drops the tag and this does not.
 * - **[NormalizedEmailParts.domain] is the raw bytes after the last `@`**, ASCII-lowercased, with no
 *   ToASCII conversion - so it is the same value whichever email policy read the address.
 * - **[NormalizedEmailParts.subaddress] is read whether or not the policy removes it**, so a caller can
 *   keep the tagged mailbox and still match on the tag.
 * - **Refusals are [normalizeEmail]'s**, unchanged.
 *
 * ```
 * normalizeEmailParts(value, EmailPolicy.Address)
 *     .onSuccess { parts ->
 *         store(hash(parts.mailbox.canonical), parts.mailbox.policyId, parts.mailbox.policyVersion)
 *         store(hash(parts.domain.canonical), parts.domain.policyId, parts.domain.policyVersion)
 *     }
 * ```
 *
 * ## Provider behaviour stays with the caller
 *
 * These pieces exist so a caller can apply its **own** rules to them - collapsing dots for one provider,
 * removing a tag only on domains it knows support them. None of that belongs here: it is one provider's
 * behaviour rather than a standard, and a frozen provider list cannot grow without splitting the tokens
 * minted before a domain joined it from those minted after. See RAD-0001.
 */
fun normalizeEmailParts(value: String, policy: EmailPolicy): Outcome<NormalizedEmailParts> = runOutcome {
    val reading = readEmail(value, policy)
    NormalizedEmailParts(
        mailbox = reading.mailbox,
        local = NormalizedEmailLocal(
            canonical = reading.local,
            policyId = EmailLocalPolicy.V1.id,
            policyVersion = EmailLocalPolicy.V1.version,
        ),
        domain = NormalizedEmailDomain(
            canonical = reading.domain,
            policyId = EmailDomainPolicy.V1.id,
            policyVersion = EmailDomainPolicy.V1.version,
        ),
        subaddress = reading.subaddress?.let { tag ->
            NormalizedEmailSubaddress(
                canonical = tag,
                policyId = EmailSubaddressPolicy.V1.id,
                policyVersion = EmailSubaddressPolicy.V1.version,
            )
        },
    )
}

/**
 * The local part of an address, as its own frozen identity: everything before the last `@`, ASCII-
 * lowercased, with any `+`-subaddress still on it.
 *
 * **On its own it identifies nobody.** `sales` is the same local part at every domain in the world, and
 * local parts come from a small vocabulary, so a token of one alone is guessable from its distribution
 * even under a keyed hash - the same warning [EmailSubaddressPolicy] carries. It is published because a
 * caller applying its own provider rules needs the piece those rules act on, not because it is a match
 * key. Pair it with [EmailDomainPolicy], or use the mailbox.
 *
 * It declares no comparable form: a local part compares with nothing else this suite writes.
 */
class EmailLocalPolicy internal constructor(
    override val id: String,
    override val version: Int,
) : Policy {

    override fun toString(): String = id

    companion object {
        internal val Base: PolicyLink = PolicyLink("email.local", LinkKind.Base)

        /** The local part, version 1. */
        val V1: EmailLocalPolicy = EmailLocalPolicy(
            id = PolicyId.of(listOf(Base)).dataOrElse { error("not a valid policy chain: ${it.message}") }.rendered,
            version = 1,
        )

        internal val all: List<EmailLocalPolicy> = listOf(V1)
    }
}

/**
 * The domain of an address, as its own frozen identity: the raw bytes after the last `@`, ASCII-
 * lowercased, with **no** ToASCII conversion.
 *
 * **It is not comparable with `domain.ascii.u17`, and the two must never be matched against each other.**
 * `normalizeDomain` runs UTS-46 under a named Unicode release and writes A-labels; this writes the bytes
 * the address carried. For an all-ASCII domain they agree, which is most input and is exactly what makes
 * the mismatch dangerous: a Unicode domain written as U-labels canonicalizes to `xn--` there and to
 * itself here, so a store mixing the two matches on the easy cases and silently misses the rest. An email
 * domain is read as bytes on purpose - the local part beside it is opaque to everyone but the receiving
 * server, and an address is not a host name.
 *
 * The value is identical under every email policy, because removing a subaddress rewrites the local part
 * and never the domain, so there is one identity rather than one per base.
 */
class EmailDomainPolicy internal constructor(
    override val id: String,
    override val version: Int,
) : Policy {

    override fun toString(): String = id

    companion object {
        internal val Base: PolicyLink = PolicyLink("email.domain", LinkKind.Base)

        /** The domain, version 1. */
        val V1: EmailDomainPolicy = EmailDomainPolicy(
            id = PolicyId.of(listOf(Base)).dataOrElse { error("not a valid policy chain: ${it.message}") }.rendered,
            version = 1,
        )

        internal val all: List<EmailDomainPolicy> = listOf(V1)
    }
}

/** Every piece of one address, each with the identity that produced it. */
data class NormalizedEmailParts(
    val mailbox: NormalizedEmail,
    val local: NormalizedEmailLocal,
    val domain: NormalizedEmailDomain,
    val subaddress: NormalizedEmailSubaddress?,
)

/** A normalized local part plus its policy identity. Store all three beside anything derived from it. */
data class NormalizedEmailLocal(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/** A normalized email domain plus its policy identity. Store all three beside anything derived from it. */
data class NormalizedEmailDomain(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized
