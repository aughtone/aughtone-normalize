package io.github.aughtone.normalize.common

import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Combining resolvers never resolves less than the modules would alone, and a composed chain resolves
 * group by group or not at all.
 *
 * The modules here are stand-ins with the same shapes the real ones have: one that enumerates its
 * policies, one that rebuilds them on demand, and one that publishes a step.
 */
class CompositePolicyResolverTest {

    private class Plain(override val id: String, override val version: Int = 1) : Policy

    private class Step(name: String) : Policy, NormalizationStep {
        override val links = listOf(PolicyLink(name, LinkKind.Base, StepPhase.Map))
        override val id = name
        override val version = 1
        override fun apply(value: String) = value.reversed()
    }

    /** Enumerates its policies, like most modules. */
    private object Enumerated : PublishedPolicies() {
        val base = PolicyLink("example.base", LinkKind.Base)
        override val policies: List<Policy> = listOf(Plain("example.base"), Plain("example.base:lenient"))
        override val links: List<PolicyLink> = listOf(base, PolicyLink.Lenient)
    }

    /** Rebuilds any `built:anything` on demand, the way regions and configured policies are resolved. */
    private object Rebuilt : PolicyResolver {
        override val policies: List<Policy> = emptyList()
        override val links: List<PolicyLink> = listOf(PolicyLink("built", LinkKind.Base, StepPhase.Map))
        override fun resolve(id: String, version: Int): Outcome<Policy> = runOutcome {
            if (!id.startsWith("built:")) throw PolicyIdentityError.UnknownLink(id, id.substringBefore(':'))
            if (version != 1) throw PolicyIdentityError.VersionMismatch(id, version, listOf(1))
            object : Policy, NormalizationStep {
                override val id = id
                override val version = 1
                override val links = id.split(':').mapIndexed { index, name ->
                    if (index == 0) PolicyLink(name, LinkKind.Base, StepPhase.Map) else PolicyLink(name, LinkKind.Parameter)
                }
                override fun apply(value: String) = value.uppercase()
            }
        }
    }

    /** Publishes one step. */
    private object Steps : PublishedPolicies() {
        val step = Step("mirror.u17")
        override val policies: List<Policy> = listOf(step)
        override val links: List<PolicyLink> = step.links
    }

    private val all = Enumerated + Rebuilt + Steps

    @Test
    fun anIdAModuleRebuildsResolvesThroughTheComposite() {
        val alone = Rebuilt.resolve("built:x", 1)
        val combined = all.resolve("built:x", 1)
        assertTrue(alone is Outcome.Success && combined is Outcome.Success, "the composite must ask the module")
        assertEquals(alone.data.id, combined.data.id)
    }

    @Test
    fun anEnumeratedIdStillResolves() {
        val outcome = all.resolve("example.base:lenient", 1)
        assertTrue(outcome is Outcome.Success)
        assertEquals("example.base:lenient", outcome.data.id)
    }

    @Test
    fun aComposedChainResolvesToItsBaseAndSteps() {
        val outcome = all.resolve("example.base:built:x:mirror.u17", 1)
        assertTrue(outcome is Outcome.Success, "got $outcome")
        val composed = outcome.data as ComposedPolicy
        assertEquals("example.base", composed.base.id)
        assertEquals(listOf("built:x", "mirror.u17"), composed.steps.map { step -> step.links.joinToString(":") { it.name } })
        assertEquals("example.base:built:x:mirror.u17", composed.id)
        assertEquals(1, composed.version)
    }

    @Test
    fun aChainNamingAModuleNotInTheResolverFails() {
        val withoutSteps = Enumerated + Rebuilt
        val outcome = withoutSteps.resolve("example.base:mirror.u17", 1)
        assertTrue(outcome is Outcome.Failure && outcome.exception is PolicyIdentityError.UnknownLink, "got $outcome")
    }

    @Test
    fun aModuleThatRecognizesAnIdSpeaksForIt() {
        val outcome = all.resolve("built:x", 2)
        assertTrue(outcome is Outcome.Failure && outcome.exception is PolicyIdentityError.VersionMismatch, "got $outcome")
    }

    @Test
    fun aGroupThatCannotRunAsAStepFailsTheChain() {
        val outcome = (Enumerated + Rebuilt + Steps + OtherBase).resolve("example.base:other.base", 1)
        assertTrue(outcome is Outcome.Failure && outcome.exception is PolicyIdentityError.NotAStep, "got $outcome")
    }

    /** A phased base that is a policy but not a step. */
    private object OtherBase : PublishedPolicies() {
        override val policies: List<Policy> = listOf(Plain("other.base"))
        override val links: List<PolicyLink> = listOf(PolicyLink("other.base", LinkKind.Base, StepPhase.Map))
    }
}
