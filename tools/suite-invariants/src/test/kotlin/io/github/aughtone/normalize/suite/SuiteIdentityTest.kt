package io.github.aughtone.normalize.suite

import io.github.aughtone.normalize.common.ComparableForm
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyResolver
import io.github.aughtone.normalize.common.plus
import io.github.aughtone.normalize.confusables.ConfusablesPolicies
import io.github.aughtone.normalize.phone.PhonePolicies
import io.github.aughtone.normalize.quodlibet.QuodlibetPolicies
import io.github.aughtone.normalize.ubilibet.UbilibetPolicies
import io.github.aughtone.normalize.unicode.UnicodePolicies
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariants that hold across the suite, which no published module can check.
 *
 * Every module resolves its own ids, and a caller combines the resolvers of the modules it depends on.
 * That is the design, and it means each module's own round-trip test can pass while the *combination*
 * a caller actually builds is broken - two modules publishing one id, two modules declaring one
 * comparable form, a chain that resolves alone and not in company. Nothing in the layout gives any module
 * a view of the others, so these questions had no home until this one.
 *
 * All three failure modes here are silent. A colliding id resolves to whichever module is asked first; a
 * colliding form makes unrelated values **match**, which no test anywhere would report as a failure.
 */
class SuiteIdentityTest {

    /** Every module that publishes policies. Adding one to the suite means adding it here. */
    private val modules: Map<String, PolicyResolver> = mapOf(
        "quodlibet" to QuodlibetPolicies,
        "ubilibet" to UbilibetPolicies,
        "unicode" to UnicodePolicies,
        "confusables" to ConfusablesPolicies,
        "phone" to PhonePolicies,
    )

    /**
     * Comparable forms two modules are **meant** to share, each with the reason.
     *
     * Empty is the healthy state. An entry here says two modules deliberately write the same canonical
     * text and their values are meant to match; anything not listed that collides is an accident, and
     * anything listed that stops colliding is a shared form silently dropped - which turns comparable
     * values into incomparable ones and reads to a caller as a fact about the values rather than a bug.
     */
    private val deliberatelySharedForms: Map<String, String> = emptyMap()

    @Test
    fun noPolicyIdIsPublishedByTwoModules() {
        val owners = mutableMapOf<String, MutableList<String>>()
        for ((module, resolver) in modules) {
            for (policy in resolver.policies) {
                owners.getOrPut(policy.id) { mutableListOf() }.add(module)
            }
        }
        val collisions = owners.filterValues { it.distinct().size > 1 }
        assertTrue(
            collisions.isEmpty(),
            "an id published by two modules resolves to whichever is asked first, and each module's own " +
                "round-trip test still passes: " +
                collisions.entries.joinToString("; ") { (id, mods) -> "<$id> in ${mods.distinct()}" },
        )
    }

    @Test
    fun noComparableFormIsDeclaredByTwoModules() {
        val owners = mutableMapOf<String, MutableSet<String>>()
        for ((module, resolver) in modules) {
            for (policy in resolver.policies) {
                for (form in policy.forms + policy.offeredForms) {
                    owners.getOrPut(form.name) { mutableSetOf() }.add(module)
                }
            }
        }
        val shared = owners.filterValues { it.size > 1 }
        val undeclared = shared.filterKeys { it !in deliberatelySharedForms }
        assertTrue(
            undeclared.isEmpty(),
            "two modules declaring one form name make unrelated values COMPARABLE, which fails by " +
                "matching rather than by erroring. Add it to deliberatelySharedForms with a reason, or " +
                "rename one: " + undeclared.entries.joinToString("; ") { (name, mods) -> "<$name> in $mods" },
        )

        val vanished = deliberatelySharedForms.keys - shared.keys
        assertTrue(
            vanished.isEmpty(),
            "a form listed as deliberately shared is no longer shared. A shared form dropped on one side " +
                "turns comparable values into incomparable ones, and a caller reads that as a fact about " +
                "the values: $vanished",
        )
    }

    @Test
    fun everyPublishedIdResolvesThroughTheCombinedResolver() {
        // The combination is what a caller builds; resolving alone is not the same guarantee.
        val combined = modules.values.reduce { a, b -> a + b }
        for ((module, resolver) in modules) {
            for (policy in resolver.policies) {
                when (val outcome = combined.resolve(policy.id, policy.version)) {
                    is Outcome.Success -> assertEquals(
                        policy.id,
                        outcome.data.id,
                        "<${policy.id}> from $module resolved to something else through the combination",
                    )

                    is Outcome.Failure -> throw AssertionError(
                        "<${policy.id}> from $module resolves alone and not in the combination a caller " +
                            "builds: ${outcome.exception::class.simpleName}",
                    )
                }
            }
        }
    }

    @Test
    fun valuesThatMustNeverMatchAreNotComparable() {
        // Pairs that look alike and mean different things. `comparability` is what a caller asks before
        // matching two stored identities, so this is the answer it must give.
        val combined = modules.values.reduce { a, b -> a + b }
        val mustNotMatch = listOf(
            // A host name under IDNA against a URL that contains one, read by the same tables under the
            // same release. The URL id even carries the domain id as a link, which is exactly the kind of
            // near-identity that invites a careless match.
            Triple(
                "domain.ascii.u17",
                "url.rfc3986:domain.ascii.u17",
                "a domain is not the URL it appears in",
            ),
            // Different widths, and one is not a prefix of the other.
            Triple("mac.eui48", "mac.eui64", "a 48-bit address is not a 64-bit one"),
        )
        for ((idA, idB, why) in mustNotMatch) {
            val a = combined.resolve(idA, 1)
            val b = combined.resolve(idB, 1)
            assertTrue(a is Outcome.Success && b is Outcome.Success, "<$idA> and <$idB> must both resolve")
            val shared = sharedForms(a.data, b.data)
            assertTrue(shared.isEmpty(), "<$idA> and <$idB> must not be comparable - $why - shared: $shared")
        }
    }

    private fun sharedForms(a: Policy, b: Policy): Set<ComparableForm> = a.forms intersect b.forms
}
