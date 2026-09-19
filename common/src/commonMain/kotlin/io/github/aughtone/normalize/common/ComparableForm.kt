package io.github.aughtone.normalize.common

import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * A named canonical text that several policies can write, so values they produce are comparable across
 * those policies.
 *
 * ## Why this exists
 *
 * Matching is scoped by policy identity: two values normalized under different policies never match,
 * even when their bytes agree, because agreeing bytes are often a coincidence. But many real comparisons
 * are between inputs written differently that mean the same thing - a phone number read with and without
 * a region, a strict value and its lenient sibling, an IPv4 address and its IPv4-mapped IPv6 spelling. A
 * comparable form makes such a match **explicit**: a policy declares the forms it writes, and two values
 * are comparable only in a form both of their policies declare. See [Comparability].
 *
 * ## Declared, or opted into
 *
 * A policy declares a form in [Policy.forms] where its rules guarantee the meaning - a strict/lenient
 * pair, for instance. Where a comparison is an interpretation instead, the policy offers the form in
 * [Policy.offeredForms] and a caller opts in by naming it in the id: `ipv4.inet.aton:form.ipv4.address`.
 * Either way the declaration comes back when a stored id is resolved, so nothing extra has to be stored.
 *
 * ## Frozen, and additive
 *
 * A form may be added to a published policy - it changes no bytes and only permits explicit matching -
 * but never removed, because a system may already rely on it. Confusable skeletons never declare one: a
 * skeleton is a check, not an identity.
 *
 * @property name The form's name, spelled with the link grammar: `phone.e164`, `ipv4.address`.
 * @throws IllegalArgumentException if [name] is not a valid link name.
 */
class ComparableForm(val name: String) : Comparable<ComparableForm> {

    init {
        require(PolicyLink.isValidLinkName(name)) {
            "a comparable form name must be lowercase segments of [a-z0-9-] joined by '.', was: $name"
        }
    }

    /** The link a caller appends to an id to opt into this form: `form.ipv4.address`. */
    val link: PolicyLink get() = PolicyLink("$FORM_PREFIX$name", LinkKind.Form)

    override fun compareTo(other: ComparableForm): Int = name.compareTo(other.name)

    override fun equals(other: Any?): Boolean = this === other || (other is ComparableForm && other.name == name)

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    companion object {
        /** The prefix every form link carries: `form.`. */
        const val FORM_PREFIX: String = "form."

        /** The form a form link names, or `null` if [link] is not one. */
        fun ofLink(link: String): ComparableForm? =
            if (link.startsWith(FORM_PREFIX) && link.length > FORM_PREFIX.length) ComparableForm(link.removePrefix(FORM_PREFIX)) else null
    }
}

/**
 * A policy with forms a caller opted into, as [Policy.withForms] returns by default.
 *
 * It produces exactly the bytes of [policy]; only its identity differs, recording the choice. A module
 * whose normalizer takes a concrete policy type overrides [Policy.withForms] to return that type instead,
 * so the opted-in id is what its normalizer stores.
 */
class OptedInPolicy internal constructor(val policy: Policy, val optedIn: Set<ComparableForm>) : Policy {

    override val id: String = (listOf(policy.id) + optedIn.sorted().map { it.link.name }).joinToString(":")

    override val version: Int = policy.version

    override val forms: Set<ComparableForm> = policy.forms + optedIn

    override val offeredForms: Set<ComparableForm> = policy.offeredForms

    override fun withForms(forms: Set<ComparableForm>): Policy = policy.withForms(optedIn + forms)

    override fun equals(other: Any?): Boolean = this === other || (other is OptedInPolicy && other.id == id && other.version == version)

    override fun hashCode(): Int = 31 * id.hashCode() + version

    override fun toString(): String = id
}

/** Whether two stored values can be matched, and why. The result of [PolicyResolver.comparability]. */
sealed class Comparability {

    /** Both values came from the same policy: the ordinary case. */
    object SamePolicy : Comparability() {
        override fun toString(): String = "SamePolicy"
    }

    /**
     * The values came from different policies that both write [form], so they are comparable because of
     * that declaration. When the policies share several forms, [form] is the first by name.
     */
    data class InForm(val form: ComparableForm) : Comparability()

    /** Different policies sharing no form. The values must not be matched. */
    object NotComparable : Comparability() {
        override fun toString(): String = "NotComparable"
    }
}

/**
 * Whether a value stored under `(idA, versionA)` can be matched against one stored under
 * `(idB, versionB)`, and why - see [Comparability].
 *
 * Both ids are resolved, so the answer rests only on what the stored identities say, including forms a
 * caller opted into. Fails with a [PolicyIdentityError] if either id does not resolve.
 */
fun PolicyResolver.comparability(idA: String, versionA: Int, idB: String, versionB: Int): Outcome<Comparability> =
    runOutcome {
        val a = resolve(idA, versionA).dataOrThrow()
        val b = resolve(idB, versionB).dataOrThrow()
        when {
            a.id == b.id && a.version == b.version -> Comparability.SamePolicy
            else -> a.forms.intersect(b.forms).minOrNull()?.let { Comparability.InForm(it) } ?: Comparability.NotComparable
        }
    }
