package io.github.aughtone.normalize.confusables

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.NormalizationStep
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.PublishedPolicies
import io.github.aughtone.normalize.common.StepPhase
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Reduce [value] to its UTS-39 skeleton under [policy]: what it looks like, with everything that only
 * differs in spelling collapsed together.
 *
 * ## This is a check, not an identity
 *
 * **A skeleton is deliberately many-to-one.** That is the point - it exists so `раypal` and `paypal`
 * collide - and it makes this the one normalizer in the suite that does not preserve distinctions. Every
 * other policy here keeps distinct values distinct so two parties can match on them; this one makes
 * different values equal on purpose.
 *
 * So: **never store a skeleton as an account key, a token, or a unique index.** Two genuinely different
 * users can share one, and a system that treats it as identity merges their accounts. Derive the
 * identity from the plain value, compute the skeleton beside it, and use the skeleton to *flag* a
 * collision for a human or a policy engine to judge.
 *
 * ## Recompute it when the data version changes
 *
 * UTS-39 states that confusable mappings may change between Unicode releases and that stored skeletons
 * must be recomputed. A policy frozen at one release keeps producing the same bytes forever, which is
 * why each release is a new named constant - but a skeleton computed under `skeleton.u17` and compared
 * against one computed under a later release is a comparison with no meaning.
 *
 * ```
 * when (val outcome = normalizeSkeleton(value, ConfusablePolicy.SkeletonU17)) {
 *     is Outcome.Success -> outcome.data.canonical
 *     is Outcome.Failure -> outcome.exception
 * }
 * ```
 */
fun normalizeSkeleton(value: String, policy: ConfusablePolicy): Outcome<NormalizedSkeleton> = runOutcome {
    if (value.hasUnpairedSurrogate()) throw ConfusableNormalizationError.UnpairedSurrogate()
    NormalizedSkeleton(
        canonical = policy.apply(value),
        policyId = policy.id,
        policyVersion = policy.version,
    )
}

/**
 * A frozen skeleton policy, pinned to the Unicode release its confusable data came from.
 *
 * Also a [NormalizationStep], so a module that carries no data can accept it from a caller and compose
 * it - `username.basic+skeleton.u17` - without depending on this module. That composed identity is a
 * different identity from the plain one, which is exactly right: the folded form is not the account.
 */
class ConfusablePolicy internal constructor(
    override val id: String,
    override val version: Int,
    override val link: PolicyLink,
) : Policy, NormalizationStep {

    override fun apply(value: String): String = Skeleton.of(value)

    override fun toString(): String = id

    companion object {
        /** The skeleton as defined by UTS-39, frozen against Unicode 17. */
        val SkeletonU17: ConfusablePolicy = run {
            val link = PolicyLink("skeleton.u17", LinkKind.Base, StepPhase.Map)
            val id = when (val outcome = PolicyId.of(listOf(link))) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            }
            ConfusablePolicy(id = id, version = 1, link = link)
        }

        internal val all: List<ConfusablePolicy> = listOf(SkeletonU17)
    }
}

/** The skeleton plus the policy identity that produced it. */
data class NormalizedSkeleton(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/** Why a value could not be reduced to a skeleton. No message carries any part of the input. */
sealed class ConfusableNormalizationError(message: String) : Exception(message) {

    /** Half a character: an unpaired surrogate has no valid UTF-8 form and no display form either. */
    class UnpairedSurrogate : ConfusableNormalizationError("confusable: unpaired surrogate")
}

/**
 * Every policy this module publishes, so a stored id resolves back to the policy that produced it.
 *
 * A caller composing the skeleton into another module's chain resolves the result by combining this
 * resolver with that module's - `QuodlibetPolicies + ConfusablesPolicies` resolves
 * `username.basic+skeleton.u17`.
 */
object ConfusablesPolicies : PublishedPolicies() {

    override val policies: List<Policy> = ConfusablePolicy.all

    override val links: List<PolicyLink> = ConfusablePolicy.all.map { it.link }
}

/** True if the string contains a high surrogate without a following low surrogate, or vice versa. */
private fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        if (character.isHighSurrogate()) {
            if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
            index += 2
        } else {
            if (character.isLowSurrogate()) return true
            index += 1
        }
    }
    return false
}
