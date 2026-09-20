package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.common.PolicyId
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
                    assertEquals(policy, o.data, "<${policy.id}> resolved to a different policy")
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
        // policy by resolution gives an equal policy and therefore the same output.
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
    fun theSubaddressRemovingPolicyIsReachedByItsChainedIdentity() {
        // The identity says what the policy is: the base rule set, then the option that removes the tag.
        val o = QuodlibetPolicies.resolve("email:subaddress.removed", 1)
        assertTrue(o is Outcome.Success)
        assertSame(EmailPolicy.SubaddressRemoved, o.data)
    }

    @Test
    fun anUnknownPolicyIsRefusedRatherThanApproximated() {
        // Every one of these is a well-formed chain of links this module publishes, or a link it does
        // not. None of them may resolve to something close enough.
        val o = QuodlibetPolicies.resolve("email:nfc.u17", 1)
        assertTrue(o is Outcome.Failure && o.exception is PolicyIdentityError.UnknownLink)

        val stale = QuodlibetPolicies.resolve("email.lenient", 1)
        assertTrue(stale is Outcome.Failure && stale.exception is PolicyIdentityError.UnknownLink)

        // Withdrawn in 0.0.3 with a clean break: the policy keeps the subaddress, it does not relax a rule.
        val withdrawn = QuodlibetPolicies.resolve("email.byte-stable:lenient", 1)
        assertTrue(withdrawn is Outcome.Failure && withdrawn.exception is PolicyIdentityError, "got $withdrawn")

        // Withdrawn in 0.0.4: every `+`-joined id, and the base that used to strip the subaddress by default.
        for (id in listOf("email.byte-stable", "email.byte-stable+subaddressed", "text.u17+trim+lower")) {
            val gone = QuodlibetPolicies.resolve(id, 1)
            assertTrue(gone is Outcome.Failure && gone.exception is PolicyIdentityError, "<$id> must not resolve, got $gone")
        }
    }

    @Test
    fun aVersionThisBuildDoesNotCarryIsRefused() {
        // Same id, different rules epoch: resolving it to v1 would hand back bytes the caller's stored
        // values were never derived under.
        val o = QuodlibetPolicies.resolve("email:subaddress.removed", 2)
        assertTrue(o is Outcome.Failure, "a version that does not exist must not resolve")
        val error = o.exception
        assertTrue(
            error is PolicyIdentityError.VersionMismatch,
            "expected VersionMismatch, got ${error::class.simpleName}",
        )
        assertEquals(listOf(1), error.available)
    }

    @Test
    fun everyPublishedIdSurvivesThePortableSpelling() {
        // #29 asked for this over *every* published id, not a sample: the portable form exists for slots
        // that cannot hold a `:`, and an id that does not come back from it is an identity a caller can
        // store and never resolve again. A sample cannot cover a name the mapping happens to mangle.
        for (policy in QuodlibetPolicies.policies) {
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
