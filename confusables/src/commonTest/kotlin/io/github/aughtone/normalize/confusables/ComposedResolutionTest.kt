package io.github.aughtone.normalize.confusables

import io.github.aughtone.normalize.common.ComposedPolicy
import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.normalize.common.plus
import io.github.aughtone.normalize.quodlibet.QuodlibetPolicies
import io.github.aughtone.normalize.unicode.TextPolicy
import io.github.aughtone.normalize.unicode.UnicodePolicies
import io.github.aughtone.normalize.unicode.UnicodeRelease
import io.github.aughtone.normalize.unicode.normalizeText
import io.github.aughtone.normalize.username.UsernamePolicy
import io.github.aughtone.normalize.username.normalizeUsername
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A stored composed id comes back as the base and steps that produced it, and re-derives the same bytes.
 *
 * This is what a store of composed tokens needs to add a row: resolve the id beside the existing rows,
 * then normalize the new value under exactly that base and those steps.
 */
class ComposedResolutionTest {

    private val resolvers = QuodlibetPolicies + UnicodePolicies + ConfusablesPolicies

    // "paypal" with a Cyrillic small er and small a, built from code points so nothing can substitute them.
    // Lowercase on purpose: a skeleton is case-sensitive, and the username base folds ASCII case only.
    private val lookalike = Char(0x0440).toString() + Char(0x0430) + "ypal"

    private fun composed(id: String): ComposedPolicy {
        val outcome = resolvers.resolve(id, 1)
        assertTrue(outcome is Outcome.Success, "<$id> must resolve, got $outcome")
        return outcome.data as ComposedPolicy
    }

    @Test
    fun aUsernameWithTheSkeletonRoundTrips() {
        val stored = (normalizeUsername("PayPal", UsernamePolicy.Basic, listOf(ConfusablePolicy.SkeletonU17)) as Outcome.Success).data
        val policy = composed(stored.policyId)
        assertEquals("username.basic:skeleton.u17", policy.id)
        val again = normalizeUsername(lookalike, policy.base as UsernamePolicy, policy.steps)
        assertEquals(stored.canonical, (again as Outcome.Success).data.canonical)
        assertEquals(stored.policyId, again.data.policyId)
    }

    @Test
    fun aUsernameWithATextPolicyRoundTrips() {
        val stored = (normalizeUsername("Alice", UsernamePolicy.Basic, listOf(TextPolicy.NfcU17)) as Outcome.Success).data
        val policy = composed(stored.policyId)
        assertEquals("username.basic:text.u17:nfc", policy.id)
        val again = normalizeUsername("ALICE", policy.base as UsernamePolicy, policy.steps)
        assertEquals(stored.canonical, (again as Outcome.Success).data.canonical)
        assertEquals(stored.policyId, again.data.policyId)
    }

    @Test
    fun aTextPolicyWithTheSkeletonRoundTrips() {
        val caseless = TextPolicy(UnicodeRelease.U17) { unicode { trim(); casefold() } }
        val stored = (normalizeText("  PayPal ", caseless, listOf(ConfusablePolicy.SkeletonU17)) as Outcome.Success).data
        val policy = composed(stored.policyId)
        assertEquals("text.u17:space.trimmed:case.folded:skeleton.u17", policy.id)
        val again = normalizeText(lookalike, policy.base as TextPolicy, policy.steps)
        assertEquals(stored.canonical, (again as Outcome.Success).data.canonical)
        assertEquals(stored.policyId, again.data.policyId)
    }

    @Test
    fun aChainNamingAModuleMissingFromTheResolverFails() {
        val withoutConfusables = QuodlibetPolicies + UnicodePolicies
        val outcome = withoutConfusables.resolve("username.basic:skeleton.u17", 1)
        assertTrue(outcome is Outcome.Failure && outcome.exception is PolicyIdentityError.UnknownLink, "got $outcome")

        val withoutUnicode = QuodlibetPolicies + ConfusablesPolicies
        val text = withoutUnicode.resolve("username.basic:text.u17:nfc", 1)
        assertTrue(text is Outcome.Failure && text.exception is PolicyIdentityError, "got $text")
    }

    @Test
    fun aConfiguredTextIdResolvesThroughTheCompositeAsItDoesAlone() {
        val id = "text.u17:space.trimmed:case.lower.ascii:empty.refused"
        val alone = UnicodePolicies.resolve(id, 1)
        val combined = resolvers.resolve(id, 1)
        assertTrue(alone is Outcome.Success && combined is Outcome.Success)
        assertEquals(alone.data, combined.data)
    }
}
