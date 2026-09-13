package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.normalize.ubilibet.generated.IdnaConformanceData
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `toUnicodeDomain` against the ToUnicode columns of the Unicode Consortium's UTS-46 conformance suite.
 *
 * The file records whether a case has any error, not which label carries it, so this checks what the
 * file fixes: the converted text, and that a domain is valid exactly when its status is empty once the
 * codes of the checks a policy relaxes are removed. Where each error lands is covered by
 * [DomainToUnicodeTest].
 */
class IdnaToUnicodeConformanceTest {

    private class Case(val source: String, val expected: String, val statuses: Set<String>)

    /** Codes belonging to checks the lenient policy turns off: DNS length, hyphens, and STD3 ASCII. */
    private val relaxedByLenient = setOf("A4_1", "A4_2", "X4_2", "V2", "V3", "U1")

    private val cases: List<Case> by lazy {
        IdnaConformanceData.rows.split(';').map { row ->
            val columns = row.split('|')
            Case(
                source = columns[0].toText(),
                expected = columns[3].toText(),
                statuses = columns[4].split(' ').filter { it.isNotEmpty() }.toSet(),
            )
        }
    }

    private fun String.toText(): String =
        if (isEmpty()) "" else split(' ').map { it.toInt(16) }.toCodePointString()

    @Test
    fun theStrictPolicyMatchesTheStandardForEveryCase() {
        assertConforms(DomainPolicy.AsciiU17, ignored = emptySet())
    }

    @Test
    fun theLenientPolicyFlagsOnlyTheChecksItKeeps() {
        assertConforms(DomainPolicy.AsciiU17Lenient, ignored = relaxedByLenient)
    }

    private fun assertConforms(policy: DomainPolicy, ignored: Set<String>) {
        var checked = 0
        for (case in cases) {
            // Ill-formed input is refused before processing, as normalizeDomain refuses it.
            if (case.source.hasUnpairedSurrogate()) continue
            checked++

            val outcome = toUnicodeDomain(case.source, policy)
            assertTrue(outcome is Outcome.Success, "<${case.source}> must convert")
            val domain = outcome.data
            val remaining = case.statuses - ignored

            assertEquals(
                remaining.isEmpty(),
                domain.isValid,
                "<${case.source}> under $policy: statuses ${remaining.joinToString()}, errors " +
                    domain.labels.mapNotNull { it.error?.let { error -> error::class.simpleName } },
            )
            // The converted text is the file's only where the case validates. For a case with errors the
            // file's value is one implementation's best effort, which the standard permits to differ.
            if (case.statuses.isEmpty()) {
                assertEquals(case.expected, domain.unicode, "<${case.source}>")
            }
        }
        assertTrue(checked > 5_000, "expected to check the corpus, checked $checked cases")
    }
}
