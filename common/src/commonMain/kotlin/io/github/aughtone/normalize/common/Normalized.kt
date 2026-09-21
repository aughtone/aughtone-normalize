package io.github.aughtone.normalize.common

/**
 * The canonical string plus the identity of the policy that produced it - the result shape every
 * normalizer in this suite returns.
 *
 * **Store all three fields together.** A caller hashes [canonical] and typically discards the original
 * value, so [policyId] and [policyVersion] become the only record of which rules produced those bytes.
 * Without them, a stored token cannot be reproduced, compared safely, or migrated later.
 *
 * **Matching is scoped by policy identity.** Two values normalized under different policies never
 * match, even where their canonical strings happen to agree - so parties that must match each other
 * have to agree on the same policy, not merely on a similar rule.
 *
 * [policyId] is a chain of links joined by `:`: the base rule set, then anything qualifying it, then
 * any steps contributed by other modules. It describes the policy rather than labelling it, which is
 * what allows a stored id to be resolved back to the policy that produced it. The full contract is in
 * `docs/knowledge/specifications/DOC-0001-normalization-suite.md`.
 */
interface Normalized {
    /** The canonical text the policy wrote. Hash or match on this, never on the input it came from. */
    val canonical: String

    /** The [Policy.id] that produced [canonical]. Store it: without it the bytes cannot be re-derived. */
    val policyId: String

    /** The [Policy.version] that produced [canonical]. Store it beside [policyId]; neither is enough alone. */
    val policyVersion: Int
}
