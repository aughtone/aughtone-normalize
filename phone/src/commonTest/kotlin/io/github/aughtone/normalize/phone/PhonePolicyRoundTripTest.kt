package io.github.aughtone.normalize.phone

import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stored `(id, version)` must come back as the policy that produced it - including the region
 * policies, which are built on demand rather than enumerated.
 *
 * That is the interesting case here. There are hundreds of regions and a caller uses one or two, so the
 * module does not publish a constant per region; resolution rebuilds the policy from the `region-xx`
 * link instead. If it could not, a value normalized under `phone.e164+region-ca` would be impossible to
 * re-derive from what the caller stored, which is the whole point of keeping the identity.
 */
class PhonePolicyRoundTripTest {

    @Test
    fun everyPublishedPolicyResolvesFromItsOwnIdentity() {
        for (policy in PhonePolicies.policies) {
            when (val outcome = PhonePolicies.resolve(policy.id, policy.version)) {
                is Outcome.Success -> assertEquals(policy.id, outcome.data.id)
                is Outcome.Failure -> throw AssertionError(
                    "published policy <${policy.id}> does not resolve: ${outcome.exception::class.simpleName}",
                )
            }
        }
    }

    @Test
    fun aRegionPolicyResolvesAndNormalizesIdentically() {
        for (id in listOf("phone.e164+region-ca", "phone.e164+region-gb+lenient")) {
            val outcome = PhonePolicies.resolve(id, 1)
            assertTrue(outcome is Outcome.Success, "<$id> must resolve")
            assertEquals(id, outcome.data.id)

            val resolved = outcome.data as PhonePolicy
            val direct = if (id.endsWith("+lenient")) {
                PhonePolicy.e164ForRegionLenient(id.substringAfter("region-").substringBefore('+'))
            } else {
                PhonePolicy.e164ForRegion(id.substringAfter("region-"))
            }
            val value = "+12125550123"
            assertEquals(
                (normalizePhone(value, direct) as Outcome.Success).data.canonical,
                (normalizePhone(value, resolved) as Outcome.Success).data.canonical,
                "<$id> normalized differently when reached by resolution",
            )
        }
    }

    @Test
    fun anUnusableIdentityIsRefusedRatherThanApproximated() {
        // A region with no metadata cannot be rebuilt, and must not fall back to a policy for somewhere
        // else - a token derived under it would be a real number in the wrong country.
        val unknownRegion = PhonePolicies.resolve("phone.e164+region-zz", 1)
        assertTrue(unknownRegion is Outcome.Failure, "an unknown region must not resolve")

        val unknownLink = PhonePolicies.resolve("phone.e164+strict", 1)
        assertTrue(unknownLink is Outcome.Failure && unknownLink.exception is PolicyIdentityError.UnknownLink)

        val wrongVersion = PhonePolicies.resolve("phone.e164", 2)
        assertTrue(wrongVersion is Outcome.Failure && wrongVersion.exception is PolicyIdentityError.VersionMismatch)
    }
}
