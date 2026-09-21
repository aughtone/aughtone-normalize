package io.github.aughtone.normalize.common

/**
 * A transform one module contributes to another module's policy chain.
 *
 * This is the extension point that keeps the suite's modules from depending on each other. A
 * table-free normalizer accepts steps **by this interface**, and a caller that wants one composes:
 * it depends on both modules and the step arrives as data rather than as a compile-time dependency.
 * An open interface rather than a sealed hierarchy for exactly that reason - a sealed type cannot be
 * extended across a module boundary.
 *
 * A step carries its own [links], so a composed policy's id names every transform in it and stays
 * self-describing. A step is a **group** in the composed chain: its first link opens the group and runs
 * at a [PolicyLink.phase], and any further links are that link's own qualifiers. That is what lets a
 * configured step travel whole - `username.basic:text.u17:space.trimmed:nfc` carries the text policy's rules
 * with it, rather than a single-link alias for one of them. Groups are applied in phase order, which is
 * validated when the chain is built and again when an id is parsed.
 *
 * **A step is frozen exactly like a policy.** Its output for a given input never changes; a rule
 * change is a new step with a new link, because anything already derived under the old one cannot be
 * recomputed.
 */
interface NormalizationStep {

    /**
     * This step's group in a chain, in order: a first link that declares a [PolicyLink.phase] - a
     * [LinkKind.Step], or a [LinkKind.Base] that is also usable as a step - followed by that link's
     * qualifiers. `skeleton.u17` is a group of one; `text.u17:space.trimmed:nfc` is a group of three.
     */
    val links: List<PolicyLink>

    /**
     * Apply the transform. Implementations are pure and total: the same input always yields the same
     * output, on every platform and in every build. A step that cannot handle an input rejects it
     * before the chain runs rather than returning something approximate.
     */
    fun apply(value: String): String
}
