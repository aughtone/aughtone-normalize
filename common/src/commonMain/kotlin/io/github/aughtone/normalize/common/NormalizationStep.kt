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
 * A step carries its own [link], so a composed policy's id names every transform in it and stays
 * self-describing. It is applied in [PolicyLink.phase] order, which is validated when the chain is
 * built and again when an id is parsed: there is one correct order, and the others are degenerate
 * rather than useful.
 *
 * **A step is frozen exactly like a policy.** Its output for a given input never changes; a rule
 * change is a new step with a new link, because anything already derived under the old one cannot be
 * recomputed.
 */
interface NormalizationStep {

    /** This step's identity in a chain - `nfc.u17`, `punycode.u17` - always a [LinkKind.Step] link. */
    val link: PolicyLink

    /**
     * Apply the transform. Implementations are pure and total: the same input always yields the same
     * output, on every platform and in every build. A step that cannot handle an input rejects it
     * before the chain runs rather than returning something approximate.
     */
    fun apply(value: String): String
}
