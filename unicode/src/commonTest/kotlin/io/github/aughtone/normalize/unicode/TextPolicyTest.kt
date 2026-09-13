package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.types.outcome.Outcome
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How a text policy is built, identified, warned about and resolved.
 *
 * The bytes each configuration produces are pinned in `TextRulesByteStabilityTest`; this file pins the
 * rules around them - which configurations exist, which id each has, and which ids are refused.
 */
class TextPolicyTest {

    private val u17 = UnicodeRelease.U17
    private val reported = mutableListOf<TextPolicyWarning>()
    private lateinit var previousHandler: (TextPolicy, TextPolicyWarning) -> Unit

    @BeforeTest
    fun captureWarnings() {
        previousHandler = TextPolicy.warningHandler
        TextPolicy.warningHandler = { _, warning -> reported += warning }
    }

    @AfterTest
    fun restoreWarnings() {
        TextPolicy.warningHandler = previousHandler
    }

    @Test
    fun theSameRulesInAnyArrangementAreOnePolicy() {
        val a = TextPolicy(u17) { unicode { trim(); casefold() }; ascii { collapseSpace() }; nonEmpty() }
        val b = TextPolicy(u17) { nonEmpty(); ascii { collapseSpace() }; unicode { casefold(); trim() } }
        assertEquals("text.u17+trim+collapse-space.ascii+casefold+non-empty", a.id)
        assertEquals(a.id, b.id)
        assertEquals(a, b)
    }

    @Test
    fun anAsciiPolicyNamesNoRelease() {
        val policy = TextPolicy { ascii { stripControl(); trim(); lowercase() } }
        assertEquals("text+strip-control+trim+lower", policy.id)
        assertEquals(null, policy.release)
        assertEquals(null, policy.links.first().dataVersion)
    }

    @Test
    fun aRepeatedRuleIsRefusedWhetherInOneBlockOrBoth() {
        assertFailsWith<TextPolicyError.RepeatedRule> { TextPolicy { ascii { trim(); trim() } } }
        assertFailsWith<TextPolicyError.RepeatedRule> { TextPolicy(u17) { unicode { trim() }; ascii { trim() } } }
        assertFailsWith<TextPolicyError.RepeatedRule> { TextPolicy { nonEmpty(); nonEmpty() } }
    }

    @Test
    fun alternativesAreRefusedTogether() {
        assertFailsWith<TextPolicyError.ContradictoryRules> { TextPolicy { ascii { lowercase(); uppercase() } } }
        assertFailsWith<TextPolicyError.ContradictoryRules> { TextPolicy(u17) { unicode { lowercase(); casefold() } } }
        assertFailsWith<TextPolicyError.ContradictoryRules> { TextPolicy(u17) { ascii { lowercase() }; unicode { casefold() } } }
        assertFailsWith<TextPolicyError.ContradictoryRules> { TextPolicy { ascii { collapseSpace(); removeSpace() } } }
        assertFailsWith<TextPolicyError.ContradictoryRules> { TextPolicy(u17) { unicode { nfc(); nfkc() } } }
    }

    @Test
    fun aReleaseNothingUsesIsRefused() {
        assertFailsWith<TextPolicyError.UnusedRelease> { TextPolicy(u17) { ascii { trim() } } }
        assertFailsWith<TextPolicyError.UnusedRelease> { TextPolicy(u17) { ascii { trim() }; nonEmpty() } }
    }

    @Test
    fun rulesWrittenOutOfOrderAreWarnedAboutAndStillBuild() {
        val policy = TextPolicy { ascii { lowercase(); trim() } }
        val expected = TextPolicyWarning.OrderDiffersFromApplication(written = listOf("lower", "trim"), applied = listOf("trim", "lower"))
        assertEquals(listOf<TextPolicyWarning>(expected), policy.warnings)
        assertEquals(listOf<TextPolicyWarning>(expected), reported)
        assertEquals(TextPolicy { ascii { trim(); lowercase() } }.id, policy.id)
    }

