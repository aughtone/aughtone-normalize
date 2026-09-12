package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.normalize.ubilibet.generated.IdnaConformanceData
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Unicode Consortium's own UTS-46 conformance suite, run on every target.
 *
 * `IdnaTestV2.txt` fixes, for every case, what ToASCII must produce and which checks must reject it.
 * Passing it is the difference between "our implementation agrees with itself" and "our implementation
 * is UTS-46".
 *
 * The file states which status codes correspond to which flag, so a policy that turns a check off
 * ignores that check's codes. That mapping is the basis of the two tests below: the strict policy
 * enables every check and must therefore reject every case carrying any status, while the lenient one
 * ignores the codes for the three checks it relaxes.
 */
class IdnaConformanceTest {

    private class Case(val source: String, val expected: String, val statuses: Set<String>)

    /** Codes belonging to checks the lenient policy turns off: DNS length, hyphens, and STD3 ASCII. */
    private val relaxedByLenient = setOf("A4_1", "A4_2", "X4_2", "V2", "V3", "U1")

    private val cases: List<Case> by lazy {
        IdnaConformanceData.rows.split(';').map { row ->
            val columns = row.split('|')
            Case(
                source = columns[0].toText(),
                expected = columns[1].toText(),
                statuses = columns[2].split(' ').filter { it.isNotEmpty() }.toSet(),
            )
        }
    }

    private fun String.toText(): String =
        if (isEmpty()) "" else split(' ').map { it.toInt(16) }.toCodePointString()

    @Test
    fun theCorpusIsWhatTheReleasePublished() {
        // A guard against testing nothing: a decoding bug that produced an empty list would otherwise
        // make every assertion below pass vacuously.
        assertTrue(cases.size > 5_000, "expected the full IDNA corpus, found ${cases.size} cases")
        assertEquals("17.0.0", IdnaConformanceData.UNICODE_VERSION)
    }

    @Test
    fun theStrictPolicyMatchesTheStandardForEveryCase() {
        var checked = 0
        for (case in cases) {
            // The corpus contains ill-formed input for the Punycode error cases; this suite refuses
            // those before processing, which is the same verdict by a different route.
            if (case.source.hasUnpairedSurrogate()) continue
            checked++

            val outcome = normalizeDomain(case.source, DomainPolicy.AsciiU17)
            if (case.statuses.isEmpty()) {
                assertTrue(outcome is Outcome.Success, "<${case.source}> must normalize, got ${describe(outcome)}")
                assertEquals(case.expected, outcome.data.canonical, "<${case.source}>")
            } else {
                assertTrue(
                    outcome is Outcome.Failure,
                    "<${case.source}> must be refused (${case.statuses.joinToString()}), got ${describe(outcome)}",
                )
            }
        }
        assertTrue(checked > 5_000, "expected to check the corpus, checked $checked cases")
    }

    @Test
    fun theLenientPolicyRefusesOnlyTheChecksItKeeps() {
        for (case in cases) {
            if (case.source.hasUnpairedSurrogate()) continue
            val remaining = case.statuses - relaxedByLenient
            val outcome = normalizeDomain(case.source, DomainPolicy.AsciiU17Lenient)

            if (remaining.isEmpty()) {
                assertTrue(
                    outcome is Outcome.Success,
                    "<${case.source}> carries only relaxed statuses (${case.statuses.joinToString()}) " +
                        "but got ${describe(outcome)}",
                )
                // The value is only compared where the strict policy agrees: for a case the file marks
                // as failing, its expected value is a best-effort partial conversion, not a result any
                // policy is required to reproduce.
                if (case.statuses.isEmpty()) {
                    assertEquals(case.expected, outcome.data.canonical, "<${case.source}>")
                }
            } else {
                assertTrue(
                    outcome is Outcome.Failure,
                    "<${case.source}> must be refused (${remaining.joinToString()}), got ${describe(outcome)}",
                )
            }
        }
    }

    /** Describe an outcome without casting it, so a failure message cannot itself throw. */
    private fun describe(outcome: Outcome<NormalizedDomain>): String = when (outcome) {
        is Outcome.Success -> "success <${outcome.data.canonical}>"
        is Outcome.Failure -> "failure ${outcome.exception::class.simpleName}"
    }

    private fun String.hasUnpairedSurrogate(): Boolean {
        var index = 0
        while (index < length) {
            val character = this[index]
            if (character.isHighSurrogate()) {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
                index += 2
            } else {
                if (character.isLowSurrogate()) return true
                index += 1
            }
        }
        return false
    }
}
