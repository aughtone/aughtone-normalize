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
    // Flattened, so `a + b + c` is one composite over three modules. A nested composite would try to
    // compose a chain before it could see the links the outer modules publish.
    CompositePolicyResolver(this.members() + other.members())

private fun PolicyResolver.members(): List<PolicyResolver> =
    if (this is CompositePolicyResolver) resolvers else listOf(this)

/**
 * A [PolicyResolver] over several others. Prefer building it with [plus].
 *
 * ## Each module resolves its own ids
 *
 * An id is offered to every module's own [PolicyResolver.resolve], not looked up in a merged list. Some
 * modules rebuild policies rather than enumerating them - a phone region, a configured text policy - and
 * only the module can do that. Combining resolvers therefore never resolves less than the modules would
 * alone.
 *
 * ## A composed chain resolves group by group
 *
 * When no module owns the whole id, it may be a composed chain: a base policy from one module followed
 * by steps from others, as `normalizeUsername(value, policy, steps)` produces. The chain is split at each
 * link that opens a group - a link published with a [StepPhase] - and each group is resolved by the
 * module that owns it. The result is a [ComposedPolicy], which carries the base and the steps needed to
 * derive the same bytes again. A group no module owns fails the whole chain; there is no partial result.
 *
 * ## Which failure is reported
 *
 * A module that recognizes an id and refuses it - a version it does not carry, a spelling that is not
 * canonical - speaks with authority, and its error is the one returned. Otherwise the error names what no
 * module knows.
 */
class CompositePolicyResolver(internal val resolvers: List<PolicyResolver>) : PolicyResolver {

    override val policies: List<Policy> get() = resolvers.flatMap { it.policies }

    override val links: List<PolicyLink> get() = resolvers.flatMap { it.links }.distinct()

    override fun resolve(id: String, version: Int): Outcome<Policy> = runOutcome {
        val direct = resolveDirect(id, version)
        if (direct is Outcome.Success) return@runOutcome direct.data
        val directFailure = (direct as Outcome.Failure).exception
        if (directFailure !is PolicyIdentityError || directFailure.isOwned()) throw directFailure

        val names = PolicyId.split(id).dataOrThrow()
        val openers = links.filter { it.phase != null }.mapTo(HashSet()) { it.name }
        val groups = splitGroups(names, openers)
        if (groups.size < 2) throw directFailure

        val base = resolveDirect(groups.first().joinToString(SEPARATOR), version).dataOrThrow()
        val steps = groups.drop(1).map { group ->
            val groupId = group.joinToString(SEPARATOR)
            // A step carries no version of its own: a changed step is a new link (see NormalizationStep),
            // so each group has exactly one version, and it is the first.
            val resolved = resolveDirect(groupId, STEP_VERSION).dataOrThrow()
            resolved as? NormalizationStep ?: throw PolicyIdentityError.NotAStep(id, groupId)
        }
        val composed = ComposedPolicy(base, steps)
        if (composed.id != id) throw PolicyIdentityError.NotCanonical(id)
        composed
    }

    /** Offer [id] to each module in turn: the first success, else the most authoritative failure. */
    private fun resolveDirect(id: String, version: Int): Outcome<Policy> {
        var fallback: Throwable? = null
        for (resolver in resolvers) {
            when (val outcome = resolver.resolve(id, version)) {
                is Outcome.Success -> return outcome
                is Outcome.Failure -> {
                    val error = outcome.exception
                    if (error !is PolicyIdentityError || error.isOwned()) return outcome
                    if (fallback == null) fallback = error
                }
            }
        }
        // No module owns it. Parse against every link so the error names the first one nobody publishes.
        val parsed = PolicyId.parse(id, links)
        if (parsed is Outcome.Failure) return parsed
        return Outcome.Failure(fallback ?: PolicyIdentityError.UnknownPolicy(id))
    }

    private companion object {
        const val SEPARATOR = "+"
        const val STEP_VERSION = 1

        /** An error from a module that recognized the id: not merely "this is not one of mine". */
        fun PolicyIdentityError.isOwned(): Boolean = this !is PolicyIdentityError.UnknownLink && this !is PolicyIdentityError.UnknownPolicy

        /** Split link names into groups, starting a new one at every name that opens a group. */
        fun splitGroups(names: List<String>, openers: Set<String>): List<List<String>> {
            val groups = mutableListOf(mutableListOf(names.first()))
            for (name in names.drop(1)) {
                if (name in openers) groups += mutableListOf(name) else groups.last() += name
            }
            return groups
        }
    }
}

/**
 * A policy composed from a [base] and the [steps] that followed it, as a composite resolver returns for a
 * stored id such as `username.basic+skeleton.u17`.
 *
 * It is a record of what produced the bytes, not a normalizer: derive new values by passing [base] and
 * [steps] back to the normalizer that owns [base] - `normalizeUsername(value, base as UsernamePolicy,
 * steps)`. Its [version] is the base's, because that is the version a composed normalizer reports.
 */
class ComposedPolicy(val base: Policy, val steps: List<NormalizationStep>) : Policy {

    override val id: String = (listOf(base.id) + steps.map { step -> step.links.joinToString("+") { it.name } }).joinToString("+")

    override val version: Int = base.version

    override fun equals(other: Any?): Boolean =
        this === other || (other is ComposedPolicy && other.id == id && other.version == version)

    override fun hashCode(): Int = 31 * id.hashCode() + version

    override fun toString(): String = id
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
    val chain = PolicyId.parse(id, links).dataOrThrow()
    val matches = policies.filter { it.id == chain.rendered }
    if (matches.isEmpty()) throw PolicyIdentityError.UnknownPolicy(chain.rendered)
    matches.firstOrNull { it.version == version }
        ?: throw PolicyIdentityError.VersionMismatch(chain.rendered, version, matches.map { it.version }.sorted())
}
