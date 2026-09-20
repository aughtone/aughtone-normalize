package io.github.aughtone.normalize.phone

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two entry points must answer the same way about the number.
 *
 * `normalizePhoneWithExtension` splits at the marker and hands the text before it to [normalizePhone], so
 * the number it returns is that function's output and an input whose number part is refused there is
 * refused here with the same error. This test is the one that says so, by running each case through
 * **both** functions and comparing — every other test in this area asserts each path separately, which is
 * how the two came apart.
 *
 * They came apart badly. Before this, the whole input went to the phonenumber library as soon as a marker
 * appeared, so none of this module's rules reached the number: `+43 1 58058-0#4` returned `+431580580`,
 * folding the Austrian Durchwahl into the subscriber number, while `+43 1 58058-0` alone was correctly
 * refused. See aughtone/aughtone-normalize#32, and #30 for why that number is the wrong one.
 */
class PhoneExtensionAgreementTest {

    /** An input carrying a marker, and the text before that marker. */
    private val split: List<Pair<String, String>> = listOf(
        // Ordinary numbers with an extension: both paths accept.
        "+1 212 555 0123 x4" to "+1 212 555 0123",
        "+1 212 555 0123x4" to "+1 212 555 0123",
        "+1 212 555 0123#4" to "+1 212 555 0123",
        "+1 212 555 0123,4" to "+1 212 555 0123",
        "+1 212 555 0123;4" to "+1 212 555 0123",
        "+1 212 555 0123;ext=4" to "+1 212 555 0123",
        "+1 (212) 555-0123 ext. 99" to "+1 (212) 555-0123",
        "+49 89 636 48018 x7" to "+49 89 636 48018",

        // The ambiguity guard from #30: the number part is refused, so the input is refused.
        "+43 1 58058-0#4" to "+43 1 58058-0",
        "+43 1 58058-0 x4" to "+43 1 58058-0",
        "+49 30 12345678-12 x4" to "+49 30 12345678-12",
        "+49 89 636 48018 9 x1" to "+49 89 636 48018 9",
        "+41 44 123 45 67-8 x2" to "+41 44 123 45 67-8",

        // A character the number rules refuse stays refused when an extension follows it.
        "+1 212*555 0123 x4" to "+1 212*555 0123",
        "+1 212\$555 0123 x4" to "+1 212\$555 0123",
        "+1 212 555 0123 FLOWERS x4" to "+1 212 555 0123 FLOWERS",

        // A marker introducing nothing: the number in front of it is read as usual.
        "+1 212 555 0123#" to "+1 212 555 0123",
        "+1 212 555 0123 ext" to "+1 212 555 0123",
        "+43 1 58058-0#" to "+43 1 58058-0",

        // A marker with the number after it leaves nothing in front of it.
        "#12125550123" to "",
        ",12125550123" to "",

        // The FIRST marker is the boundary, so a stray one truncates the number rather than being read
        // as part of it. `+1 212` is not a number, so the input is refused.
        "+1 212;555 0123 x4" to "+1 212",
    )

    @Test
    fun theNumberIsWhatNormalizePhoneWritesForTheTextBeforeTheMarker() {
        for ((input, head) in split) {
            assertAgrees(input, head, ExtensionPolicy.E164, PhonePolicy.E164)
        }
    }

    @Test
    fun theyAgreeForARegionPolicyToo() {
        val region = "DE"
        val cases = listOf(
            "0301 2345678 x4" to "0301 2345678",
            "0301 2345678-12 x4" to "0301 2345678-12",
            "030 12345678#9" to "030 12345678",
        )
        for ((input, head) in cases) {
            assertAgrees(input, head, ExtensionPolicy.forRegion(region), PhonePolicy.e164ForRegion(region))
        }
    }

    @Test
    fun aMarkerFreeInputIsTheSameReadingInBoth() {
        for (input in listOf("+12125550123", "+1 (212) 555-0123", "+43 1 58058-0", "1-800-FLOWERS", "")) {
            assertAgrees(input, input, ExtensionPolicy.E164, PhonePolicy.E164)
        }
    }

    private fun assertAgrees(input: String, head: String, policy: ExtensionPolicy, number: PhonePolicy) {
        val withExtension = normalizePhoneWithExtension(input, policy)
        val direct = normalizePhone(head, number)
        when (direct) {
            is Outcome.Success -> {
                assertTrue(
                    withExtension is Outcome.Success,
                    "<$input>: normalizePhone accepts <$head> and this refused it: $withExtension",
                )
                assertEquals(
                    direct.data,
                    withExtension.data.number,
                    "<$input>: the number must be normalizePhone's reading of <$head>",
                )
            }

            is Outcome.Failure -> {
                assertTrue(
                    withExtension is Outcome.Failure,
                    "<$input>: normalizePhone refuses <$head> and this accepted it: $withExtension",
                )
                assertEquals(
                    direct.exception::class,
                    withExtension.exception::class,
                    "<$input>: refused for a different reason than <$head>",
                )
            }
        }
    }
}
