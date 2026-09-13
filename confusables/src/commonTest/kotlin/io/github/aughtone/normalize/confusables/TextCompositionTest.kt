package io.github.aughtone.normalize.confusables

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.NormalizationStep
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.StepPhase
import io.github.aughtone.normalize.unicode.TextPolicy
import io.github.aughtone.normalize.unicode.TextPolicyError
import io.github.aughtone.normalize.unicode.UnicodeRelease
import io.github.aughtone.normalize.unicode.normalizeText
import io.github.aughtone.normalize.username.UsernamePolicy
import io.github.aughtone.normalize.username.normalizeUsername
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A configured text policy travels whole into another module's chain, and takes steps of its own.
 *
 * `:quodlibet` carries no Unicode data, so a username normalizer that wants NFC takes a text policy as a
 * step - and the composed id carries every rule in it, not a single-link alias for one of them. That is
 * what keeps one configuration to one spelling wherever it appears.
 */
class TextCompositionTest {

    @Test
    fun aTextPolicyComposesIntoAUsernameChainAsOneGroup() {
        val outcome = normalizeUsername("A\u030Alice", UsernamePolicy.Basic, listOf(TextPolicy.NfcU17))
        assertTrue(outcome is Outcome.Success)
        assertEquals("username.basic+text.u17+nfc", outcome.data.policyId)
        // The username base runs first and lowercases the ASCII A; NFC then composes it with the ring.
        assertEquals("\u00E5lice", outcome.data.canonical)

        val configured = TextPolicy(UnicodeRelease.U17) { unicode { collapseSpace(); nfc() } }
        val composed = normalizeUsername("  a  b  ", UsernamePolicy.Basic, listOf(configured))
        assertTrue(composed is Outcome.Success)
        assertEquals("username.basic+text.u17+collapse-space+nfc", composed.data.policyId)
    }

    @Test
    fun aTextPolicyTakesTheSkeletonAsAStep() {
        val caseless = TextPolicy(UnicodeRelease.U17) { unicode { trim(); casefold() } }
        val latin = normalizeText("  PayPal ", caseless, listOf(ConfusablePolicy.SkeletonU17))
        val cyrillic = normalizeText("\u0420\u0430yPal", caseless, listOf(ConfusablePolicy.SkeletonU17))
        assertTrue(latin is Outcome.Success && cyrillic is Outcome.Success)
        assertEquals("text.u17+trim+casefold+skeleton.u17", latin.data.policyId)
        assertEquals(latin.data.canonical, cyrillic.data.canonical)
    }

    @Test
    fun aStepFrozenAgainstAnotherReleaseIsRefused() {
        val otherRelease = object : NormalizationStep {
            override val links = listOf(PolicyLink("example.u18", LinkKind.Step, StepPhase.Map))
            override fun apply(value: String) = value
        }
        val outcome = normalizeText("x", TextPolicy.CaselessU17, listOf(otherRelease))
        assertTrue(outcome is Outcome.Failure && outcome.exception is TextPolicyError.MismatchedRelease)
    }
}
