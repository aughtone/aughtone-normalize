package io.github.aughtone.normalize.confusables

import io.github.aughtone.normalize.confusables.generated.BidiConformanceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Unicode Consortium's conformance suite for the bidirectional algorithm, run on every target.
 *
 * `BidiCharacterTest.txt` fixes, for a piece of text and a paragraph direction, the resolved paragraph
 * level, every character's resolved level, and the resulting visual order. Passing it is what separates
 * an implementation of UAX #9 from something that behaves like it on the examples someone thought of.
 *
 * This runs a deterministic sample of the file - it is 94,000 cases and several megabytes once compiled,
 * which is more than belongs in every target's test binary. The JVM suite reads the whole file and runs
 * all of it; what runs here is what proves the other targets agree with the JVM.
 */
class BidiConformanceTest {

    private class Case(
        val codePoints: IntArray,
        val direction: Int,
        val paragraphLevel: Int,
        val levels: List<Int?>,
        val order: List<Int>,
    )

    private val cases: List<Case> by lazy { BidiConformanceData.rows.split(';').chunked(5).map { parse(it) } }

    private fun parse(fields: List<String>): Case = Case(
        codePoints = fields[0].trim().split(' ').map { it.toInt(16) }.toIntArray(),
        direction = fields[1].trim().toInt(),
        paragraphLevel = fields[2].trim().toInt(),
        levels = fields[3].trim().split(' ').map { if (it == "x") null else it.toInt() },
        order = fields[4].trim().split(' ').filter { it.isNotEmpty() }.map { it.toInt() },
    )

    @Test
    fun theSampleIsWhatTheReleasePublished() {
        // A guard against testing nothing: a decoding bug producing an empty list would make every
        // assertion below pass vacuously.
        assertTrue(cases.size > 5_000, "expected a substantial sample, found ${cases.size} cases")
        assertEquals("17.0.0", BidiConformanceData.UNICODE_VERSION)
    }

    @Test
    fun resolvedLevelsMatchTheStandard() {
        for ((number, case) in cases.withIndex()) {
            val bidi = Bidi(case.codePoints, case.paragraphLevel)
            val resolved = bidi.resolveLevels()
            for ((index, expected) in case.levels.withIndex()) {
                // A level of `x` marks a character rule X9 removes; the standard does not say what an
                // implementation should report for those, so they are not compared.
                if (expected == null) continue
                assertEquals(
                    expected,
                    resolved[index],
                    "case $number, character $index (direction ${case.direction})",
                )
            }
        }
    }

    @Test
    fun visualOrderMatchesTheStandard() {
        for ((number, case) in cases.withIndex()) {
            val bidi = Bidi(case.codePoints, case.paragraphLevel)
            assertEquals(
                case.order,
                bidi.visualOrder().toList(),
                "case $number (direction ${case.direction})",
            )
        }
    }
}
