package io.github.aughtone.normalize.common

import io.github.aughtone.types.outcome.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The grammar and ordering rules for a policy identity.
 *
 * These assertions are the published id format, not an implementation detail: a consumer stores a
 * rendered id and expects this parser to accept it years later, so loosening a rule here silently
 * changes what a stored identity means.
 */
class PolicyIdTest {

    private val emailBase = PolicyLink("email.byte-stable", LinkKind.Base)
    private val phoneBase = PolicyLink("phone.e164", LinkKind.Base)
    private val regionCa = PolicyLink("region-ca", LinkKind.Parameter)
    private val nfc = PolicyLink("nfc.u17", LinkKind.Step, StepPhase.Normalize)
    private val punycode = PolicyLink("punycode.u17", LinkKind.Step, StepPhase.Encode)
    private val known = listOf(emailBase, phoneBase, regionCa, PolicyLink.Lenient, nfc, punycode)

    private fun parsed(id: String): PolicyId =
        when (val o = PolicyId.parse(id, known)) {
            is Outcome.Success -> o.data
            is Outcome.Failure -> throw AssertionError("expected <$id> to parse, got ${o.exception::class.simpleName}")
        }

    private inline fun <reified E : PolicyIdentityError> assertRefused(id: String) {
        when (val o = PolicyId.parse(id, known)) {
            is Outcome.Success -> throw AssertionError("expected <$id> to be refused, got ${o.data.rendered}")
            is Outcome.Failure -> assertTrue(
                o.exception is E,
                "expected ${E::class.simpleName} for <$id>, got ${o.exception::class.simpleName}",
            )
        }
    }

    @Test
    fun everyChainShapeRoundTrips() {
        // One shape per row of the grammar: bare base, parameter, relaxation, both, and steps.
        val shapes = listOf(
            "email.byte-stable",
            "email.byte-stable+lenient",
            "phone.e164+region-ca",
            "phone.e164+region-ca+lenient",
            "email.byte-stable+nfc.u17",
            "email.byte-stable+nfc.u17+punycode.u17",
        )
        for (id in shapes) {
            assertEquals(id, parsed(id).rendered, "<$id> did not render back to itself")
        }
    }

    @Test
    fun theBaseComesFirstAndThereIsExactlyOne() {
        assertEquals(emailBase, parsed("email.byte-stable+lenient").base)
        assertRefused<PolicyIdentityError.MissingBase>("lenient")
        assertRefused<PolicyIdentityError.MultipleBases>("email.byte-stable+phone.e164")
    }

    @Test
    fun linksOutOfOrderAreRefusedRatherThanSorted() {
        // A relaxation belongs after a parameter, and a step after both. Accepting either order would
        // give one policy two valid ids, and an id that is not unique does not identify anything.
        assertRefused<PolicyIdentityError.OutOfOrder>("phone.e164+lenient+region-ca")
        assertRefused<PolicyIdentityError.OutOfOrder>("email.byte-stable+punycode.u17+nfc.u17")
    }

    @Test
    fun malformedLinksAreRefused() {
        assertRefused<PolicyIdentityError.MalformedLink>("Email.Byte-Stable")
        assertRefused<PolicyIdentityError.MalformedLink>("phone.e164+region-CA")
        assertRefused<PolicyIdentityError.MalformedLink>("email.byte-stable+")
        assertRefused<PolicyIdentityError.MalformedLink>("email..byte-stable")
        assertRefused<PolicyIdentityError.MalformedLink>("-email.byte-stable")
        assertRefused<PolicyIdentityError.EmptyId>("")
    }

    @Test
    fun duplicateAndUnknownLinksAreRefused() {
        assertRefused<PolicyIdentityError.DuplicateLink>("email.byte-stable+lenient+lenient")
        assertRefused<PolicyIdentityError.UnknownLink>("email.byte-stable+strict")
        assertRefused<PolicyIdentityError.UnknownLink>("skeleton.u17")
    }

    @Test
    fun aLinkReportsTheDataVersionItWasFrozenAgainst() {
        // Read from the name, never stored beside it: the name is what a consumer keeps, so the two
        // cannot be allowed to disagree about which data produced a value.
        assertEquals("u17", nfc.dataVersion)
        assertEquals("u15-1", PolicyLink("nfc.u15-1", LinkKind.Step, StepPhase.Normalize).dataVersion)
        assertEquals(null, emailBase.dataVersion)
        assertEquals(null, PolicyLink.Lenient.dataVersion)
        assertEquals(null, PolicyLink("domain.ascii", LinkKind.Base).dataVersion)
    }

    @Test
    fun splitChecksOnlyTheLexicalGrammar() {
        // Config validation happens before any module is consulted, so an unknown-but-well-formed link
        // passes split and fails later at resolution, where the error can name the missing module.
        val o = PolicyId.split("email.byte-stable+not-a-real-link")
        assertTrue(o is Outcome.Success)
        assertEquals(listOf("email.byte-stable", "not-a-real-link"), o.data)
    }
}
