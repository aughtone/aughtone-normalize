package io.github.aughtone.normalize.common

import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * A policy identity: an ordered chain of links that renders to a [Policy.id] and parses back from one.
 *
 * Rendering joins the links with `+`; parsing splits an id and resolves each link. They are the same
 * list read in opposite directions, which is the point - a second, separate parser is exactly how the
 * written and read forms drift apart, and drift here means a stored id no longer names the policy that
 * produced the bytes beside it.
 *
 * ## The grammar
 *
 * ```
 * id      = link ("+" link)*
 * link    = segment ("." segment)*
 * segment = [a-z0-9]+ ("-" [a-z0-9]+)*
 * ```
 *
 * Lowercase throughout, so a region reads `region-ca` and never `region-CA`. Examples:
 * `email.byte-stable`, `email.byte-stable+lenient`, `phone.e164+region-ca+lenient`,
 * `domain.ascii.u17+lenient`, `email.byte-stable+nfc.u17+punycode.u17`.
 *
 * ## The order
 *
 * A chain is a sequence of **groups**. The first group opens with the [LinkKind.Base]; each later group
 * opens with something that runs at a [StepPhase], and the phases do not go backwards. Within a group
 * come that link's qualifiers: [LinkKind.Parameter]s first, then [LinkKind.Relaxation]s.
 *
 * Grouping is what lets a qualifier say which link it modifies. In `url.rfc3986+domain.ascii.u17+lenient`
 * the leniency belongs to the host policy, not to the URL policy, and a flat ordering could not express
 * the difference. A chain in any other order is **refused, never reordered**: if two spellings resolved
 * to one policy, the id would stop identifying it.
 *
 * ## What this type does not do
 *
 * Holding a chain is not permission to run it. [parse] resolves links only against the ones a module
 * actually publishes, so a caller may assemble combinations of published links - the result is fully
 * described by its own id - but can never introduce a link, and therefore never names a rule-set nobody
 * published. Turning an id into a usable [Policy] is [PolicyResolver]'s job.
 */
class PolicyId private constructor(val links: List<PolicyLink>) {

    /** The chain as it appears in [Policy.id] and in storage: links joined by `+`. */
    val rendered: String = links.joinToString(SEPARATOR.toString()) { it.name }

    /** The base link, which every valid chain has exactly one of, first. */
    val base: PolicyLink get() = links.first()

    override fun toString(): String = rendered

    override fun equals(other: Any?): Boolean = this === other || (other is PolicyId && links == other.links)

    override fun hashCode(): Int = links.hashCode()

    companion object {
        private const val SEPARATOR = '+'

        /**
         * Build a chain from [links], validating order and duplicates. Fails with a
         * [PolicyIdentityError] rather than repairing anything.
         */
        fun of(links: List<PolicyLink>): Outcome<PolicyId> = runOutcome {
            val rendered = links.joinToString(SEPARATOR.toString()) { it.name }
            if (links.isEmpty()) throw PolicyIdentityError.EmptyId()

            if (links.first().kind != LinkKind.Base) throw PolicyIdentityError.MissingBase(rendered)

            val seen = mutableSetOf<String>()
            for (link in links) {
                if (!seen.add(link.name)) throw PolicyIdentityError.DuplicateLink(rendered, link.name)
            }

            // A chain is a sequence of groups: a rule-set or a step, followed by the qualifiers that
            // belong to it. That is what lets `url.rfc3986+domain.ascii.u17+lenient` say the leniency
            // applies to the host policy rather than to the URL policy - a flat "qualifiers first" rule
            // could not express which link a qualifier modifies.
            var phase = -1
            var sawRelaxation = false
            for (link in links.drop(1)) {
                val linkPhase = link.phase
                when {
                    linkPhase != null -> {
                        if (linkPhase.ordinal < phase) throw PolicyIdentityError.OutOfOrder(rendered, link.name)
                        phase = linkPhase.ordinal
                        sawRelaxation = false
                    }

                    link.kind == LinkKind.Base -> throw PolicyIdentityError.MultipleBases(rendered)

                    link.kind == LinkKind.Parameter -> {
                        // Parameters come before relaxations within their group: a region says what the
                        // rules operate on, and a relaxation says which of them to loosen.
                        if (sawRelaxation) throw PolicyIdentityError.OutOfOrder(rendered, link.name)
                    }

                    link.kind == LinkKind.Relaxation -> sawRelaxation = true

                    else -> throw PolicyIdentityError.OutOfOrder(rendered, link.name)
                }
            }

            PolicyId(links)
        }

        /**
         * Parse [id] into a chain, resolving each link against [known] - the links the caller's modules
         * publish. An unknown link fails the whole chain: a partially understood identity is worse than
         * none, because it looks usable.
         */
        fun parse(id: String, known: Collection<PolicyLink>): Outcome<PolicyId> = runOutcome {
            val byName = known.associateBy { it.name }
            val names = when (val outcome = split(id)) {
                is Outcome.Success -> outcome.data
                is Outcome.Failure -> throw outcome.exception
            }
            val links = names.map { name ->
                byName[name] ?: throw PolicyIdentityError.UnknownLink(id, name)
            }
            when (val outcome = of(links)) {
                is Outcome.Success -> outcome.data
                is Outcome.Failure -> throw outcome.exception
            }
        }

        /**
         * Split [id] into link names, checking only the lexical grammar - useful for validating a
         * configured string before any module is consulted. Order and existence are [parse]'s job.
         */
        fun split(id: String): Outcome<List<String>> = runOutcome {
            if (id.isEmpty()) throw PolicyIdentityError.EmptyId()
            val names = id.split(SEPARATOR)
            for (name in names) {
                if (!PolicyLink.isValidLinkName(name)) throw PolicyIdentityError.MalformedLink(name)
            }
            names
        }

    }
}
