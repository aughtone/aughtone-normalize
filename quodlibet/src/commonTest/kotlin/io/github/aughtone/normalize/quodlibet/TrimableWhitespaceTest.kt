package io.github.aughtone.normalize.quodlibet

import io.github.aughtone.normalize.email.EmailPolicy
import io.github.aughtone.normalize.email.normalizeEmail
import io.github.aughtone.normalize.username.UsernamePolicy
import io.github.aughtone.normalize.username.normalizeUsername
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FROZEN. DO NOT UPDATE THE EXPECTED VALUES IN THIS FILE.
 *
 * One value, pasted any of the ways people paste it, gives **one** token.
 *
 * Before this, only ASCII whitespace was trimmed, on input that can hold any character - so a value
 * copied out of a formatted page kept its no-break space, and a value read from a file with a byte-order
 * mark kept the mark, both inside the canonical and therefore inside the token. Two identical addresses
 * failed to match and nothing reported it. See aughtone/aughtone-normalize#40.
 *
 * The list is frozen deliberately: `Char.isWhitespace()` reads a Unicode property, and these policies
 * promise operations that cannot drift when Unicode ships a release. This test is what makes replacing
 * the list with a property lookup fail.
 */
class TrimableWhitespaceTest {

    /** Every character trimmed, with the name it is known by. */
    private val trimmed: List<Pair<Char, String>> = listOf(
        '\t' to "tab",
        '\n' to "line feed",
        Char(0x000B) to "line tabulation",
        Char(0x000C) to "form feed",
        '\r' to "carriage return",
        ' ' to "space",
        Char(0x0085) to "next line",
        Char(0x00A0) to "no-break space",
        Char(0x1680) to "ogham space mark",
        Char(0x2000) to "en quad",
        Char(0x2004) to "three-per-em space",
        Char(0x200A) to "hair space",
        Char(0x2028) to "line separator",
        Char(0x2029) to "paragraph separator",
        Char(0x202F) to "narrow no-break space",
        Char(0x205F) to "medium mathematical space",
        Char(0x3000) to "ideographic space",
        Char(0x200B) to "zero-width space",
        Char(0x2060) to "word joiner",
        Char(0xFEFF) to "byte-order mark",
    )

    /** Characters that look adjacent to the set and are NOT trimmed, because they are part of a value. */
    private val kept: List<Pair<Char, String>> = listOf(
        Char(0x200C) to "zero-width non-joiner",
        Char(0x200D) to "zero-width joiner",
        Char(0x00AD) to "soft hyphen",
        Char(0x180E) to "mongolian vowel separator",
    )

    @Test
    fun oneAddressPastedAnyWayGivesOneToken() {
        val expected = canonicalEmail("user@example.com")
        for ((character, name) in trimmed) {
            assertEquals(expected, canonicalEmail("$character user@example.com".replace(" ", "")), "leading $name")
            assertEquals(expected, canonicalEmail("user@example.com$character"), "trailing $name")
            assertEquals(expected, canonicalEmail("$character user@example.com$character".replace(" ", "")), "both, $name")
        }
    }

    @Test
    fun oneUsernamePastedAnyWayGivesOneToken() {
        val expected = canonicalUsername("someone")
        for ((character, name) in trimmed) {
            assertEquals(expected, canonicalUsername("${character}someone"), "leading $name")
            assertEquals(expected, canonicalUsername("someone$character"), "trailing $name")
        }
    }

    @Test
    fun theListIsExactlyThis() {
        // Pins the set itself, so replacing it with `Char.isWhitespace()` fails here rather than quietly
        // making these policies depend on a Unicode release.
        for ((character, name) in trimmed) {
            assertTrue(character.isTrimableWhitespace(), "$name (U+${character.code.toString(16).uppercase()}) must be trimmed")
        }
        for ((character, name) in kept) {
            assertTrue(!character.isTrimableWhitespace(), "$name (U+${character.code.toString(16).uppercase()}) must NOT be trimmed")
        }
    }

    @Test
    fun theInteriorOfAValueIsUntouched() {
        // A quoted local part may legitimately hold a space, and a no-break space inside a value is a
        // character the value contains rather than something around it.
        assertEquals("\"a b\"@example.com", canonicalEmail("\"a b\"@example.com"))
        val interior = "\"a" + Char(0x00A0) + "b\"@example.com"
        assertEquals(interior, canonicalEmail(interior))
    }

    private fun canonicalEmail(value: String): String {
        val outcome = normalizeEmail(value, EmailPolicy.Address)
        assertTrue(outcome is Outcome.Success, "<$value> must normalize, got $outcome")
        return outcome.data.canonical
    }

    private fun canonicalUsername(value: String): String {
        val outcome = normalizeUsername(value, UsernamePolicy.Basic)
        assertTrue(outcome is Outcome.Success, "<$value> must normalize, got $outcome")
        return outcome.data.canonical
    }
}
