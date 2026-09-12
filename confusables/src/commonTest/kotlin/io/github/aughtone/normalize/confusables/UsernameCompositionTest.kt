package io.github.aughtone.normalize.confusables

import io.github.aughtone.normalize.common.plus
import io.github.aughtone.normalize.quodlibet.QuodlibetPolicies
import io.github.aughtone.normalize.username.UsernamePolicy
import io.github.aughtone.normalize.username.normalizeUsername
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The composition mechanism, end to end, across two modules that do not depend on each other.
 *
 * `:quodlibet` carries no Unicode data and never will; this module carries the confusable tables. The
 * username normalizer accepts a step by the `:common` interface, so a caller that wants anti-spoofing
 * depends on both and composes - and the composed policy's identity names the transform, which is what
 * keeps the folded form from being mistaken for the plain one.
 */
class UsernameCompositionTest {

    private val skeleton = ConfusablePolicy.SkeletonU17

    private fun plain(value: String) =
        (normalizeUsername(value, UsernamePolicy.Basic) as Outcome.Success).data

    private fun folded(value: String) =
        (normalizeUsername(value, UsernamePolicy.Basic, listOf(skeleton)) as Outcome.Success).data

    @Test
    fun theComposedChainNamesTheTransformInItsIdentity() {
        assertEquals("username.basic", plain("alice").policyId)
        assertEquals("username.basic+skeleton.u17", folded("alice").policyId)
    }

    @Test
    fun theFoldedFormIsADifferentIdentityFromThePlainOne() {
        // Not a variant of the same policy: a value folded for spoof detection must never be compared
        // against one that was not, which the differing identities make impossible to do by accident.
        assertNotEquals(plain("alice").policyId, folded("alice").policyId)
    }

    @Test
    fun aLookalikePairIsDistinctPlainAndEqualFolded() {
        val latin = "paypal"
        val cyrillic = "раypal" // р and а are Cyrillic

        // The identity keeps them apart: they are different accounts, and the suite's other policies
        // exist to preserve exactly that distinction.
        assertNotEquals(plain(latin).canonical, plain(cyrillic).canonical)

        // The check brings them together, which is the entire point of the fold - and the reason it must
        // never be used as an account key.
        assertEquals(folded(latin).canonical, folded(cyrillic).canonical)
    }

    @Test
    fun aComposedIdentityResolvesAcrossBothModules() {
        // Resolution follows the caller's dependencies: neither module alone can resolve this chain, and
        // combining the two resolvers is how a program says which modules it trusts to name policies.
        val resolvers = QuodlibetPolicies + ConfusablesPolicies
        val outcome = resolvers.resolve("skeleton.u17", 1)
        assertTrue(outcome is Outcome.Success, "the composite resolver must cover both modules")

        val quodlibetAlone = QuodlibetPolicies.resolve("skeleton.u17", 1)
        assertTrue(quodlibetAlone is Outcome.Failure, "one module must not resolve another module's link")
    }
}
