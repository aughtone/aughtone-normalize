package io.github.aughtone.normalize.email

import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize [value] under [policy] into a byte-stable canonical string.
 *
 * - **No default policy:** the caller must name one, so output is never produced under rules nobody chose.
 * - **Total or explicitly failing:** malformed input yields `Outcome.Error(EmailNormalizationError)`,
 *   never a best-effort token. It never rejects a valid address, and never fails when Unicode changes.
 * - **Idempotent:** `normalizeEmail(normalizeEmail(v, p).data.canonical, p)` yields the same canonical.
 *
 * Consume:
 * ```
 * when (val o = normalizeEmail(value, EmailPolicy.ByteStableV1)) {
 *     is Outcome.Success -> o.data               // NormalizedEmail; hash o.data.canonical
 *     is Outcome.Error   -> o.exception          // an EmailNormalizationError
 * }
 * ```
 */
fun normalizeEmail(value: String, policy: EmailPolicy): Outcome<NormalizedEmail> = runOutcome {
    if (value.hasUnpairedSurrogate()) throw EmailNormalizationError.UnpairedSurrogate()

    val trimmed = value.trimAsciiWhitespace()
    val at = trimmed.lastIndexOf('@')
    if (at < 0) throw EmailNormalizationError.MissingAtSign()

    var local = trimmed.substring(0, at).asciiLowercase()
    val domain = trimmed.substring(at + 1).asciiLowercase()
    if (domain.isEmpty()) throw EmailNormalizationError.EmptyDomain()
    if (local.isEmpty()) throw EmailNormalizationError.EmptyLocalPart()

    if (policy.stripPlusSubaddress) {
        local = local.substringBefore('+')
        if (local.isEmpty()) throw EmailNormalizationError.EmptyLocalPart()
    }

    NormalizedEmail(canonical = "$local@$domain", policyId = policy.id, policyVersion = policy.version)
}

/**
 * Loose convenience: the canonical string, or `null` if [value] cannot be normalized under [policy].
 * NOT for blind tokenization — use [normalizeEmail] and persist `policyId` + `policyVersion` there.
 */
fun String.normalizeEmailOrNull(policy: EmailPolicy): String? =
    when (val outcome = normalizeEmail(this, policy)) {
        is Outcome.Success -> outcome.data.canonical
        is Outcome.Error -> null
    }

// --- byte-level helpers: ASCII only, Unicode-version-independent ---

/** True if the string contains a high surrogate without a following low surrogate, or vice-versa. */
private fun String.hasUnpairedSurrogate(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate()) {
            if (i + 1 >= length || !this[i + 1].isLowSurrogate()) return true
            i += 2
        } else {
            if (c.isLowSurrogate()) return true
            i += 1
        }
    }
    return false
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

/** Lowercases ASCII `A`–`Z` only; every other code unit (including all non-ASCII) is left untouched. */
private fun String.asciiLowercase(): String {
    if (none { it in 'A'..'Z' }) return this
    val sb = StringBuilder(length)
    for (c in this) sb.append(if (c in 'A'..'Z') c + 32 else c)
    return sb.toString()
}
