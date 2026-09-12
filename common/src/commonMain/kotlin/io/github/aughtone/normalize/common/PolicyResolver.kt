package io.github.aughtone.normalize.common

import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Turns a stored `(id, version)` back into the [Policy] that produced it.
 *
 * ## Why this exists
 *
 * A consumer hashes a canonical value, stores the hash with the policy identity beside it, and discards
 * the input. Two ordinary things then need the identity to be resolvable. Adding a row to an existing
 * store means normalizing the new value under *the policy those rows were derived with*, which is
 * sitting right there in the data - without resolution the application hardcodes the pairing instead,
 * and the hardcoding drifts from the data silently. And naming a policy in a properties file or an
 * environment variable lets one build serve stores on different policies with no code change, which
 * puts a human-typed string on this path.
 *
 * ## How resolution is wired
 *
 * Each module exposes **one** resolver over the policies it publishes, and a caller combines the ones
 * it depends on with [plus]. There is deliberately no global registry: on a multiplatform target a
 * registry populated at startup is an initialization-order trap, and it defeats dead-code elimination
 * by referencing every policy whether the program uses one or not. Explicit resolution also says, in
 * the code, exactly which modules a program trusts to name policies.
 *
 * ## What a resolver must never do
 *
 * Guess. An unknown id, an unknown link, or a version this build does not carry is a typed failure -
 * never the nearest match, never the newest version, never a default. Returning an almost-right policy
 * is worse than returning nothing, because the caller will use it to derive bytes that silently fail to
 * match everything already stored.
 */
interface PolicyResolver {

    /** Every policy this resolver can return. Small, finite, and the basis of the round-trip test. */
    val policies: List<Policy>

    /** Every link these policies are built from, used to parse an id before looking it up. */
    val links: List<PolicyLink>

    /**
     * Resolve [id] at [version], or fail with a [PolicyIdentityError] explaining which part could not
     * be resolved.
     */
    fun resolve(id: String, version: Int): Outcome<Policy>
}

/**
 * Resolve across several modules, trying each in turn. Build one where the program starts, from exactly
 * the modules it depends on:
 *
 * ```
 * val policies = QuodlibetPolicies + PhonePolicies
 * val policy = policies.resolve(storedId, storedVersion)
 * ```
 */
operator fun PolicyResolver.plus(other: PolicyResolver): PolicyResolver =
    CompositePolicyResolver(listOf(this, other))

/** A [PolicyResolver] over several others. Prefer building it with [plus]. */
class CompositePolicyResolver(private val resolvers: List<PolicyResolver>) : PolicyResolver {

    override val policies: List<Policy> get() = resolvers.flatMap { it.policies }

    override val links: List<PolicyLink> get() = resolvers.flatMap { it.links }.distinct()

    override fun resolve(id: String, version: Int): Outcome<Policy> = resolveIn(policies, links, id, version)
}

/**
 * The resolver a module exposes: it publishes its [policies] and the [links] they are built from, and
 * inherits parsing, ordering and version checking.
 *
 * Extend it once per module - never expose two, or a caller cannot tell which one covers what.
 */
abstract class PublishedPolicies : PolicyResolver {

    override fun resolve(id: String, version: Int): Outcome<Policy> = resolveIn(policies, links, id, version)
}

/**
 * Parse [id] against [links], then look the chain up among [policies] at [version]. Shared by every
 * resolver so that one module cannot accidentally accept an id another would refuse.
 */
private fun resolveIn(
    policies: List<Policy>,
    links: List<PolicyLink>,
    id: String,
    version: Int,
): Outcome<Policy> = runOutcome {
    val chain = when (val parsed = PolicyId.parse(id, links)) {
        is Outcome.Success -> parsed.data
        is Outcome.Failure -> throw parsed.exception
    }
    val matches = policies.filter { it.id == chain.rendered }
    if (matches.isEmpty()) throw PolicyIdentityError.UnknownPolicy(chain.rendered)
    matches.firstOrNull { it.version == version }
        ?: throw PolicyIdentityError.VersionMismatch(chain.rendered, version, matches.map { it.version }.sorted())
}
