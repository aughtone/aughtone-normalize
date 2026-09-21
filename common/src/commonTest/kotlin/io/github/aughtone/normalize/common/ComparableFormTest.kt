package io.github.aughtone.normalize.common

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Comparable forms: what a policy declares, what a caller may opt into, how the choice is spelled in an
 * id, and what the comparability check reports.
 *
 * The policies here are stand-ins, so the rules are pinned independently of any real normalizer.
 */
class ComparableFormTest {

    private val value = ComparableForm("example.value")
    private val loose = ComparableForm("example.loose")
    private val wide = ComparableForm("example.wide")

    private class Stub(
        override val id: String,
        override val forms: Set<ComparableForm> = emptySet(),
        override val offeredForms: Set<ComparableForm> = emptySet(),
    ) : Policy {
        override val version: Int = 1
    }

    private val strict = Stub("example.strict", forms = setOf(value), offeredForms = setOf(loose, wide))
    private val lenient = Stub("example.strict:lenient", forms = setOf(value))
    private val other = Stub("example.other", offeredForms = setOf(loose))
    private val plain = Stub("example.plain")

    private val module = object : PublishedPolicies() {
        override val policies: List<Policy> = listOf(strict, lenient, other, plain)
        override val links: List<PolicyLink> = listOf(
            PolicyLink("example.strict", LinkKind.Base),
            PolicyLink("example.other", LinkKind.Base),
            PolicyLink("example.plain", LinkKind.Base),
            PolicyLink.Lenient,
        )
    }

    @Test
    fun aPolicyDeclaresNoFormsByDefault() {
        val bare = object : Policy {
            override val id = "example.bare"
            override val version = 1
        }
        assertTrue(bare.forms.isEmpty())
        assertTrue(bare.offeredForms.isEmpty())
    }

    @Test
    fun aFormNameFollowsTheLinkGrammar() {
        assertFailsWith<IllegalArgumentException> { ComparableForm("Example.Value") }
        assertFailsWith<IllegalArgumentException> { ComparableForm("example:value") }
        assertEquals("form.example.value", value.link.name)
        assertEquals(LinkKind.Form, value.link.kind)
    }

    @Test
    fun formLinksComeLastInNameOrder() {
        val base = PolicyLink("example.strict", LinkKind.Base)
        val ok = PolicyId.of(listOf(base, PolicyLink.Lenient, loose.link, wide.link))
        assertTrue(ok is Outcome.Success)
        assertEquals("example.strict:lenient:form.example.loose:form.example.wide", ok.data.rendered)

        val reversed = PolicyId.of(listOf(base, wide.link, loose.link))
        assertTrue(reversed is Outcome.Failure && reversed.exception is PolicyIdentityError.OutOfOrder)
        val beforeQualifier = PolicyId.of(listOf(base, loose.link, PolicyLink.Lenient))
        assertTrue(beforeQualifier is Outcome.Failure && beforeQualifier.exception is PolicyIdentityError.OutOfOrder)
        val repeated = PolicyId.of(listOf(base, loose.link, loose.link))
        assertTrue(repeated is Outcome.Failure && repeated.exception is PolicyIdentityError.DuplicateLink)
        assertFailsWith<IllegalArgumentException> { PolicyLink("form.example.value", LinkKind.Parameter) }
        assertFailsWith<IllegalArgumentException> { PolicyLink("example.value", LinkKind.Form) }
    }

    @Test
    fun optingInKeepsThePolicyAndRecordsTheChoice() {
        val opted = strict.withForms(setOf(wide, loose))
        assertEquals("example.strict:form.example.loose:form.example.wide", opted.id)
        assertEquals(setOf(value, loose, wide), opted.forms)
        assertEquals(strict.version, opted.version)
        assertEquals(strict, (opted as OptedInPolicy).policy)
        assertFailsWith<IllegalArgumentException> { plain.withForms(setOf(loose)) }
        assertEquals(strict, strict.withForms(emptySet()))
    }

