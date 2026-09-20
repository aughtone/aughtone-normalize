package io.github.aughtone.normalize.email

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.ubilibet.DomainPolicy
import io.github.aughtone.normalize.ubilibet.NormalizedDomain
import io.github.aughtone.normalize.ubilibet.normalizeDomain
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.dataOrElse
import io.github.aughtone.types.outcome.runOutcome

/**
 * Read an address once and get every piece of it, each with its own identity.
 *
 * A caller that tokenizes more than the mailbox has to get the pieces from somewhere, and splitting the
 * canonical string by hand means reproducing rules this module already owns - which `@` is the boundary,
 * that the lowercasing is ASCII-only - and then normalizing the domain with rules it does not own at all.
 * It is also how pieces drift: a hand-split piece records no policy, so nothing says which reading
 * produced the token and nothing can re-derive it. This returns every piece from one reading.
 *
 * - **[NormalizedEmailParts.mailbox] is exactly [normalizeEmail] under the same [EmailPolicy]** - same
 *   bytes, id and version. It is the only piece the [EmailPolicy] changes.
 * - **[NormalizedEmailParts.local] is the whole local part**, subaddress included, because that is what
 *   the address says. Under [EmailPolicy.SubaddressRemoved] the mailbox drops the tag and this does not.
 * - **[NormalizedEmailParts.domain] is a real domain token**: exactly what [normalizeDomain] writes for
 *   that domain under [domainPolicy], carrying `domain.ascii.u17`. A domain taken out of an address is a
 *   domain, so it matches one read from a URL or a block list rather than only matching other addresses.
 * - **[NormalizedEmailParts.subaddress] is read whether or not the policy removes it**, so a caller can
 *   keep the tagged mailbox and still match on the tag.
 * - **The address refuses exactly where [normalizeEmail] refuses.** The domain is the one piece that can
 *   fail on its own, and it fails without taking the address with it - see [NormalizedEmailParts.domain].
 *
 * ```
 * normalizeEmailParts(value, EmailPolicy.Address, DomainPolicy.AsciiU17)
 *     .onSuccess { parts ->
 *         store(hash(parts.mailbox.canonical), parts.mailbox.policyId, parts.mailbox.policyVersion)
 *         parts.domain.onSuccess { domain ->
 *             store(hash(domain.canonical), domain.policyId, domain.policyVersion)
 *         }
 *     }
 * ```
 *
 * ## The domain piece is bound to a Unicode release; nothing else here is
 *
 * The mailbox, the local part and the subaddress are ASCII-level and Unicode-version-independent, so they
 * cannot drift when Unicode ships a new version. The domain is normalized under UTS-46 against the tables
 * of the release named in [domainPolicy], which is why that release is part of its id (`domain.ascii.u17`).
 *
 * For a caller storing tokens this is the thing to know: a future Unicode release is a **new domain
 * policy** with a new id, and domain tokens derived under it are a different set from those derived under
 * this one - while mailbox tokens beside them are unaffected. That is the same rule every Unicode-bound
 * policy in this suite follows; it is called out here because the other pieces of the same call do not
 * follow it, and one return value carrying both kinds is easy to misread.
 *
 * ## Provider behaviour stays with the caller
 *
 * These pieces exist so a caller can apply its **own** rules to them - collapsing dots for one provider,
 * removing a tag only on domains it knows support them. None of that belongs here: it is one provider's
 * behaviour rather than a standard, and a frozen provider list cannot grow without splitting the tokens
 * minted before a domain joined it from those minted after. See RAD-0001.
 */
fun normalizeEmailParts(
    value: String,
    policy: EmailPolicy,
    domainPolicy: DomainPolicy,
): Outcome<NormalizedEmailParts> = runOutcome {
    val reading = readEmail(value, policy)
    NormalizedEmailParts(
        mailbox = reading.mailbox,
        local = NormalizedEmailLocal(
            canonical = reading.local,
            policyId = EmailLocalPolicy.V1.id,
            policyVersion = EmailLocalPolicy.V1.version,
        ),
        domain = normalizeDomain(reading.domain, domainPolicy),
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
 * key. Pair it with the domain beside it, or use the mailbox.
 *
 * It declares no comparable form: a local part compares with nothing else this suite writes.
 *
 * ## Why this piece has an identity and the domain does not
 *
 * Not because of where it sits in the address. **A piece gets its own identity when nothing else in the
 * suite reads the same thing.** A local part is meaningful only inside the address it came from, nothing
 * else produces one, and there is no competing reading of it to be confused with - so the email rules are
 * the only rules it has, and the identity says so.
 *
 * A domain is the opposite: `normalizeDomain` already reads one, properly, under UTS-46. Publishing a
 * second reading here would mint two identities for one concept, agreeing on every ASCII domain and
 * diverging on the rest - which is a silent mismatch rather than a choice. So the domain beside this
 * carries the domain identity, and this module publishes no reading of its own.
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

/** Every piece of one address, each with the identity that produced it. */
data class NormalizedEmailParts(
    /** The address under the policy that read it - exactly what [normalizeEmail] writes for the same input. */
    val mailbox: NormalizedEmail,

    /** Everything before the last `@`, subaddress included, whatever the policy did with it. */
    val local: NormalizedEmailLocal,

    /**
     * The domain, normalized as a domain: **the same identity and the same bytes as any other domain token
     * in the system**, so an address's domain matches one read from a URL, a host list or a block list.
     * There is no second domain identity, because a domain taken out of an address is still a domain.
     *
     * It is an [Outcome] because a domain that will not convert is information rather than a reason to
     * refuse the whole address. `user@[192.0.2.1]` carries an address literal and not a domain, and a
     * label can fail a UTS-46 check that an address carried happily. The mailbox is still valid and still
     * the thing to match on, so the address reads and this says why it has no token.
     */
    val domain: Outcome<NormalizedDomain>,

    /** The RFC 5233 tag, or `null` when the address carried no `+`. Empty for `user+@example.com`. */
    val subaddress: NormalizedEmailSubaddress?,
)

/** A normalized local part plus its policy identity. Store all three beside anything derived from it. */
data class NormalizedEmailLocal(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized
