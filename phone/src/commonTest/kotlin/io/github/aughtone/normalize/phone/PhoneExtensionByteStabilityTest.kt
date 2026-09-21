package io.github.aughtone.normalize.phone

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * What the phone normalizer does with an extension, and with the ordinary formatting an extension is
 * written in. Every expectation here is a published contract: the refusals as much as the acceptances,
 * because the failure this file exists to prevent is not a refusal but its opposite - an extension folded
 * into the subscriber number, producing a different, valid-looking number that nothing reports.
 *
 * `+43 1 58058-0` is a real spelling of a real thing: the Durchwahl convention of German-speaking
 * countries, where the digits after the hyphen are an extension. Folding them in gives `+431580580`,
 * which is a plausible Austrian number and the wrong one. A caller hashes it and never finds out.
 *
 * ## Why this cannot be decided by characters alone
 *
 * `-`, `.`, `/`, `(`, `)` and the space all separate parts of ordinary numbers as well as introducing
 * extensions, so no character test distinguishes them. What can be asked is whether the number is already
 * valid without its last group. That question needs the national number plan, which is why the rule lives
 * beside the metadata rather than in a character filter.
 *
 * ## What may change here
 *
 * Adding cases. Changing one means the canonical output moved for input that already had one, which is a
 * new policy version rather than an edit - see `docs/knowledge/specifications/DOC-0001-normalization-suite.md`.
 */
class PhoneExtensionByteStabilityTest {

    /** Input to canonical output. Ordinary numbers, written the ways people write them. */
    private val accepted: List<Pair<String, String>> = listOf(
        "+12125550123" to "+12125550123",
        "+1 212 555 0123" to "+12125550123",
        "+1 (212) 555-0123" to "+12125550123",
        "+1-212-555-0123" to "+12125550123",
        "+44 20 7123 4567" to "+442071234567",
        "+33 1 42 68 53 00" to "+33142685300",

        // Variable-length plans: the leading part of each of these is itself a valid number, and they are
        // still one number. A rule that refused on that alone would refuse ordinary input.
        "+49 30 12345678" to "+493012345678",
        "+49 89 636 48018" to "+498963648018",
    )

    /** Input to the refusal it must produce, under both the strict and the lenient policy. */
    private val refused: List<Pair<String, String>> = listOf(
        // An extension marker says what follows is not the number. Dropping it splices the digits on.
        "+1 212 555 0123 #4" to "ExtensionNotSupported",
        "+12125550123,4" to "ExtensionNotSupported",
        "+12125550123;ext=4" to "ExtensionNotSupported",

        // The Durchwahl hyphen, with a complete number before it.
        "+49 30 12345678-12" to "AmbiguousTrailingGroup",
        "+43 1 58058-0" to "AmbiguousTrailingGroup",
        "+41 44 123 45 67-8" to "AmbiguousTrailingGroup",

        // A trailing group after a space, where the number is complete without it and invalid with it.
        "+1 212 555 0123 4" to "AmbiguousTrailingGroup",

        // Trailing formatting after the last group does not excuse it. A separator ends the trailing group
        // only when a digit follows, so a stray space cannot clear the guard - which is how
        // `+43 1 58058-0 x4` reached the dependency and folded. See aughtone/aughtone-normalize#32.
        "+43 1 58058-0 " to "AmbiguousTrailingGroup",
        "+49 30 12345678-12  " to "AmbiguousTrailingGroup",

        // Letter-dialled extensions were always refused, and stay refused.
        "+1 212 555 0123 x123" to "LetterNotSupported",
        "+1 212 555 0123 ext. 4" to "LetterNotSupported",
    )

    @Test
    fun ordinaryNumbersProduceTheFrozenCanonicalBytes() {
        for ((input, expected) in accepted) {
            for (policy in listOf(PhonePolicy.E164, PhonePolicy.E164Lenient)) {
                val outcome = normalizePhone(input, policy)
                assertTrue(outcome is Outcome.Success, "FROZEN CORPUS BROKEN: <$input> must normalize under $policy, got $outcome")
                assertEquals(expected, outcome.data.canonical, "FROZEN CORPUS BROKEN for <$input> under $policy")
            }
        }
    }

    @Test
    fun anExtensionIsRefusedUnderEveryPolicyRatherThanFoldedIn() {
        for ((input, error) in refused) {
            for (policy in listOf(PhonePolicy.E164, PhonePolicy.E164Lenient)) {
                val outcome = normalizePhone(input, policy)
                assertTrue(
                    outcome is Outcome.Failure,
                    "FROZEN CORPUS BROKEN: <$input> must be refused under $policy. Accepting it folds an " +
                        "extension into the subscriber number and yields a valid-looking wrong number.",
                )
                assertEquals(error, outcome.exception::class.simpleName, "<$input> under $policy")
            }
        }
    }

    @Test
    fun aRefusalCarriesNoPartOfTheNumber() {
        val outcome = normalizePhone("+43 1 58058-0", PhonePolicy.E164)
        assertTrue(outcome is Outcome.Failure)
        val message = outcome.exception.message.orEmpty()
        for (fragment in listOf("58058", "431", "+43")) {
            assertTrue(!message.contains(fragment), "the message must not carry <$fragment>: $message")
        }
    }
}
