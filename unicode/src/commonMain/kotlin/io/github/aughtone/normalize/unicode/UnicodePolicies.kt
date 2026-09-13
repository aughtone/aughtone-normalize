package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.PublishedPolicies
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Every text policy this module can produce, resolvable from its stored id.
 *
 * Text policies are configured rather than enumerated, so resolution rebuilds the policy an id names
 * instead of looking it up: `text.u17+trim+casefold` parses into its release and rules, is built through
 * the same builder a caller uses, and is refused unless it renders back to exactly the id given. That
 * keeps the round trip total - anything this module can build, it can resolve - without accepting a
 * second spelling of any policy.
 *
 * [policies] lists the named configurations, which is what the round-trip test walks.
 */
object UnicodePolicies : PublishedPolicies() {

    override val policies: List<Policy> = TextPolicy.all

    override val links: List<PolicyLink> = TextPolicy.publishedLinks

    override fun resolve(id: String, version: Int): Outcome<Policy> = runOutcome {
        val policy = TextPolicy.parse(id).dataOrThrow()
        if (policy.version != version) throw PolicyIdentityError.VersionMismatch(id, version, listOf(policy.version))
        policy
    }
}
