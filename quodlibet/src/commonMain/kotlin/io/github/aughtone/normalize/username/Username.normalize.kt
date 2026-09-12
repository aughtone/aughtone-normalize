package io.github.aughtone.normalize.username

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.NormalizationStep
import io.github.aughtone.normalize.common.Normalized
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize a username or handle under [policy], optionally through [steps] contributed by another
 * module.
 *
 * The base rules are email's, and for email's reason: trim ASCII whitespace, lowercase ASCII `A`-`Z`,
 * and leave every non-ASCII character exactly as it arrived, case included. Folding non-ASCII case
 * needs a Unicode table, and a table in this module would reintroduce the version drift the suite
 * exists to remove.
 *
 * ## No platform rules
 *
 * Dots are significant. A leading `@` is an ordinary character. No length cap is applied. Some
 * platforms ignore dots, strip the sigil, or limit length - that is one provider's behaviour, and this
 * suite encodes universal standards only. The reasoning is the Gmail-dots argument in RAD-0001, and it
 * has the same answer here: a rule that cannot be true everywhere cannot be frozen.
 *
 * ## Confusable folding is a chain, and a different identity
 *
 * A caller wanting anti-spoofing passes the skeleton step from `:confusables`, producing the chained
 * policy `username.basic+skeleton.u17`. That fold is deliberately many-to-one - it exists to make a
 * lookalike collide with its target - so **it is a check, never an account key**. Storing a folded
 * handle as an identity merges genuinely different accounts.
 *
 * ```
 * normalizeUsername(value, UsernamePolicy.Basic)                    // the identity
 * normalizeUsername(value, UsernamePolicy.Basic, listOf(skeleton))  // the spoof check
 * ```
 */
fun normalizeUsername(
    value: String,
    policy: UsernamePolicy,
    steps: List<NormalizationStep> = emptyList(),
): Outcome<NormalizedUsername> = runOutcome {
    if (value.hasUnpairedSurrogate()) throw UsernameNormalizationError.UnpairedSurrogate()

    val trimmed = value.trimAsciiWhitespace()
    if (trimmed.isEmpty()) throw UsernameNormalizationError.Empty()

    var canonical = trimmed.asciiLowercase()
    for (step in steps) canonical = step.apply(canonical)

    NormalizedUsername(
        canonical = canonical,
        policyId = policy.idWith(steps),
        policyVersion = policy.version,
    )
}

/**
 * A frozen username normalization policy.
 *
 * One policy, because there is nothing here to relax: the rules are already the most permissive ones
 * that stay byte-stable without a table.
 */
class UsernamePolicy internal constructor(
    override val id: String,
    override val version: Int,
) : Policy {

    /**
     * The identity of this policy composed with [steps] - the base link, then each step's link, which
     * is what makes a folded handle a different identity from a plain one rather than a hidden variant
     * of it.
     */
    internal fun idWith(steps: List<NormalizationStep>): String {
        if (steps.isEmpty()) return id
        return when (val outcome = PolicyId.of(listOf(Base) + steps.map { it.link })) {
            is Outcome.Success -> outcome.data.rendered
            is Outcome.Failure -> throw outcome.exception
        }
    }

    override fun toString(): String = id

    companion object {
        /** The base link this policy is built on. */
        internal val Base: PolicyLink = PolicyLink("username.basic", LinkKind.Base)

        /** Trim, ASCII-lowercase, and nothing else. */
        val Basic: UsernamePolicy = UsernamePolicy(
            id = when (val outcome = PolicyId.of(listOf(Base))) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            },
            version = 1,
        )

        internal val all: List<UsernamePolicy> = listOf(Basic)
    }
}

/** The canonical handle plus the policy identity that produced it. */
data class NormalizedUsername(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized

/** Why a handle could not be normalized. No message carries any part of the input. */
sealed class UsernameNormalizationError(message: String) : Exception(message) {

    /** Empty, or nothing but ASCII whitespace. */
    class Empty : UsernameNormalizationError("username: empty")

    /** Half a character: an unpaired surrogate has no valid UTF-8 form. */
    class UnpairedSurrogate : UsernameNormalizationError("username: unpaired surrogate")
}

private fun Char.isAsciiWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u000B' || this == '\u000C'

private fun String.trimAsciiWhitespace(): String {
    var start = 0
    var end = length
    while (start < end && this[start].isAsciiWhitespace()) start++
    while (end > start && this[end - 1].isAsciiWhitespace()) end--
    return substring(start, end)
}

private fun String.asciiLowercase(): String {
    if (none { it in 'A'..'Z' }) return this
    val builder = StringBuilder(length)
    for (character in this) builder.append(if (character in 'A'..'Z') character + 32 else character)
    return builder.toString()
}

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
