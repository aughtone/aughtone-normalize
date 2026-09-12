package io.github.aughtone.normalize.common

/**
 * A frozen, named rule-set: what every normalizer in this suite takes as its second argument.
 *
 * [id] and [version] together identify the canonical bytes. A caller stores both beside anything
 * derived from a normalized value, because the value itself is usually discarded, and the pair is then
 * the only record of which rules produced those bytes.
 *
 * [id] is a **chain** of links joined by `+` - the base rule set first, then anything qualifying it,
 * then any steps contributed by other modules - so an id describes a policy rather than labelling it.
 * See [PolicyId] for the grammar and the ordering rules, and [PolicyResolver] for turning a stored id
 * back into the policy that produced it.
 *
 * **Implementations are frozen.** A published policy's output never changes in place: a rule change is
 * a new constant with its own id, or a bumped [version] on an existing one. Editing one in place
 * orphans every value already derived under it, silently and unrecoverably, because the inputs are
 * gone. Concrete policies therefore have `internal` constructors and are exposed as named constants -
 * a rule-set nobody named cannot be reproduced from a stored id later.
 */
interface Policy {
    /** The chain, rendered: `email.byte-stable`, `email.byte-stable+lenient`, `domain.ascii.u17`. */
    val id: String

    /** The rules epoch for this [id]. Bumped only when the canonical bytes could differ for some input. */
    val version: Int
}
