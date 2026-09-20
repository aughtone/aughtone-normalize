package io.github.aughtone.normalize.phone

import io.github.aughtone.phonenumber.PhoneNumberUtil
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Defects in the dependency, pinned so that the release which fixes them is visible here.
 *
 * **A failing test here is good news, and none of it is load-bearing any more.** `normalizePhoneWithExtension`
 * splits at the marker itself and no longer gives the phonenumber library raw input, so none of these
 * defects can reach this module - they are pinned because they are fixed in a release that is queued
 * rather than out, and the day it lands should be visible in this build rather than found later.
 *
 * When one fails: update its expectation to the fixed behaviour, or delete the test if the defect is gone
 * for good. Every other frozen test in this suite says "do not change this"; this one says the opposite.
 *
 * Pinned against `phonenumber:0.0.3`. Upstream tickets: aughtone/aughtone-phonenumber#23, #24 and #22.
 */
class PhoneDependencyDefectTest {

    /**
     * aughtone/aughtone-phonenumber#23. A marker with no digits after it is not read as a marker, so its
     * letters go through keypad conversion and land in the number.
     *
     * Not reachable from here: `findMarker` treats a marker with no digits after it as no marker, and the
     * input is read as an ordinary number and refused. Pinned to show when their fix ships.
     */
    @Test
    fun aLetterMarkerWithNoDigitsIsStillFoldedIntoTheNumber() {
        val parsed = PhoneNumberUtil.parse("+1 212 555 0123 ext", "US")
        assertEquals(
            "+12125550123398",
            parsed.formatToE164(),
            "FIXED UPSTREAM: 'ext' with no digits no longer becomes keypad 398. Nothing here depends on " +
                "it; update this expectation or drop the case.",
        )
        assertEquals(null, parsed.extension)
    }

    /**
     * aughtone/aughtone-phonenumber#24. A trailing `#` splits the number in the wrong place: the
     * subscriber digits become the extension.
     *
     * Not reachable from here, for the same reason as above.
     */
    @Test
    fun aTrailingHashStillSplitsTheNumberInTheWrongPlace() {
        val parsed = PhoneNumberUtil.parse("+1 212 555 0123#", "US")
        assertEquals(
            "+1212555",
            parsed.formatToE164(),
            "FIXED UPSTREAM: a trailing '#' no longer eats the subscriber digits. Nothing here depends on " +
                "it; update this expectation or drop the case.",
        )
        assertEquals("0123", parsed.extension)
    }

    /**
     * aughtone/aughtone-phonenumber#22. The trailing-group guard refuses an ordinary number whose
     * leading part is also valid.
     *
     * Never reachable: this module's own ambiguity rule decides these, and it is narrower on purpose.
     * Pinned only so the day it changes is visible; their fix is aughtone/aughtone-phonenumber#22.
     */
    @Test
    fun anOrdinaryNumberWhoseLeadingPartIsValidIsStillRefused() {
        val outcome = runCatching { PhoneNumberUtil.parse("+49 89 636 48018", "DE").formatToE164() }
        assertEquals(
            "AMBIGUOUS_TRAILING_GROUP",
            outcome.exceptionOrNull()
                ?.let { (it as? PhoneNumberUtil.NumberParseException)?.errorType?.name }
                ?: "parsed as ${outcome.getOrNull()}",
            "FIXED UPSTREAM: an ordinary grouped number now parses. Nothing here depends on it.",
        )
    }
}
