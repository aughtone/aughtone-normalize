package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainToUnicodeTest {

    private fun convert(value: String, policy: DomainPolicy = DomainPolicy.AsciiU17): UnicodeDomain {
        val outcome = toUnicodeDomain(value, policy)
        assertTrue(outcome is Outcome.Success, "<$value> must convert")
        return outcome.data
    }

    @Test
    fun anALabelConvertsToItsULabel() {
        val domain = convert("xn--caf-dma.example")
        assertTrue(domain.isValid)
        assertEquals("caf\u00e9.example", domain.unicode)
        assertEquals(listOf("xn--caf-dma", "example"), domain.labels.map { it.ascii })
    }

    @Test
    fun unmappedInputConvertsToTheSameULabel() {
        val domain = convert("CAF\u00c9.Example")
        assertTrue(domain.isValid)
        assertEquals("caf\u00e9.example", domain.unicode)
        assertEquals("xn--caf-dma", domain.labels.first().ascii)
    }

    @Test
    fun aFailingLabelIsReportedAloneAndTheOthersStillConvert() {
        // A Punycode label that does not decode keeps the form it was given in.
        val domain = convert("xn--caf-dma.xn--0.example")
        assertFalse(domain.isValid)
        val (cafe, broken, example) = domain.labels
        assertNull(cafe.error)
        assertEquals("caf\u00e9", cafe.unicode)
        assertIs<DomainNormalizationError.PunycodeDecodeFailed>(broken.error)
        assertEquals("xn--0", broken.unicode)
        assertEquals("xn--0", broken.ascii)
        assertNull(example.error)
    }

    @Test
    fun aDecodedLabelThatBreaksAJoinerRuleFallsBackToItsALabel() {
        // A zero-width joiner after a Latin letter: decodes cleanly, and must not be shown.
        val aLabel = normalizeDomain("a\u200db.example", DomainPolicy.AsciiU17)
        assertTrue(aLabel is Outcome.Failure)
        val encoded = "xn--ab-m1t.example"
        val domain = convert(encoded)
        val first = domain.labels.first()
        assertIs<DomainNormalizationError.JoinerRule>(first.error)
        assertEquals("a\u200db", first.unicode)
        assertEquals("xn--ab-m1t", first.ascii)
    }

    @Test
    fun aDisallowedCodePointFailsOnlyItsLabel() {
        val domain = convert("a\u2488com.example")
        assertIs<DomainNormalizationError.DisallowedCodePoint>(domain.labels.first().error)
        assertNull(domain.labels.last().error)
    }

    @Test
    fun theBidiRuleIsReportedOnTheLabelThatBreaksIt() {
        // The Hebrew label makes this a bidi domain, and the label starting with a digit then breaks rule 1.
        val domain = convert("0\u00e0.\u05d0")
        assertIs<DomainNormalizationError.BidiRule>(domain.labels.first().error)
        assertNull(domain.labels.last().error)
    }

    @Test
    fun anEmptyLabelFailsOnlyWhenDnsLengthIsVerified() {
        val strict = convert("a..example")
        assertIs<DomainNormalizationError.EmptyLabel>(strict.labels[1].error)

        assertTrue(convert("a..example", DomainPolicy.AsciiU17Lenient).isValid)
    }

    @Test
    fun theRootLabelMayBeEmpty() {
        val domain = convert("example.com.")
        assertTrue(domain.isValid)
        assertEquals(3, domain.labels.size)
    }

    @Test
    fun theLenientPolicyShowsWhatOnlyTheStrictOneRefuses() {
        assertIs<DomainNormalizationError.DisallowedCodePoint>(convert("a_b.example").labels.first().error)
        assertTrue(convert("a_b.example", DomainPolicy.AsciiU17Lenient).isValid)
    }

    @Test
    fun anUnpairedSurrogateFailsTheWholeCall() {
        // The surrogate is constructed rather than written - see [withLoneSurrogate].
        val outcome = toUnicodeDomain(withLoneSurrogate("a", ".example"), DomainPolicy.AsciiU17)
        assertTrue(outcome is Outcome.Failure)
        assertIs<DomainNormalizationError.UnpairedSurrogate>(outcome.exception)
    }

    @Test
    fun noErrorMessageCarriesTheInput() {
        val domain = convert("secretname\u200d.example")
        val message = domain.labels.first().error?.message.orEmpty()
        assertTrue(message.isNotEmpty())
        assertFalse(message.contains("secretname"))
    }

/**
 * A string carrying an unpaired high surrogate between [before] and [after], built so that the character
 * never appears in a literal the compiler emits.
 *
 * `"a" + 0xD800.toChar() + "b"` is a **constant expression**: the compiler folds it and writes the
 * surrogate into the generated source. A lone surrogate is not a Unicode scalar value, so it has no UTF-8
 * representation at all - a generator can only carry one by escaping it, and one written raw comes back as
 * a replacement character. The input then silently stops being the one under test, and an assertion loose
 * enough not to notice passes while testing nothing.
 *
 * Routing the code point through a call the compiler cannot evaluate keeps it out of emitted source, and
 * the checks below fail loudly if a build mangles it anyway. **Do not simplify this back into a literal:**
 * the previous fix here was exactly that reasoning, and it put the bug back.
 * See aughtone/aughtone-normalize#34.
 */
private fun withLoneSurrogate(before: String, after: String): String {
    val code = listOf(0xD800).first()
    val built = before + Char(code) + after
    assertEquals(
        before.length + 1 + after.length,
        built.length,
        "this build mangled the lone surrogate - the case below would test the wrong input (see #34)",
    )
    assertEquals(
        code,
        built[before.length].code,
        "this build mangled the lone surrogate - the case below would test the wrong input (see #34)",
    )
    return built
}
}
