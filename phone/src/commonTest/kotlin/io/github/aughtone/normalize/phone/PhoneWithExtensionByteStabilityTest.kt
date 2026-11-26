package io.github.aughtone.normalize.phone

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN CORPUS. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * What `normalizePhoneWithExtension` writes: the E.164 number, and the extension beside it. Both are
 * tokenized by callers, so both are frozen. A failure here means the split between number and extension
 * moved, which silently re-keys every token derived under the old split.
 *
 * Adding cases is allowed. Editing one is a new policy version, not an edit.
 */
class PhoneWithExtensionByteStabilityTest {

    private fun read(value: String): NormalizedPhoneWithExtension {
        val outcome = normalizePhoneWithExtension(value, ExtensionPolicy.E164)
        assertTrue(outcome is Outcome.Success, "FROZEN CORPUS BROKEN: <$value> must normalize, got $outcome")
        return outcome.data
    }

    /** Input to number and extension. A `null` extension means the input carried none. */
    private val corpus: List<Triple<String, String, String?>> = listOf(
        // No marker: the number is read by the ordinary path and nothing is delegated.
        Triple("+12125550123", "+12125550123", null),
        Triple("+1 (212) 555-0123", "+12125550123", null),
        Triple("+49 89 636 48018", "+498963648018", null),

        // Markers: the marker is the boundary, so the number is the text before it read by the ordinary
        // rules, and the extension is the digits after it.
        // `#4`, `,4` and `;4` stood here, each reading as extension `4`, until
        // aughtone/aughtone-normalize#38. They are post-dial markers rather than extension markers, so
        // what followed them was a dialling instruction being minted as a `phone.extension` token. All
        // three now refuse; see `postDial` below for the inputs and the error.
        Triple("+1 212 555 0123x4", "+12125550123", "4"),
        Triple("+1 212 555 0123 ext 4", "+12125550123", "4"),
        Triple("+1 212 555 0123 ext. 4", "+12125550123", "4"),
        Triple("+1 212 555 0123 extension 4", "+12125550123", "4"),
        Triple("+1 212 555 0123 x123", "+12125550123", "123"),
        Triple("+1 212 555 0123;ext=4", "+12125550123", "4"),
        Triple("+1 212 555 0123 x 4", "+12125550123", "4"),

        // A marker introducing nothing leaves the number alone and reads no extension - the behaviour the
        // phonenumber library takes from the release fixing aughtone/aughtone-phonenumber#23 and #24.
        // `+1 212 555 0123#` and `+1 212 555 0123,` stood here, each the number with no extension, until
        // aughtone/aughtone-normalize#38: a dangling post-dial marker is no longer a marker this function
        // knows, so the input reaches `normalizePhone` whole and is refused. A dangling *extension* marker
        // still reads nothing, which is the next three lines.
        Triple("+1 212 555 0123 ext", "+12125550123", null),
        Triple("+1 212 555 0123 x", "+12125550123", null),
        Triple("+1 212 555 0123;ext=", "+12125550123", null),

        // A space-separated trailing group is refused only when the whole number is invalid with it, so
        // this one is accepted as a number and an extension - the same reading `normalizePhone` gives the
        // text before the marker. #30 narrowed the rule deliberately; see its corpus.
        Triple("+49 89 636 48018 9 x1", "+4989636480189", "1"),

        // Extension digits fold to ASCII by the same rule as the number: any script, one spelling.
        Triple("+1 212 555 0123 ext ４", "+12125550123", "4"),
        Triple("+1 212 555 0123 ext ٤", "+12125550123", "4"),
        Triple("+1 212 555 0123 ext ४", "+12125550123", "4"),
        // Supplementary plane: MATHEMATICAL BOLD DIGIT FOUR, U+1D7D2. An astral character is a valid
        // scalar value, so it survives compilation on every target - unlike a lone surrogate, which does
        // not and is constructed rather than written wherever one is needed (#34).
        Triple("+1 212 555 0123 ext 𝟒", "+12125550123", "4"),
    )

