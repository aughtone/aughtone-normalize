package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.PublishedPolicies
import io.github.aughtone.normalize.email.EmailLinks
import io.github.aughtone.normalize.email.EmailPolicy
import io.github.aughtone.normalize.email.EmailSubaddressPolicy
import io.github.aughtone.normalize.iban.IbanPolicy
import io.github.aughtone.normalize.ipv4.Ipv4Networks
import io.github.aughtone.normalize.ipv4.Ipv4Policy
import io.github.aughtone.normalize.ipv6.Ipv6Networks
import io.github.aughtone.normalize.ipv6.Ipv6Policy
import io.github.aughtone.normalize.mac.MacPolicy
import io.github.aughtone.normalize.pan.PanPolicy
import io.github.aughtone.normalize.username.UsernamePolicy
import io.github.aughtone.normalize.uuid.UuidPolicy
import io.github.aughtone.types.outcome.Outcome

/**
 * Every policy this module publishes, and the links they are built from - the one place an id stored by
 * a consumer becomes a usable policy again.
 *
 * ```
 * // the id and version were stored beside the hash when the value was first normalized
 * val policy = QuodlibetPolicies.resolve(storedId, storedVersion).dataOrThrow() as EmailPolicy
 * normalizeEmail(newValue, policy)
 * ```
 *
 * A caller depending on several modules combines their resolvers with `+` rather than reaching for a
 * registry, so resolution covers exactly the modules the program depends on and nothing registers at
 * startup.
 *
 * **Adding a policy to this module means adding it here**, or a value normalized under it can never be
 * re-derived from its stored id. The round-trip test in this module fails when the two disagree, which
 * is the point of having it.
 */
object QuodlibetPolicies : PublishedPolicies() {

    override val policies: List<Policy> =
        listOf(EmailPolicy.ByteStableV1, EmailPolicy.ByteStableV1Subaddressed) + EmailSubaddressPolicy.all +
            PanPolicy.all + IbanPolicy.all + Ipv4Policy.all + Ipv6Policy.all + UsernamePolicy.all +
            Ipv4Networks.policies + Ipv6Networks.policies + MacPolicy.all + UuidPolicy.all

    override val links: List<PolicyLink> = listOf(
        EmailLinks.ByteStable,
        EmailLinks.Subaddressed,
        EmailSubaddressPolicy.Base,
        PanPolicy.Base,
        IbanPolicy.Base,
        Ipv6Policy.Base,
        UsernamePolicy.Base,
        PolicyLink.Lenient,
    ) + Ipv4Policy.links + MacPolicy.links + UuidPolicy.links + (Ipv4Networks.links + Ipv6Networks.links).distinctBy { it.name }

    /**
     * IP block and CIDR policies, and IPv6 policies with modes, are rebuilt from their ids; every other id
     * is looked up among [policies].
     */
    override fun resolveBase(id: String, version: Int): Outcome<Policy> {
        val rebuilt = try {
            IpPolicyIds.rebuild(id)
        } catch (refused: PolicyIdentityError) {
            return Outcome.Failure(refused)
        } ?: return super.resolveBase(id, version)
        return if (rebuilt.version == version) {
            Outcome.Success(rebuilt)
        } else {
            Outcome.Failure(PolicyIdentityError.VersionMismatch(id, version, listOf(rebuilt.version)))
        }
    }
}
