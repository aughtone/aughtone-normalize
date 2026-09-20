package io.github.aughtone.normalize.phone

import io.github.aughtone.phonenumber.PhoneNumberUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The extension markers the dependency recognises, pinned.
 *
 * `normalizePhoneWithExtension` delegates to the phonenumber library only when it sees a marker it knows,
 * so two vocabularies have to agree: ours decides whether to delegate, theirs decides where the number
 * ends. A marker they gain and we do not means input we refuse and they could have split. A marker they
 * lose means input we delegate and they fold into the number - which is the failure this whole area
 * exists to prevent.
 *
 * **This test exists to fail on a dependency upgrade**, not to describe our own code. It asks the library
 * directly what it does with each spelling, so a bump that moves the boundary between number and extension
 * shows up here rather than in a consumer's tokens. If it fails, read the dependency's release notes: the
 * marker set is pinned per release there and a change to it is a called-out byte change.
 *
 * Pinned against `io.github.aughtone:phonenumber:0.0.3`.
 */
class PhoneExtensionMarkerTest {

    /** Each spelling, and the extension the dependency must read from `+1 212 555 0123<marker>4`. */
    private val markers: List<String> = listOf("#", ",", ";", "x", "X", " ext", " ext.", " extn", " xtn", " extension")

    @Test
    fun theDependencyStillReadsEveryMarkerWeDelegateFor() {
        for (marker in markers) {
            val input = "+1 212 555 0123$marker" + "4"
            val parsed = PhoneNumberUtil.parse(input, "US")
            assertEquals(
                "+12125550123",
                parsed.formatToE164(),
                "<$input>: the dependency no longer splits this marker off the number. Read its release " +
                    "notes before changing anything here - the split moving is a byte change.",
            )
            assertEquals("4", parsed.extension, "<$input>: extension")
        }
    }

    @Test
    fun weDelegateForEveryMarkerTheDependencyReads() {
        for (marker in markers) {
            val input = "+1 212 555 0123$marker" + "4"
            assertTrue(
                findMarker(input) != null,
                "<$input>: the dependency reads this marker and we do not delegate for it, so we refuse " +
                    "input it could have split. Add the spelling to findMarker.",
            )
        }
    }

    @Test
    fun weDoNotDelegateForOrdinaryNumbers() {
        // The gate that keeps ordinary input away from the dependency's trailing-group guard, which
        // refuses some valid numbers whose leading part is also valid.
        for (input in listOf("+1 212 555 0123", "+1 (212) 555-0123", "+49 89 636 48018", "+43 1 58058-0")) {
            assertEquals(null, findMarker(input), "<$input> must not be delegated")
        }
    }
}
