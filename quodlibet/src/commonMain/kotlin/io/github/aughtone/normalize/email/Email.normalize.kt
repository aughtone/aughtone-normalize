package io.github.aughtone.normalize.email

import io.github.aughtone.normalize.quodlibet.trimWhitespace
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * Normalize [value] under [policy] into a byte-stable canonical string.
 *
 * - **No default policy:** the caller must name one, so output is never produced under rules nobody chose.
 * - **Total or explicitly failing:** malformed input yields `Outcome.Failure(EmailNormalizationError)`,
 *   never a best-effort token. It never rejects a valid address, and never fails when Unicode changes.
 * - **Idempotent:** `normalizeEmail(normalizeEmail(v, p).data.canonical, p)` yields the same canonical.
 * - **No Unicode table, ever:** every step here is ASCII-level and Unicode-version-independent, which is
 *   what makes the output identical on every platform and every build. Anything needing a Unicode table
 *   belongs in another module and reaches this one as a caller-composed step, never as a rule added here.
 *   [normalizeEmailParts] is where that shows: it normalizes the domain as a domain, under a domain policy
 *   the caller names, so the Unicode-bound work is a separate piece with its own id - and this function's
 *   output is untouched by it.
 *
 * [NormalizedEmail.canonical] is what a caller hashes; [NormalizedEmail.policyId] and
 * [NormalizedEmail.policyVersion] are stored beside that hash, because they are the only record of which
 * rules produced it.
 *
 * Consume:
 * ```
 * normalizeEmail(value, EmailPolicy.Address)
 *     .onSuccess { normalized -> store(hash(normalized.canonical), normalized.policyId, normalized.policyVersion) }
 *     .onFailure { failure -> log(failure.exception) }   // a typed, value-free EmailNormalizationError
 * ```
 */
fun normalizeEmail(value: String, policy: EmailPolicy): Outcome<NormalizedEmail> = runOutcome {
    readEmail(value, policy).mailbox
}

/**
 * One reading of an address: every piece of it, from one pass.
 *
 * [local] and [domain] are the address as written, normalized - the local part keeps its subaddress
 * whatever the policy does with it, because that is what the address said. [mailbox] is the piece the
 * policy produces, which is the only one of the four that depends on the policy at all. [subaddress] is
 * read whether or not the policy removes it, so a caller can have the tag without giving up the tagged
 * mailbox.
 */
internal class EmailReading(
    val mailbox: NormalizedEmail,
    val subaddress: String?,
    val local: String,
    val domain: String,
)

/**
 * Read [value] under [policy]. The one place the email rules live, so every piece derived from an address
 * comes from the same reading of it. Throws a typed [EmailNormalizationError].
 */
internal fun readEmail(value: String, policy: EmailPolicy): EmailReading {
    if (value.hasUnpairedSurrogate()) throw EmailNormalizationError.UnpairedSurrogate()

    val trimmed = value.trimWhitespace()
    val at = trimmed.lastIndexOf('@')
    if (at < 0) throw EmailNormalizationError.MissingAtSign()

    val local = trimmed.substring(0, at).asciiLowercase()
    val domain = trimmed.substring(at + 1).asciiLowercase()
    if (domain.isEmpty()) throw EmailNormalizationError.EmptyDomain()
    if (local.isEmpty()) throw EmailNormalizationError.EmptyLocalPart()

    // Read whatever the address says, then let the policy decide what the mailbox keeps. The tag is not a
    // by-product of removing it: a caller can want the tag and the tagged mailbox both.
    val plus = local.indexOf('+')
    val subaddress = if (plus >= 0) local.substring(plus + 1) else null
    var mailboxLocal = local
    if (policy.stripPlusSubaddress && plus >= 0) {
        mailboxLocal = local.substring(0, plus)
        if (mailboxLocal.isEmpty()) throw EmailNormalizationError.EmptyLocalPart()
    }

    return EmailReading(
        mailbox = NormalizedEmail(
            canonical = "$mailboxLocal@$domain",
            policyId = policy.id,
            policyVersion = policy.version,
        ),
        subaddress = subaddress,
        local = local,
        domain = domain,
    )
}

/**
 * Loose convenience: the canonical string, or `null` if [value] cannot be normalized under [policy].
 * NOT for blind tokenization — use [normalizeEmail] and persist `policyId` + `policyVersion` there.
 */
fun String.normalizeEmailOrNull(policy: EmailPolicy): String? =
    normalizeEmail(this, policy).dataOrNull()?.canonical

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


/** Lowercases ASCII `A`–`Z` only; every other code unit (including all non-ASCII) is left untouched. */
private fun String.asciiLowercase(): String {
    if (none { it in 'A'..'Z' }) return this
    val sb = StringBuilder(length)
    for (c in this) sb.append(if (c in 'A'..'Z') c + 32 else c)
    return sb.toString()
}
