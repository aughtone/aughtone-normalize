package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A stored `(id, version)` must come back as the policy that produced it.
 *
 * The first test is the one that matters when a fifth form or a new Unicode release is added: publish a
 * policy without listing it in [UnicodePolicies] and this fails, which is the only thing standing
 * between a new policy and a value nobody can re-derive.
 */
class UnicodePolicyRoundTripTest {

    @Test
    fun everyPublishedPolicyResolvesFromItsOwnIdentity() {
        for (policy in UnicodePolicies.policies) {
            when (val outcome = UnicodePolicies.resolve(policy.id, policy.version)) {
                is Outcome.Success -> {
                    assertSame(policy, outcome.data, "<${policy.id}> resolved to a different instance")
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
        val value = "ÅÅﬁ"
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
    fun aFormFrozenAgainstAnotherReleaseDoesNotResolve() {
        // `nfc.u18` is a policy this build does not carry. Resolving it to the Unicode 17 tables would
        // hand back bytes from the wrong release, which is worse than refusing.
        val outcome = UnicodePolicies.resolve("nfc.u18", 1)
        assertTrue(outcome is Outcome.Failure && outcome.exception is PolicyIdentityError.UnknownLink)
    }

    @Test
    fun aFormIsUsableAsAStepInAnotherModulesChain() {
        // What lets a table-free module offer NFC without depending on this one: the policy is also a
        // step, and its link names the transform inside the composed identity.
        val step = TextPolicy.NfcU17
        assertEquals("nfc.u17", step.link.name)
        assertEquals("u17", step.link.dataVersion)
        assertEquals("Å", step.apply("Å"))
    }
}
