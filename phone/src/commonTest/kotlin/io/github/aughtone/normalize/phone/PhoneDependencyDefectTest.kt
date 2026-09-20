package io.github.aughtone.normalize.phone

import io.github.aughtone.phonenumber.PhoneNumberUtil
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Defects in the dependency that this module works around, pinned so the workaround cannot outlive them.
 *
 * **A failing test here is good news.** It means `io.github.aughtone:phonenumber` has fixed the defect,
 * and the workaround named in that test should be deleted rather than the expectation updated. Every
 * other frozen test in this suite says "do not change this"; this one says the opposite.
 *
 * Pinned against `phonenumber:0.0.3`. Upstream tickets: aughtone/aughtone-phonenumber#23, #24 and #22.
 */
class PhoneDependencyDefectTest {

    /**
     * aughtone/aughtone-phonenumber#23. A marker with no digits after it is not read as a marker, so its
     * letters go through keypad conversion and land in the number.
     *
     * **Workaround:** `findMarker` requires a digit after the marker, so this input is never delegated and
     * is refused by the ordinary path instead. Delete that condition when this test fails.
     */
    @Test
    fun aLetterMarkerWithNoDigitsIsStillFoldedIntoTheNumber() {
        val parsed = PhoneNumberUtil.parse("+1 212 555 0123 ext", "US")
        assertEquals(
            "+12125550123398",
            parsed.formatToE164(),
            "FIXED UPSTREAM: 'ext' with no digits no longer becomes keypad 398. Remove the digit-after " +
                "condition in findMarker and let this input be delegated.",
        )
        assertEquals(null, parsed.extension)
    }

    /**
     * aughtone/aughtone-phonenumber#24. A trailing `#` splits the number in the wrong place: the
     * subscriber digits become the extension.
     *
     * **Workaround:** the same digit-after condition in `findMarker`.
     */
    @Test
    fun aTrailingHashStillSplitsTheNumberInTheWrongPlace() {
        val parsed = PhoneNumberUtil.parse("+1 212 555 0123#", "US")
        assertEquals(
            "+1212555",
            parsed.formatToE164(),
            "FIXED UPSTREAM: a trailing '#' no longer eats the subscriber digits. Remove the digit-after " +
                "condition in findMarker.",
        )
        assertEquals("0123", parsed.extension)
    }

    /**
     * aughtone/aughtone-phonenumber#22. The trailing-group guard refuses an ordinary number whose
     * leading part is also valid.
     *
     * **No workaround needed here** - marker gating already keeps input with no extension marker away
     * from the dependency's parser, and this module's own ambiguity rule is narrower on purpose. This is
     * pinned only so the day it changes is visible: their fix is tracked as aughtone/aughtone-phonenumber#22.
     */
    @Test
    fun anOrdinaryNumberWhoseLeadingPartIsValidIsStillRefused() {
        val outcome = runCatching { PhoneNumberUtil.parse("+49 89 636 48018", "DE").formatToE164() }
        assertEquals(
            "AMBIGUOUS_TRAILING_GROUP",
            outcome.exceptionOrNull()
                ?.let { (it as? PhoneNumberUtil.NumberParseException)?.errorType?.name }
                ?: "parsed as ${outcome.getOrNull()}",
            "FIXED UPSTREAM: an ordinary grouped number now parses. Nothing to remove here, but the " +
                "marker gate in normalizePhoneWithExtension could be relaxed if there were a reason to.",
        )
    }
}
