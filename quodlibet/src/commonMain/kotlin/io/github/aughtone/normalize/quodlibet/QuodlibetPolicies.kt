package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.PublishedPolicies
import io.github.aughtone.normalize.email.EmailLinks
import io.github.aughtone.normalize.email.EmailPolicy
import io.github.aughtone.normalize.iban.IbanPolicy
import io.github.aughtone.normalize.ipv4.Ipv4Policy
import io.github.aughtone.normalize.ipv6.Ipv6Policy
import io.github.aughtone.normalize.pan.PanPolicy
import io.github.aughtone.normalize.username.UsernamePolicy

/**
 * Every policy this module publishes, and the links they are built from - the one place an id stored by
 * a consumer becomes a usable policy again.
 *
 * ```
 * // the id and version were stored beside the hash when the value was first normalized
 * when (val outcome = QuodlibetPolicies.resolve(storedId, storedVersion)) {
 *     is Outcome.Success -> normalizeEmail(newValue, outcome.data as EmailPolicy)
 *     is Outcome.Failure -> error(outcome.exception.message ?: "unknown policy")
 * }
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
        listOf(EmailPolicy.ByteStableV1, EmailPolicy.ByteStableV1Lenient) +
            PanPolicy.all + IbanPolicy.all + Ipv4Policy.all + Ipv6Policy.all + UsernamePolicy.all

    override val links: List<PolicyLink> = listOf(
        EmailLinks.ByteStable,
        PanPolicy.Base,
        IbanPolicy.Base,
        Ipv6Policy.Base,
        UsernamePolicy.Base,
        PolicyLink.Lenient,
    ) + Ipv4Policy.links
}