    /**
     * Input that carries a post-dial marker, and must be refused as one. Frozen in the same sense as the
     * corpus: a refusal that turns back into an acceptance mints tokens from dialling instructions, which
     * is what aughtone/aughtone-normalize#38 removed.
     */
    private val postDial: List<String> = listOf(
        // Was extension "4" under all three, before #38.
        "+1 212 555 0123#4",
        "+1 212 555 0123,4",
        "+1 212 555 0123;4",
        // Was the number with a null extension, before #38.
        "+1 212 555 0123#",
        "+1 212 555 0123,",
    )

    @Test
    fun theNumberAndExtensionAreTheFrozenBytes() {
        for ((input, number, extension) in corpus) {
            val read = read(input)
            assertEquals(number, read.number.canonical, "FROZEN CORPUS BROKEN for <$input>: number")
            assertEquals(extension, read.extension?.canonical, "FROZEN CORPUS BROKEN for <$input>: extension")
        }
    }

    @Test
    fun theNumberIsExactlyWhatNormalizePhoneWritesWhereItAcceptsTheInput() {
        for ((input, _, _) in corpus) {
            // Marker-free input only: with a marker the number comes from the text before it, which is
            // what `PhoneExtensionAgreementTest` compares. A null extension no longer means no marker.
            if (findMarker(input) != null) continue
            val direct = normalizePhone(input, PhonePolicy.E164)
            assertTrue(direct is Outcome.Success, "<$input>")
            assertEquals(direct.data, read(input).number, "<$input> differs from normalizePhone")
        }
    }

    @Test
    fun theIdentitiesAreFrozen() {
        assertEquals("phone.extension", ExtensionPolicy.E164.id)
        assertEquals(1, ExtensionPolicy.E164.version)
        val read = read("+1 212 555 0123 x4")
        assertEquals("phone.extension", read.extension?.policyId)
        assertEquals(1, read.extension?.policyVersion)
        assertEquals("phone.e164", read.number.policyId)
    }

    @Test
    fun aPostDialMarkerIsRefusedByBothEntryPoints() {
        // The error names what it found, rather than calling it an extension marker: #38's whole point is
        // that `#`, `,` and `;` do not say an extension follows.
        for (input in postDial) {
            val outcome = normalizePhoneWithExtension(input, ExtensionPolicy.E164)
            assertTrue(
                outcome is Outcome.Failure && outcome.exception is PhoneNormalizationError.PostDialNotSupported,
                "<$input> must be refused as post-dial, got $outcome",
            )
            val direct = normalizePhone(input, PhonePolicy.E164)
            assertTrue(
                direct is Outcome.Failure && direct.exception is PhoneNormalizationError.PostDialNotSupported,
                "<$input> must be refused as post-dial by normalizePhone too, got $direct",
            )
        }
    }

    @Test
    fun aPostDialMarkerInsideTheExtensionIsRefusedByName() {
        // `x4#5` is an extension marker and then a post-dial one. Only this entry point reaches the `#`
        // at all - `normalizePhone` refuses the whole input on the `x` first, which is its own rule and
        // not a disagreement. What matters is that the extension reader does not call the `#` an
        // unsupported character: it has a name, and both paths use it. See #38.
        val outcome = normalizePhoneWithExtension("+1 212 555 0123 x4#5", ExtensionPolicy.E164)
        assertTrue(
            outcome is Outcome.Failure && outcome.exception is PhoneNormalizationError.PostDialNotSupported,
            "must be refused as post-dial, got $outcome",
        )
    }

