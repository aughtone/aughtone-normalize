package io.github.aughtone.normalize.email

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.types.outcome.dataOrElse

/**
 * A frozen, NAMED email-normalization policy.
 *
 * ## What the identity means
 *
 * [id] and [version] are the byte-stability epoch. They travel with anything derived from a normalized
 * value - a hash, a blind token - so the exact rules can be reproduced later. The [id] is a **chain**:
 * links joined by `:`, the base rule set first and anything qualifying it after, which is why the
 * subaddress-removing policy reads `email:subaddress.removed`. An id therefore describes a policy rather
 * than merely labelling it.
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
 * standard itself (RFC 5233 subaddressing), and that one is an option rather than the default.
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
         * THE identity anchor: the whole address, trim + ASCII-lowercase only, with the `+`-subaddress
         * KEPT. `User+Tag@Example.com` is `user+tag@example.com`.
         *
         * **Why keeping is the default.** Some mail systems treat the subaddress as part of an
         * individual's account, and there is no way to ask a domain which behaviour it has: RFC 5233 is
         * optional, RFC 5321 makes the local part opaque to everyone but the receiving server, and a
         * default Postfix treats `+` as a literal character. Where that cannot be known, the safe
         * assumption is that the tagged address **is** the whole address, because it never merges two
         * people - and splitting it apart later is always possible, while un-merging two accounts already
         * tokenized as one is not. See `docs/knowledge/specifications/DOC-0001-normalization-suite.md`.
         *
         * Its [id] is the bare base link, `email`, with nothing qualifying it. A future rules change mints
         * a new constant with its own id, or a bumped [version] on this one; it is never edited in place.
         */
        val Address: EmailPolicy = EmailPolicy(
            id = chainOf(EmailLinks.Base), version = 1,
            stripPlusSubaddress = false,
        )

        /**
         * [Address] with the `+`-subaddress removed: `user+tag@example.com` is `user@example.com`.
         *
         * **What it is for.** Matching every address that reaches one mailbox on a provider that treats
         * the tag as a tag - which is a caller's knowledge, not something this library can discover. It is
         * the mailbox [normalizeEmailWithSubaddress] returns beside the subaddress piece.
         *
         * **It is not comparable with [Address], and declares no comparable form.** For a tagged address
         * the two write different text, and a tag cannot be removed from a stored token after the fact, so
         * matching across them would depend on whether someone typed a tag.
         *
         * Its id was `email.lenient` in `0.0.1`, `email.byte-stable+lenient` in `0.0.2` and
         * `email.byte-stable` in `0.0.3`, where it was also the default and the tag-keeping variant was
         * `email.byte-stable+subaddressed`. In `0.0.4` removing the tag is
         * the option rather than the default, and the id says what it does. Its bytes never changed.
         */
        val SubaddressRemoved: EmailPolicy = EmailPolicy(
            id = chainOf(EmailLinks.Base, EmailLinks.SubaddressRemoved), version = 1,
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
            PolicyId.of(links.toList())
                .dataOrElse { error("not a valid policy chain: ${it.message}") }
                .rendered
    }
}

/** The links the email policies are built from. Published to the suite by `QuodlibetPolicies`. */
internal object EmailLinks {
    val Base: PolicyLink = PolicyLink("email", LinkKind.Base)

    /** The option that removes the `+`-subaddress. Keeping it is the default; see [EmailPolicy.Address]. */
    val SubaddressRemoved: PolicyLink = PolicyLink("subaddress.removed", LinkKind.Parameter)
}
