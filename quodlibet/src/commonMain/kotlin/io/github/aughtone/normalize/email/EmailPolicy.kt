package io.github.aughtone.normalize.email

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.Outcome

/**
 * A frozen, NAMED email-normalization policy.
 *
 * ## What the identity means
 *
 * [id] and [version] are the byte-stability epoch. They travel with anything derived from a normalized
 * value - a hash, a blind token - so the exact rules can be reproduced later. The [id] is a **chain**:
 * links joined by `+`, the base rule set first and anything qualifying it after, which is why the
 * relaxed policy reads `email.byte-stable+lenient`. An id therefore describes a policy rather than
 * merely labelling it.
 *
 * **Matching is scoped by identity.** Two values normalized under different policies never match, even
 * where their canonical strings happen to agree. Consumers that must match each other have to agree on
 * the same policy constant, not merely on a similar-sounding rule.
 *
 * ## Why the constructor is internal
 *
 * A rule-set nobody named cannot be reproduced from a stored id later, so callers choose one of the
 * named constants below instead of assembling their own.
 *
 * ## What this canonical form deliberately does not do
 *
 * It is **byte-level**: only ASCII-level, Unicode-version-independent operations, never a Unicode
 * table. So it cannot drift or expire when Unicode ships a new version, and it is byte-identical on
 * every platform and every app build for all time. It collapses **no** Unicode variants (composed
 * versus decomposed spellings, `café.fr` versus its punycode, non-ASCII case), and encodes **no**
 * provider-specific behaviour such as Gmail treating dots as insignificant - that behaviour is
 * non-standard, unknowable in general, and a frozen provider list could never grow without splitting
 * historical tokens from new ones. The only transform beyond ASCII case and whitespace comes from the
 * standard itself (RFC 5233 subaddressing).
 *
 * ## Changing this class
 *
 * A published policy's output never changes in place. Any rule change - adding NFC, IDNA/`ToASCII`, a
 * provider rule, anything - is a NEW constant with its own id, or a bumped [version] on this one. Never
 * an edit: the moment the bytes change, every token already derived under the old rules becomes
 * unmatchable, silently, with no way to find the affected records because the inputs are gone.
 * `EmailByteStabilityTest` exists to stop exactly that, and a failure there means the implementation
 * moved, never that the expectations are stale. The suite-wide rules are in
 * `docs/knowledge/specifications/DOC-0001-normalization-suite.md`.
 */
class EmailPolicy internal constructor(
    override val id: String,
    override val version: Int,
    internal val stripPlusSubaddress: Boolean,
) : Policy {
    companion object {
        /**
         * [ByteStableV1] with subaddress stripping relaxed: trim + ASCII-lowercase only, keeping the
         * `+`-subaddress. It relaxes that one rule and keeps every guarantee the suite makes: its output
         * is byte-stable, identical on every platform, frozen for this `id` and [version], and it refuses
         * the same malformed input. Two addresses collide only if they differ by ASCII case or
         * surrounding ASCII whitespace.
         *
         * Its [id] is a chain - the base rule set, then the link that relaxes it - so the identity says
         * what the policy is rather than only what it is called. Tokens derived under it match only other
         * tokens derived under `email.byte-stable+lenient`. Like any two policies it never matches
         * [ByteStableV1]: store `policyId` and `policyVersion` beside every derived value, and match
         * within a single policy identity.
         */
        val ByteStableV1Lenient: EmailPolicy = EmailPolicy(
            id = chainOf(EmailLinks.ByteStable, PolicyLink.Lenient), version = 1,
            stripPlusSubaddress = false,
        )

        /**
         * THE shared canonical form for blind tokenization, used byte-identically by every consumer.
         *
         * Frozen: trim ASCII whitespace; ASCII-lowercase; strip the `+`-subaddress (RFC 5233, part of
         * the standard). It does NOT special-case any provider's own behaviour, Gmail dots included -
         * that is non-standard and unknowable in general.
         *
         * Its [id] is a bare base link, `email.byte-stable`, with nothing qualifying it. A future rules
         * change mints a NEW `ByteStableV2` carrying the same id at [version] 2; this constant is never
         * edited in place. Pick this one unless you specifically need the subaddress kept.
         */
        val ByteStableV1: EmailPolicy = EmailPolicy(
            id = chainOf(EmailLinks.ByteStable), version = 1,
            stripPlusSubaddress = true,
        )

        /**
         * Render a chain to an id through [PolicyId], rather than writing the string out by hand.
         *
         * The written and parsed forms have to agree forever: a consumer stores what this renders and
         * expects the parser to resolve it years later. Building both from one grammar is what keeps
         * them from drifting apart. An invalid chain here is a programming error, caught the first time
         * the class loads rather than the first time someone stores an id.
         */
        private fun chainOf(vararg links: PolicyLink): String =
            when (val outcome = PolicyId.of(links.toList())) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            }
    }
}

/** The links the email policies are built from. Published to the suite by `QuodlibetPolicies`. */
internal object EmailLinks {
    val ByteStable: PolicyLink = PolicyLink("email.byte-stable", LinkKind.Base)
}
