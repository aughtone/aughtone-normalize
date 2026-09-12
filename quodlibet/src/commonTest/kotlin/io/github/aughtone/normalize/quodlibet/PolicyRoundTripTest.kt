package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.normalize.email.EmailPolicy
import io.github.aughtone.normalize.email.normalizeEmail
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A stored `(id, version)` must come back as the policy that produced it, or the identity a consumer
 * kept beside their data is decoration.
 *
 * The first test here is the one that matters for every module added later: publish a policy without
 * listing it in the module's resolver and this fails, which is the only thing standing between a new
 * policy and a value nobody can re-derive.
 */
class PolicyRoundTripTest {

    @Test
    fun everyPublishedPolicyResolvesFromItsOwnIdentity() {
        for (policy in QuodlibetPolicies.policies) {
            when (val o = QuodlibetPolicies.resolve(policy.id, policy.version)) {
                is Outcome.Success -> {
                    assertSame(policy, o.data, "<${policy.id}> resolved to a different instance")
                    assertEquals(policy.id, o.data.id, "<${policy.id}> did not render back to itself")
                }

                is Outcome.Failure -> throw AssertionError(
                    "published policy <${policy.id}> v${policy.version} does not resolve: " +
                        "${o.exception::class.simpleName}. Add it to QuodlibetPolicies.",
                )
            }
        }
    }

    @Test
    fun aResolvedPolicyProducesTheSameBytesAsTheConstant() {
        val value = "  User+Tag@Example.COM  "
        // This module publishes several normalizers now; the email ones are the ones this value suits.
        // Each normalizer's own frozen corpus covers its bytes, so what matters here is that reaching a
        // policy by resolution gives the same instance and therefore the same output.
        for (policy in QuodlibetPolicies.policies.filterIsInstance<EmailPolicy>()) {
            val resolved = (QuodlibetPolicies.resolve(policy.id, policy.version) as Outcome.Success).data
            val viaConstant = normalizeEmail(value, policy)
            val viaResolved = normalizeEmail(value, resolved as EmailPolicy)
            assertTrue(viaConstant is Outcome.Success && viaResolved is Outcome.Success)
            assertEquals(
                viaConstant.data.canonical,
                viaResolved.data.canonical,
                "<${policy.id}> normalized differently when reached by resolution",
            )
        }
    }

    @Test
    fun theRelaxedPolicyIsReachedByItsChainedIdentity() {
        // The identity says what the policy is: the base rule set, then the link that relaxes it.
        val o = QuodlibetPolicies.resolve("email.byte-stable+lenient", 1)
        assertTrue(o is Outcome.Success)
        assertSame(EmailPolicy.ByteStableV1Lenient, o.data)
    }

    @Test
    fun anUnknownPolicyIsRefusedRatherThanApproximated() {
        // Every one of these is a well-formed chain of links this module publishes, or a link it does
        // not. None of them may resolve to something close enough.
        val o = QuodlibetPolicies.resolve("email.byte-stable+nfc.u17", 1)
        assertTrue(o is Outcome.Failure && o.exception is PolicyIdentityError.UnknownLink)

        val stale = QuodlibetPolicies.resolve("email.lenient", 1)
        assertTrue(stale is Outcome.Failure && stale.exception is PolicyIdentityError.UnknownLink)
    }

    @Test
    fun aVersionThisBuildDoesNotCarryIsRefused() {
        // Same id, different rules epoch: resolving it to v1 would hand back bytes the caller's stored
        // values were never derived under.
        val o = QuodlibetPolicies.resolve("email.byte-stable", 2)
        assertTrue(o is Outcome.Failure, "a version that does not exist must not resolve")
        val error = o.exception
        assertTrue(
            error is PolicyIdentityError.VersionMismatch,
            "expected VersionMismatch, got ${error::class.simpleName}",
        )
        assertEquals(listOf(1), error.available)
    }
}
