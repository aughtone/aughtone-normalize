package io.github.aughtone.normalize.confusables

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole of `BidiCharacterTest.txt`, read from the pinned data rather than compiled in.
 *
 * The cross-platform suite runs a deterministic sample, because 94,000 cases is several megabytes once
 * compiled into a test binary and that is more than belongs in every target's bundle. This test is the
 * other half of that trade: it runs every case, on the one target that can read a file, so nothing is
 * taken on faith merely because the sample happened to miss it.
 */
class FullBidiConformanceTest {

    private val corpus = File("../ucd/17.0.0/BidiCharacterTest.txt")

    @Test
    fun everyCaseInTheStandardsCorpusPasses() {
        assertTrue(corpus.isFile, "missing ${corpus.path}: the pinned conformance data must be checked in")

        var checked = 0
        var levelChecks = 0
        corpus.forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            val fields = line.split(';')
            check(fields.size == 5) { "malformed case: $raw" }

            val codePoints = fields[0].trim().split(' ').map { it.toInt(16) }.toIntArray()
            val paragraphLevel = fields[2].trim().toInt()
            val expectedLevels = fields[3].trim().split(' ').map { if (it == "x") null else it.toInt() }
            val expectedOrder = fields[4].trim().split(' ').filter { it.isNotEmpty() }.map { it.toInt() }

            val bidi = Bidi(codePoints, paragraphLevel)
            val resolved = bidi.resolveLevels()
            for ((index, expected) in expectedLevels.withIndex()) {
                if (expected == null) continue
                assertEquals(expected, resolved[index], "levels, character $index of: $line")
                levelChecks++
            }
            assertEquals(expectedOrder, Bidi(codePoints, paragraphLevel).visualOrder().toList(), "order of: $line")
            checked++
        }

        // The corpus has roughly 94,000 cases; a parsing change that silently skipped most of them would
        // otherwise leave this test green while proving nothing.
        assertTrue(checked > 90_000, "expected the whole corpus, checked $checked cases")
        assertTrue(levelChecks > 100_000, "expected many level assertions, made $levelChecks")
    }
}
