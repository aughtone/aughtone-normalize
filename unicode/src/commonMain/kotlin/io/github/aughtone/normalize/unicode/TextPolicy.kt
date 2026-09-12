package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.NormalizationStep
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.StepPhase
import io.github.aughtone.types.outcome.Outcome

/**
 * A frozen Unicode normalization form, pinned to the Unicode release its tables came from.
 *
 * ## Canonical and compatibility are not interchangeable
 *
 * [NfcU17] and [NfdU17] are **canonical**: lossless, reversible between themselves, and the right basis
 * for an identity. [NfkcU17] and [NfkdU17] are **compatibility** forms: deliberately lossy. They fold
 * a ligature into its letters, a superscript into its digit, a full-width character into its ASCII
 * counterpart. That is useful for search and for loose matching, and wrong for a token you expect to
 * round-trip - the original spelling is gone and cannot be recovered.
 *
 * ## Why the version is in the name
 *
 * Unicode data changes between releases. A policy is frozen against one release, and its [id] records
 * which - `nfc.u17` is Unicode 17. A later release means a **new constant**, `NfcU18`, never a changed
 * [NfcU17]: the stability policy guarantees an existing decomposition never changes, so what this
 * policy produced last year it still produces, and callers pinned to it keep matching.
 *
 * ## Also a step
 *
 * Each of these is usable as a [NormalizationStep], so a module that carries no Unicode data can accept
 * one from a caller and compose it - `email.byte-stable+nfc.u17` - without ever depending on this
 * module. That composed identity names the transform, so what produced the bytes stays legible.
 */
class TextPolicy internal constructor(
    override val id: String,
    override val version: Int,
    override val link: PolicyLink,
    internal val form: Form,
) : Policy, NormalizationStep {

    /** Which of the four forms this policy applies. */
    internal enum class Form(val compatibility: Boolean, val composes: Boolean) {
        NFC(compatibility = false, composes = true),
        NFD(compatibility = false, composes = false),
        NFKC(compatibility = true, composes = true),
        NFKD(compatibility = true, composes = false),
    }

    /**
     * Apply this form. Used when the policy is composed into another module's chain; a direct caller
     * goes through `normalizeText`, which also reports the identity to store beside the result.
     */
    override fun apply(value: String): String =
        if (form.composes) Normalization.compose(value, form.compatibility)
        else Normalization.decompose(value, form.compatibility)

    override fun toString(): String = id

    companion object {
        private const val VERSION_SEGMENT = "u17"

        /** Canonical composition, frozen against Unicode 17. The form to reach for by default. */
        val NfcU17: TextPolicy = of(Form.NFC, "nfc")

        /** Canonical decomposition, frozen against Unicode 17. */
        val NfdU17: TextPolicy = of(Form.NFD, "nfd")

        /** Compatibility composition, frozen against Unicode 17. **Lossy** - see the class docs. */
        val NfkcU17: TextPolicy = of(Form.NFKC, "nfkc")

        /** Compatibility decomposition, frozen against Unicode 17. **Lossy** - see the class docs. */
        val NfkdU17: TextPolicy = of(Form.NFKD, "nfkd")

        /** Every policy this module publishes, in the order they are documented. */
        internal val all: List<TextPolicy> = listOf(NfcU17, NfdU17, NfkcU17, NfkdU17)

        private fun of(form: Form, name: String): TextPolicy {
            // A rule-set in its own right, and a step when another module composes it, so the link
            // declares the phase at which it runs.
            val link = PolicyLink("$name.$VERSION_SEGMENT", LinkKind.Base, StepPhase.Normalize)
            val id = when (val outcome = PolicyId.of(listOf(link))) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            }
            return TextPolicy(id = id, version = 1, link = link, form = form)
        }
    }
}
