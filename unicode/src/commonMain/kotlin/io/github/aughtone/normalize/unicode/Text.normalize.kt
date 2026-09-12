package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize [value] to the Unicode form named by [policy].
 *
 * - **No default policy:** the caller names the form, so text is never normalized under rules nobody
 *   chose - and the choice between a canonical and a compatibility form is not one to make by default.
 * - **Frozen data, never the platform's:** the tables come from a pinned Unicode release and ship with
 *   the library, so the same input yields the same bytes on every platform and in an app built years
 *   ago. Calling a platform normalizer here would reintroduce exactly the drift this suite removes.
 * - **Idempotent:** normalizing an already-normalized string returns it unchanged.
 *
 * ```
 * when (val outcome = normalizeText(value, TextPolicy.NfcU17)) {
 *     is Outcome.Success -> outcome.data.canonical
 *     is Outcome.Failure -> outcome.exception       // a TextNormalizationError
 * }
 * ```
 */
fun normalizeText(value: String, policy: TextPolicy): Outcome<NormalizedText> = runOutcome {
    if (value.hasUnpairedSurrogate()) throw TextNormalizationError.UnpairedSurrogate()
    NormalizedText(
        canonical = policy.apply(value),
        policyId = policy.id,
        policyVersion = policy.version,
    )
}

/**
 * Loose convenience: the normalized string, or `null` if [value] cannot be normalized under [policy].
 * NOT for anything you intend to store a token from - use [normalizeText] and keep the policy identity.
 */
fun String.normalizeTextOrNull(policy: TextPolicy): String? =
    when (val outcome = normalizeText(this, policy)) {
        is Outcome.Success -> outcome.data.canonical
        is Outcome.Failure -> null
    }

/**
 * The normalized string plus the policy identity that produced it. Store all three beside anything
 * derived from [canonical] - the identity records which Unicode release the bytes came from.
 */
data class NormalizedText(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/**
 * Why text could not be normalized.
 *
 * Value-free, like every error in this suite: a message never echoes the text, because normalized text
 * is routinely personal data and a rejected value must not reach a log. Match on the subclass; the
 * message text is not API.
 */
sealed class TextNormalizationError(message: String) : Exception(message) {

    /**
     * The value contains an unpaired UTF-16 surrogate - half a character, with no valid UTF-8 form.
     * Encoding one to bytes is not consistent across platforms, so it is refused rather than normalized
     * into something that differs by target.
     */
    class UnpairedSurrogate : TextNormalizationError("text: unpaired surrogate")
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
