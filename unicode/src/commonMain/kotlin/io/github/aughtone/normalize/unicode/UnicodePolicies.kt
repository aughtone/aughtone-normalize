package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.PublishedPolicies

/**
 * Every policy this module publishes, and the links they are built from, so a stored id resolves back
 * to the form that produced it.
 *
 * These links are also what another module's chain is parsed against: a caller that composed
 * `email.byte-stable+nfc.u17` resolves it by combining this resolver with the one from the module that
 * owns the base - `QuodlibetPolicies + UnicodePolicies`. Neither module knows about the other; the
 * caller's dependencies decide what can be resolved.
 *
 * **A new form added here must be listed here**, or a value normalized under it can never be
 * re-derived from its stored id. The round-trip test fails when they disagree.
 */
object UnicodePolicies : PublishedPolicies() {

    override val policies: List<Policy> = TextPolicy.all

    override val links: List<PolicyLink> = TextPolicy.all.map { it.link }
}
