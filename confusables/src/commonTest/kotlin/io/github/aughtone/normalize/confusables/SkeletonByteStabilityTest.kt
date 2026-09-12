package io.github.aughtone.normalize.confusables

import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * A skeleton is a comparison artifact rather than a stored identity, so the stakes differ from the other
 * frozen corpora here - but the policy is still published, and a caller who did store one deserves the
 * same guarantee. A new Unicode release is a new policy constant, never a change to this one.
 */
class SkeletonByteStabilityTest {

    private fun skeleton(value: String): String =
        when (val outcome = normalizeSkeleton(value, ConfusablePolicy.SkeletonU17)) {
            is Outcome.Success -> outcome.data.canonical
            is Outcome.Failure -> throw AssertionError(
                "FROZEN: <$value> must reduce, failed with ${outcome.exception::class.simpleName}",
            )
        }

    @Test
    fun lookalikesCollideAndUnlikeThingsDoNot() {
        // The reason this module exists: a Cyrillic er and a Latin p are different characters that draw
        // the same, and a spoofed name must reduce to the target's skeleton.
        val cyrillicPaypal = "раypal"   // р and а are Cyrillic
        val latinPaypal = "paypal"
        assertEquals(skeleton(latinPaypal), skeleton(cyrillicPaypal), "a lookalike must collide")
        // `paypa1` is *also* a lookalike - the digit one reduces to `l` - so the negative case has to be
        // text that genuinely looks different.
        assertEquals(skeleton("paypal"), skeleton("paypa1"), "a digit-for-letter swap must collide too")
        assertNotEquals(skeleton("paypal"), skeleton("example"), "different-looking text must not collide")
    }

    @Test
    fun theTransformIsFrozen() {
        // Every pin here is read off the mapping data rather than guessed: the prototype is the
        // character the confusables file maps *to*, which is not always the one you would expect.
        val corpus = listOf(
            "paypal" to "paypal",     // p, a, y and l are prototypes already, so nothing moves
            "example" to "exarnple",  // m reduces to `rn` - the classic pair this table exists for
            "1" to "l",               // the digit one reduces to the letter, not the other way round
            "0" to "O",               // and zero to the capital letter
            "" to "",
        )
        for ((input, expected) in corpus) {
            assertEquals(expected, skeleton(input), "FROZEN: skeleton of <$input>")
        }
    }

    @Test
    fun multiCharacterPrototypesAreApplied() {
        // The `oe` ligature has a prototype of two characters, which is why the mapping cannot be a
        // simple character-to-character table.
        assertEquals(skeleton("oe"), skeleton("œ"), "a ligature must reduce to its letters")
    }

    @Test
    fun defaultIgnorableCharactersAreRemoved() {
        // A zero-width joiner is invisible, so a name carrying one looks identical to one without.
        val withJoiner = "pay" + Char(0x200D) + "pal"
        assertEquals(skeleton("paypal"), skeleton(withJoiner), "an invisible character must not change the skeleton")
    }

    @Test
    fun theTransformIsIdempotent() {
        // UTS-39 states the transform is idempotent, so a caller that reduces twice gets the same value.
        for (input in listOf("paypal", "раypal", "œuvre", "ＡＢＣ", "Ω")) {
            val once = skeleton(input)
            assertEquals(once, skeleton(once), "FROZEN: not idempotent for <$input>")
        }
    }

    @Test
    fun rightToLeftTextReducesThroughItsDisplayOrder() {
        // The skeleton is defined through the bidirectional algorithm, so a right-to-left string is laid
        // out before it is reduced. This pins that the pipeline runs at all for such input.
        val hebrew = "אבג"
        assertEquals(skeleton(hebrew), skeleton(hebrew), "stable for right-to-left text")
        assertTrue(skeleton(hebrew).isNotEmpty())
    }

    @Test
    fun unpairedSurrogatesAreRefused() {
        val broken = "a" + Char(0xD800) + "b"
        val outcome = normalizeSkeleton(broken, ConfusablePolicy.SkeletonU17)
        assertTrue(
            outcome is Outcome.Failure && outcome.exception is ConfusableNormalizationError.UnpairedSurrogate,
            "FROZEN: a lone surrogate must be refused",
        )
    }

    @Test
    fun policyIdentityIsFrozen() {
        assertEquals("skeleton.u17", ConfusablePolicy.SkeletonU17.id)
        assertEquals(1, ConfusablePolicy.SkeletonU17.version)
    }

    @Test
    fun thePolicyResolvesFromItsOwnIdentity() {
        val outcome = ConfusablesPolicies.resolve("skeleton.u17", 1)
        assertTrue(outcome is Outcome.Success)
        assertSame(ConfusablePolicy.SkeletonU17, outcome.data)

        val laterRelease = ConfusablesPolicies.resolve("skeleton.u18", 1)
        assertTrue(laterRelease is Outcome.Failure && laterRelease.exception is PolicyIdentityError.UnknownLink)
    }
}