    @Test
    fun anOptedInIdResolves() {
        val outcome = module.resolve("example.strict:form.example.loose:form.example.wide", 1)
        assertTrue(outcome is Outcome.Success, "got $outcome")
        assertEquals(setOf(value, loose, wide), outcome.data.forms)

        val throughComposite = (module + object : PublishedPolicies() {
            override val policies: List<Policy> = emptyList()
            override val links: List<PolicyLink> = emptyList()
        }).resolve("example.other:form.example.loose", 1)
        assertTrue(throughComposite is Outcome.Success, "got $throughComposite")
        assertEquals("example.other:form.example.loose", throughComposite.data.id)
    }

    @Test
    fun anythingButTheOfferedCanonicalSpellingIsRefused() {
        val notOffered = module.resolve("example.plain:form.example.loose", 1)
        assertTrue(notOffered is Outcome.Failure && notOffered.exception is PolicyIdentityError.FormNotOffered, "got $notOffered")
        val reversed = module.resolve("example.strict:form.example.wide:form.example.loose", 1)
        assertTrue(reversed is Outcome.Failure && reversed.exception is PolicyIdentityError.NotCanonical, "got $reversed")
        val repeated = module.resolve("example.strict:form.example.loose:form.example.loose", 1)
        assertTrue(repeated is Outcome.Failure && repeated.exception is PolicyIdentityError.DuplicateLink, "got $repeated")
        val onlyForms = module.resolve("form.example.loose", 1)
        assertTrue(onlyForms is Outcome.Failure && onlyForms.exception is PolicyIdentityError, "got $onlyForms")
        val inTheMiddle = module.resolve("example.strict:form.example.loose:lenient", 1)
        assertTrue(inTheMiddle is Outcome.Failure && inTheMiddle.exception is PolicyIdentityError, "got $inTheMiddle")
    }

    @Test
    fun theCheckReportsSamePolicyInFormOrNotComparable() {
        assertEquals(Comparability.SamePolicy, module.comparability("example.strict", 1, "example.strict", 1).dataOrThrow())
        assertEquals(Comparability.InForm(value), module.comparability("example.strict", 1, "example.strict:lenient", 1).dataOrThrow())
        assertEquals(Comparability.NotComparable, module.comparability("example.strict", 1, "example.plain", 1).dataOrThrow())
        // Not comparable until the caller opts in, and then comparable in exactly that form.
        assertEquals(Comparability.NotComparable, module.comparability("example.strict", 1, "example.other", 1).dataOrThrow())
        assertEquals(
            Comparability.InForm(loose),
            module.comparability("example.strict:form.example.loose", 1, "example.other:form.example.loose", 1).dataOrThrow(),
        )
    }

    @Test
    fun severalSharedFormsReportTheFirstByName() {
        val result = module.comparability(
            "example.strict:form.example.loose:form.example.wide", 1,
            "example.strict:form.example.wide", 1,
        ).dataOrThrow()
        // Shared: example.value (declared) and example.wide (opted into). First by name wins.
        assertEquals(Comparability.InForm(value), result)
    }

    @Test
    fun anUnresolvableIdFailsTheCheck() {
        val outcome = module.comparability("example.strict", 1, "example.missing", 1)
        assertTrue(outcome is Outcome.Failure && outcome.exception is PolicyIdentityError, "got $outcome")
        val both = module.comparability("example.missing", 1, "example.missing", 1)
        assertTrue(both is Outcome.Failure, "two unresolvable ids are never comparable")
    }

    @Test
    fun aComposedPolicyDeclaresNoForms() {
        val step = object : Policy, NormalizationStep {
            override val id = "example.step"
            override val version = 1
            override val links = listOf(PolicyLink("example.step", LinkKind.Base, StepPhase.Map))
            override val forms = setOf(value)
            override fun apply(value: String) = value
        }
        val composed = ComposedPolicy(strict, listOf(step))
        assertTrue(composed.forms.isEmpty())
        assertFailsWith<IllegalArgumentException> { composed.withForms(setOf(loose)) }
    }
}
