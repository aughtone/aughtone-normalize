package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.PublishedPolicies
import io.github.aughtone.normalize.common.StepPhase
import io.github.aughtone.types.outcome.Outcome

/**
 * A frozen hostname-normalization policy: a fixed set of UTS-46 checks, pinned to the Unicode release
 * whose IDNA tables it was built against.
 *
 * ## Why the flags are not yours to choose
 *
 * UTS-46 takes five boolean flags, and every combination is a different rule-set that accepts and
 * refuses different names. A caller-supplied flag is a rule nobody recorded: two parties could each use
 * "UTS-46" and disagree about whether a name is valid, which is precisely what a policy identity exists
 * to prevent. So the flags are fixed per policy and travel inside the [id].
 *
 * ## The two policies
 *
 * [AsciiU17] applies every check. [AsciiU17Lenient] relaxes the three that ordinary web input routinely
 * fails - hyphen placement, the STD3 character restriction and DNS length - and keeps the bidi and
 * joiner rules, which exist to stop a name that displays as one thing and resolves as another. Relaxing
 * those would not be leniency; it would be accepting a name that cannot be represented unambiguously.
 *
 * ## A new Unicode release is a new constant
 *
 * UTS-46 guarantees that a character already valid keeps its mapping, so a newer table can accept what
 * an older one refused but never changes a name that already normalized. `AsciiU18` would therefore be
 * a new constant; [AsciiU17] keeps producing what it produces today.
 */
class DomainPolicy internal constructor(
    override val id: String,
    override val version: Int,
    internal val flags: Uts46Flags,
) : Policy {

    override fun toString(): String = id

    companion object {
        /**
         * The base link every domain policy is built on: UTS-46 against the Unicode 17 tables.
         *
         * It declares a phase because it is also applied *inside* another policy's chain - a URL policy
         * names the host policy it uses, so `url.rfc3986+domain.ascii.u17` says exactly how the host was
         * normalized rather than leaving it implied.
         */
        internal val Base: PolicyLink = PolicyLink("domain.ascii.u17", LinkKind.Base, StepPhase.Map)

        /**
         * Every UTS-46 check applied: hyphen placement, bidi, joiners, the STD3 ASCII restriction and
         * DNS length. The policy to reach for when a name is going to be stored, matched or resolved.
         */
        val AsciiU17: DomainPolicy = DomainPolicy(
            id = chainOf(Base),
            version = 1,
            flags = Uts46Flags(
                checkHyphens = true,
                checkBidi = true,
                checkJoiners = true,
                useStd3AsciiRules = true,
                verifyDnsLength = true,
            ),
        )

        /**
         * Hyphen placement, the STD3 ASCII restriction and DNS length relaxed; bidi and joiner rules
         * kept. Roughly what a browser accepts, and still byte-stable, frozen and identical on every
         * platform - a relaxed rule, never a weaker guarantee.
         */
        val AsciiU17Lenient: DomainPolicy = DomainPolicy(
            id = chainOf(Base, PolicyLink.Lenient),
            version = 1,
            flags = Uts46Flags(
                checkHyphens = false,
                checkBidi = true,
                checkJoiners = true,
                useStd3AsciiRules = false,
                verifyDnsLength = false,
            ),
        )

        /** Every policy this module publishes, in the order they are documented. */
        internal val all: List<DomainPolicy> = listOf(AsciiU17, AsciiU17Lenient)

        /** Render a chain through [PolicyId] rather than writing the id out by hand - see `Policy`. */
        private fun chainOf(vararg links: PolicyLink): String =
            when (val outcome = PolicyId.of(links.toList())) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            }
    }
}

/**
 * Every policy this module publishes, and the links they are built from, so a stored id resolves back to
 * the policy that produced it.
 *
 * **A new policy must be listed here**, or a hostname normalized under it can never be re-derived from
 * its stored identity. The round-trip test fails when the two disagree.
 */
object UbilibetPolicies : PublishedPolicies() {

    override val policies: List<Policy> = DomainPolicy.all + UrlPolicy.all

    override val links: List<PolicyLink> = listOf(DomainPolicy.Base, UrlPolicy.Base, PolicyLink.Lenient)
}