    @Test
    fun rulesWrittenInOrderAreNotWarnedAbout() {
        val policy = TextPolicy(u17) { unicode { trim(); casefold(); nfc() }; nonEmpty() }
        assertTrue(policy.warnings.isEmpty())
        assertTrue(reported.isEmpty())
    }

    @Test
    fun aWarningNeverChangesTheOutput() {
        val warned = TextPolicy { ascii { lowercase(); trim() } }
        val quiet = TextPolicy { ascii { trim(); lowercase() } }
        val input = "  MiXeD  "
        assertEquals(
            (normalizeText(input, quiet) as Outcome.Success).data,
            (normalizeText(input, warned) as Outcome.Success).data,
        )
    }

    @Test
    fun theWarningHandlerCanBeSilenced() {
        TextPolicy.warningHandler = { _, _ -> }
        val policy = TextPolicy { ascii { lowercase(); trim() } }
        assertEquals(1, policy.warnings.size, "the policy still records the warning")
        assertTrue(reported.isEmpty())
    }

    @Test
    fun everyConfigurationResolvesFromItsId() {
        val policies = listOf(
            TextPolicy { ascii { trim() } },
            TextPolicy { ascii { stripControl(); trim(); collapseSpace(); uppercase() }; nonEmpty() },
            TextPolicy(u17) { unicode { stripControl(); trim(); removeSpace(); casefold(); nfkc() } },
            TextPolicy(u17) { unicode { trim(); nfd() }; ascii { lowercase() }; nonEmpty() },
        )
        for (policy in policies) {
            val outcome = UnicodePolicies.resolve(policy.id, policy.version)
            assertTrue(outcome is Outcome.Success, "<${policy.id}> must resolve")
            assertEquals(policy, outcome.data)
        }
    }

    @Test
    fun anythingButTheCanonicalSpellingIsRefused() {
        val notCanonical = listOf(
            "text+lower+trim",               // out of application order
            "text+trim.ascii",               // a marker with no Unicode release to be an exception to
            "text.u17+trim.ascii",           // a release nothing uses
            "text+casefold",                 // a Unicode rule with no release
            "text+trim+trim",                // repeated
            "text.u17+lower+casefold",       // alternatives
            "text.u17+nfc.ascii",            // a Unicode-only rule cannot run over ASCII
        )
        for (id in notCanonical) {
            val outcome = UnicodePolicies.resolve(id, 1)
            assertTrue(
                outcome is Outcome.Failure && outcome.exception is PolicyIdentityError.NotCanonical,
                "<$id> must be refused as not canonical, got $outcome",
            )
        }
        val unknown = UnicodePolicies.resolve("text+frobnicate", 1)
        assertTrue(unknown is Outcome.Failure && unknown.exception is PolicyIdentityError.UnknownLink)
        val wrongVersion = UnicodePolicies.resolve("text+trim", 2)
        assertTrue(wrongVersion is Outcome.Failure && wrongVersion.exception is PolicyIdentityError.VersionMismatch)
    }

    @Test
    fun theDataVersionIsStatedOnce() {
        val policy = TextPolicy(u17) { unicode { trim(); casefold(); nfc() } }
        assertEquals(1, policy.id.split('+').count { it.contains("u17") })
    }

    @Test
    fun aTextIdRoundTripsThroughItsPortableSpelling() {
        val policy = TextPolicy(u17) { unicode { trim() }; ascii { lowercase() }; nonEmpty() }
        val portable = PolicyId.toPortable(policy.id).dataOrThrow()
        assertEquals("text.u17_trim_lower.ascii_non-empty", portable)
        val back = PolicyId.fromPortable(portable).dataOrThrow()
        assertEquals(policy, (UnicodePolicies.resolve(back, 1) as Outcome.Success).data)
    }
}