    @Test
    fun aDanglingExtensionMarkerReadsNoExtensionWhereADanglingPostDialMarkerRefuses() {
        // These look inconsistent and are not. A dangling extension marker introduces nothing, so the
        // number is complete and dropping the marker invents no data - the cost being that a truncated
        // extension reads the same as one never written. A dangling post-dial marker is not a marker this
        // function recognises at all: the input goes whole to `normalizePhone`, which refuses it, because
        // the characters after it would have been a dialling instruction rather than an extension.
        // Different markers, different meanings. See aughtone/aughtone-normalize#38.
        for (input in listOf("+1 212 555 0123 ext", "+1 212 555 0123 x", "+1 212 555 0123;ext=")) {
            assertEquals(null, read(input).extension, "<$input> must read no extension")
            assertEquals("+12125550123", read(input).number.canonical, "<$input> must keep the number")
        }
        for (input in listOf("+1 212 555 0123#", "+1 212 555 0123,", "+1 212 555 0123;")) {
            val outcome = normalizePhoneWithExtension(input, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused, got $outcome")
        }
    }

    @Test
    fun theSeparateArgumentOverloadReadsBothPiecesAndGuessesNothing() {
        // For a caller who already holds the two apart. No marker vocabulary is involved on either side,
        // so there is nothing to guess; the extension folds across scripts exactly as in the free-text
        // path. Added by aughtone/aughtone-normalize#38.
        val read = normalizePhoneWithExtension("+1 (212) 555-0123", "4", ExtensionPolicy.E164)
        assertTrue(read is Outcome.Success, "must normalize, got $read")
        assertEquals("+12125550123", read.data.number.canonical)
        assertEquals("4", read.data.extension?.canonical)
        assertEquals("phone.extension", read.data.extension?.policyId)
        assertEquals(1, read.data.extension?.policyVersion)

        for (absent in listOf(null, "", " ")) {
            val outcome = normalizePhoneWithExtension("+12125550123", absent, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Success, "<$absent> must normalize, got $outcome")
            assertEquals(null, outcome.data.extension, "<$absent> must read no extension")
        }

        // Any decimal digits, one spelling - the same frozen fold the number gets.
        val folded = normalizePhoneWithExtension("+12125550123", "４", ExtensionPolicy.E164)
        assertTrue(folded is Outcome.Success, "got $folded")
        assertEquals("4", folded.data.extension?.canonical)

        // The field is the extension, not something to re-read: a marker left in it is refused rather
        // than stripped, on its letter or by its own name.
        for (input in listOf("x4", "ext 4", "#4", ",4")) {
            val outcome = normalizePhoneWithExtension("+12125550123", input, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused, got $outcome")
        }
    }

    @Test
    fun anAmbiguousTrailingGroupIsStillRefused() {
        // Including when an extension marker follows it. The number is read by `normalizePhone` from the
        // text before the marker, so the guard cannot be walked around by writing the extension out:
        // `+43 1 58058-0#4` once returned `+431580580`, the wrong line. See aughtone/aughtone-normalize#32.
        for (input in listOf(
            "+43 1 58058-0",
            "+49 30 12345678-12",
            // `#4` refuses as post-dial now rather than as an ambiguous group (#38); either way the
            // guard cannot be walked around by writing the extension out.
            "+43 1 58058-0#4",
            "+43 1 58058-0 x4",
            "+49 30 12345678-12 x4",
            "+41 44 123 45 67-8 x2",
        )) {
            val outcome = normalizePhoneWithExtension(input, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused, got $outcome")
        }
    }

    @Test
    fun lettersOutsideAMarkerAreRefused() {
        // A vanity number must never become a number the caller did not type. The number part goes through
        // `normalizePhone`, which refuses a letter, and the extension part takes digits only - so neither
        // half can reach the keypad conversion the dependency performs on a run of three or more letters.
        for (input in listOf(
            "1-800-FLOWERS",
            "+1 212 555 0123 FLOWERS x4",
            "+1 800 CALL x2",
            "+1 212 555 0123 x4 FLOWERS",
            "+1 212 555 0123 x4x5",
        )) {
            val outcome = normalizePhoneWithExtension(input, ExtensionPolicy.E164)
            assertTrue(outcome is Outcome.Failure, "<$input> must be refused, got $outcome")
        }
    }
}
