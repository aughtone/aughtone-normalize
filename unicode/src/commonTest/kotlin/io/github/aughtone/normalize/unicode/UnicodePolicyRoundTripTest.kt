package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stored `(id, version)` must come back as the policy that produced it.
 *
 * The first test is the one that matters when a named configuration is added: publish one without
 * listing it in `TextPolicy.all` and the resolver's own walk misses it. The configured-policy cases live
 * in `TextPolicyTest`.
 */
class UnicodePolicyRoundTripTest {

    @Test
    fun everyPublishedPolicyResolvesFromItsOwnIdentity() {
        for (policy in UnicodePolicies.policies) {
            when (val outcome = UnicodePolicies.resolve(policy.id, policy.version)) {
                is Outcome.Success -> {
                    assertEquals(policy, outcome.data, "<${policy.id}> resolved to a different policy")
                    assertEquals(policy.id, outcome.data.id)
                }

                is Outcome.Failure -> throw AssertionError(
                    "published policy <${policy.id}> does not resolve: ${outcome.exception::class.simpleName}. " +
                        "Add it to UnicodePolicies.",
                )
            }
        }
    }

    @Test
    fun aResolvedPolicyProducesTheSameBytesAsTheConstant() {
        val value = "  \u00C5A\u030A\uFB01  "
        for (policy in UnicodePolicies.policies) {
            val resolved = (UnicodePolicies.resolve(policy.id, policy.version) as Outcome.Success).data
            assertEquals(
                (normalizeText(value, policy as TextPolicy) as Outcome.Success).data.canonical,
                (normalizeText(value, resolved as TextPolicy) as Outcome.Success).data.canonical,
                "<${policy.id}> normalized differently when reached by resolution",
            )
        }
    }

    @Test
    fun aPolicyFrozenAgainstAnotherReleaseDoesNotResolve() {
        // `text.u18` names a release this build does not carry. Resolving it to the Unicode 17 tables
        // would hand back bytes from the wrong release, which is worse than refusing.
        val outcome = UnicodePolicies.resolve("text.u18:nfc", 1)
        assertTrue(outcome is Outcome.Failure && outcome.exception is PolicyIdentityError.UnknownLink)
    }

    @Test
    fun aPolicyIsUsableAsAStepInAnotherModulesChain() {
        // What lets a table-free module offer text rules without depending on this one: the policy is
        // also a step, and it contributes its whole group of links to the composed identity.
        val step = TextPolicy.NfcU17
        assertEquals(listOf("text.u17", "nfc"), step.links.map { it.name })
        assertEquals("u17", step.links.first().dataVersion)
        assertEquals("\u00C5", step.apply("A\u030A"))
    }

    @Test
    fun everyPublishedIdSurvivesThePortableSpelling() {
        // #29 asked for this over *every* published id, not a sample: the portable form exists for slots
        // that cannot hold a `:`, and an id that does not come back from it is an identity a caller can
        // store and never resolve again. A sample cannot cover a name the mapping happens to mangle.
        for (policy in UnicodePolicies.policies) {
            val portable = PolicyId.toPortable(policy.id)
            assertTrue(portable is Outcome.Success, "<${policy.id}> has no portable spelling: $portable")
            assertTrue(
                portable.data.none { it == ':' },
                "<${policy.id}> kept a ':' in its portable spelling: <${portable.data}>",
            )
            val back = PolicyId.fromPortable(portable.data)
            assertTrue(back is Outcome.Success, "<${portable.data}> does not recover an id: $back")
            assertEquals(policy.id, back.data, "<${policy.id}> did not survive the portable round trip")
        }
    }
}
