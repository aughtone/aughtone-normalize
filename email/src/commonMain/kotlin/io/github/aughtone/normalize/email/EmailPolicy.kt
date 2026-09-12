package io.github.aughtone.normalize.email

/**
 * A frozen, NAMED email-normalization policy.
 *
 * [id] + [version] are the byte-stability epoch: they travel with anything derived from a normalized
 * value (a hash / blind token) so the exact rules can be reproduced later. A published policy's output
 * NEVER changes in place — any rule change is a NEW policy or a bumped [version], never an edit.
 *
 * The canonical form is deliberately **byte-level**: it applies only ASCII-level, Unicode-version-
 * independent operations and never consults a Unicode table. It therefore never fails, drifts, or
 * expires when Unicode ships a new version, and is byte-identical on every platform and every app build
 * for all time. It deliberately collapses **no** Unicode variants (composed vs decomposed spellings,
 * `café.fr` vs its punycode, non-ASCII case), and it deliberately encodes **no** provider-specific
 * behaviour (such as Gmail treating dots as insignificant): that behaviour is non-standard, unknowable
 * in general, and a frozen provider list could never grow without splitting historical tokens. The only
 * transforms it applies beyond ASCII case/whitespace come from the standard itself (RFC 5233
 * subaddressing). Adding NFC, IDNA/ToASCII, or any provider rule later would change the canonical bytes,
 * so it must be a NEW policy version — never an in-place "improvement", which would orphan every token
 * already derived under this one.
 */
class EmailPolicy internal constructor(
    val id: String,
    val version: Int,
    internal val stripPlusSubaddress: Boolean,
) {
    companion object {
        /**
         * Relaxed: trim + ASCII-lowercase only, keeping the `+`-subaddress. It relaxes one rule of
         * [ByteStableV1] - subaddress stripping - and keeps every guarantee the suite makes: its output
         * is byte-stable, identical on every platform, frozen for this `id` and [version], and it refuses
         * the same malformed input. Two addresses collide only if they differ by ASCII case or
         * surrounding ASCII whitespace.
         *
         * Tokens derived under it match only other tokens derived under `email.lenient`. Like any two
         * policies, it never matches [ByteStableV1]: store `policyId` and `policyVersion` beside every
         * derived value, and match within a single policy identity.
         */
        val Lenient: EmailPolicy = EmailPolicy(
            id = "email.lenient", version = 1,
            stripPlusSubaddress = false,
        )

        /**
         * THE shared canonical form for blind tokenization, used byte-identically by every consumer.
         * Frozen: trim ASCII whitespace; ASCII-lowercase; strip the `+`-subaddress (RFC 5233, part of
         * the spec). It does NOT special-case any provider's own behaviour (e.g. Gmail dots) — that is
         * non-standard and unknowable in general. A future rules change mints a NEW `ByteStableV2` (same
         * id, [version] = 2); this one is never edited in place.
         */
        val ByteStableV1: EmailPolicy = EmailPolicy(
            id = "email.byte-stable", version = 1,
            stripPlusSubaddress = true,
        )
    }
}
