package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A stored `(id, version)` must come back as the policy that produced it.
 *
 * The first test is the one that matters when `AsciiU18` arrives: publish a policy without listing it in
 * [UbilibetPolicies] and this fails, which is the only thing standing between a new policy and a
 * hostname nobody can re-derive.
 */
class UbilibetPolicyRoundTripTest {

    @Test
    fun everyPublishedPolicyResolvesFromItsOwnIdentity() {
        for (policy in UbilibetPolicies.policies) {
            when (val outcome = UbilibetPolicies.resolve(policy.id, policy.version)) {
                is Outcome.Success -> {
                    assertSame(policy, outcome.data, "<${policy.id}> resolved to a different instance")
                    assertEquals(policy.id, outcome.data.id)
                }

                is Outcome.Failure -> throw AssertionError(
                    "published policy <${policy.id}> does not resolve: ${outcome.exception::class.simpleName}. " +
                        "Add it to UbilibetPolicies.",
                )
            }
        }
    }

    @Test
    fun aResolvedPolicyProducesTheSameBytesAsTheConstant() {
        val value = "Café.FR"
        // This module publishes URL policies as well now; a hostname is what this value suits, and each
        // normalizer's own frozen corpus covers its bytes.
        for (policy in UbilibetPolicies.policies.filterIsInstance<DomainPolicy>()) {
            val resolved = (UbilibetPolicies.resolve(policy.id, policy.version) as Outcome.Success).data
            assertEquals(
                (normalizeDomain(value, policy) as Outcome.Success).data.canonical,
                (normalizeDomain(value, resolved as DomainPolicy) as Outcome.Success).data.canonical,
                "<${policy.id}> normalized differently when reached by resolution",
            )
        }
    }

    @Test
    fun theLenientPolicyIsReachedByItsChainedIdentity() {
        // The identity says what the policy is: the base rule set, then the link that relaxes it.
        val outcome = UbilibetPolicies.resolve("domain.ascii.u17+lenient", 1)
        assertTrue(outcome is Outcome.Success)
        assertSame(DomainPolicy.AsciiU17Lenient, outcome.data)
    }

    @Test
    fun aPolicyFrozenAgainstAnotherReleaseDoesNotResolve() {
        // Resolving `u18` against the Unicode 17 tables would hand back a policy whose identity claims
        // data this build does not carry.
        val outcome = UbilibetPolicies.resolve("domain.ascii.u18", 1)
        assertTrue(outcome is Outcome.Failure && outcome.exception is PolicyIdentityError.UnknownLink)
    }
}
