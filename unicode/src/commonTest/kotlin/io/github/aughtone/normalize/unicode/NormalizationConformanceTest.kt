package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.unicode.generated.NormalizationConformanceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Unicode Consortium's own conformance suite, run on every target.
 *
 * `NormalizationTest.txt` gives five spellings of one text per case, and fixes what each form must
 * produce for all of them. Passing it is the difference between "our normalizer agrees with itself"
 * and "our normalizer is correct", and running it on every target is the difference between a suite
 * that promises identical bytes everywhere and one that has only checked on JVM.
 *
 * The data is generated into this source set from the pinned release, rather than read from a file,
 * because JS and wasm tests cannot read files - and those are exactly the targets where a divergence
 * would otherwise go unnoticed.
 */
class NormalizationConformanceTest {

    private class Case(val source: String, val nfc: String, val nfd: String, val nfkc: String, val nfkd: String)

    private val cases: List<Case> by lazy {
        NormalizationConformanceData.cases.split(';').map { record ->
            val columns = record.split('|').map { column ->
                column.split(' ').map { it.toInt(16) }.toStringFromCodePoints()
            }
            Case(columns[0], columns[1], columns[2], columns[3], columns[4])
        }
    }

    private fun nfc(value: String) = TextPolicy.NfcU17.apply(value)
    private fun nfd(value: String) = TextPolicy.NfdU17.apply(value)
    private fun nfkc(value: String) = TextPolicy.NfkcU17.apply(value)
    private fun nfkd(value: String) = TextPolicy.NfkdU17.apply(value)

    @Test
    fun theTablesAndTheConformanceDataComeFromTheSameRelease() {
        // A mismatch here means the corpus was regenerated without the tables, or the other way round,
        // and every result below would be measured against the wrong answers.
        assertEquals(UnicodeTables.VERSION, NormalizationConformanceData.UNICODE_VERSION)
    }

    @Test
    fun theCorpusIsWhatTheReleasePublished() {
        // A guard against silently testing nothing: this file has ~19,000 cases, and a decoding bug
        // that produced an empty list would otherwise make every test below pass.
        assertTrue(cases.size > 18_000, "expected the full conformance corpus, found ${cases.size} cases")
    }

    @Test
    fun canonicalCompositionMatchesTheStandard() {
        for ((index, case) in cases.withIndex()) {
            for (spelling in listOf(case.source, case.nfc, case.nfd)) {
                assertEquals(case.nfc, nfc(spelling), "NFC, case $index")
            }
            for (spelling in listOf(case.nfkc, case.nfkd)) {
                assertEquals(case.nfkc, nfc(spelling), "NFC of a compatibility spelling, case $index")
            }
        }
    }

    @Test
    fun canonicalDecompositionMatchesTheStandard() {
        for ((index, case) in cases.withIndex()) {
            for (spelling in listOf(case.source, case.nfc, case.nfd)) {
                assertEquals(case.nfd, nfd(spelling), "NFD, case $index")
            }
            for (spelling in listOf(case.nfkc, case.nfkd)) {
                assertEquals(case.nfkd, nfd(spelling), "NFD of a compatibility spelling, case $index")
            }
        }
    }

    @Test
    fun compatibilityCompositionMatchesTheStandard() {
        for ((index, case) in cases.withIndex()) {
            for (spelling in listOf(case.source, case.nfc, case.nfd, case.nfkc, case.nfkd)) {
                assertEquals(case.nfkc, nfkc(spelling), "NFKC, case $index")
            }
        }
    }

    @Test
    fun compatibilityDecompositionMatchesTheStandard() {
        for ((index, case) in cases.withIndex()) {
            for (spelling in listOf(case.source, case.nfc, case.nfd, case.nfkc, case.nfkd)) {
                assertEquals(case.nfkd, nfkd(spelling), "NFKD, case $index")
            }
        }
    }
}
