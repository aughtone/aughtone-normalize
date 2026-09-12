package io.github.aughtone.normalize.phone

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.PublishedPolicies
import io.github.aughtone.phonenumber.PhoneNumberUtil
import io.github.aughtone.types.outcome.Outcome

/**
 * A frozen phone-normalization policy.
 *
 * Four of them, covering two independent choices, neither of which is ever made for the caller:
 *
 * - **Where the country code comes from.** [E164] accepts only input that carries its own;
 *   [e164ForRegion] reads national-format input against a region the caller named. Nothing guesses.
 * - **What happens to a number the metadata calls invalid.** The default refuses it; the lenient
 *   variants normalize it anyway. A number can be well-formed and not exist, and which of those a caller
 *   wants depends on whether they are dialling it or matching it.
 *
 * ## The region is in the identity, and that has a consequence worth reading
 *
 * `phone.e164` and `phone.e164+region-ca` produce **identical bytes** for input already in E.164 form,
 * and are still different identities. Matching is scoped by identity, so two consumers using different
 * policies will not match each other even where the strings agree. Anyone coordinating between systems
 * has to agree on the same constant, not merely on "E.164" - see ADR-0001.
 *
 * ## Why a phone policy carries a version at all
 *
 * Unlike the Unicode-backed policies, nothing here is frozen against a table this repository controls:
 * the country metadata belongs to the phonenumber library. A version is minted when moving to a newer
 * release of that library changes what this policy produces for some input in the frozen corpus. Most
 * callers will never see a bump - and the field exists so that the one who does can tell.
 */
class PhonePolicy internal constructor(
    override val id: String,
    override val version: Int,
    internal val region: String?,
    internal val requiresValidity: Boolean,
    internal val dropsUnsupportedCharacters: Boolean,
) : Policy {

    internal val hasRegion: Boolean get() = region != null

    override fun toString(): String = id

    companion object {
        /** The base link every phone policy is built on. */
        internal val Base: PolicyLink = PolicyLink("phone.e164", LinkKind.Base)

        /**
         * For input that already carries its own country code. National-format input is refused rather
         * than interpreted against a region nobody named.
         */
        val E164: PhonePolicy = PhonePolicy(
            id = chainOf(Base),
            version = 1,
            region = null,
            requiresValidity = true,
            dropsUnsupportedCharacters = false,
        )

        /** As [E164], but a number the metadata calls invalid is normalized rather than refused. */
        val E164Lenient: PhonePolicy = PhonePolicy(
            id = chainOf(Base, PolicyLink.Lenient),
            version = 1,
            region = null,
            requiresValidity = false,
            dropsUnsupportedCharacters = true,
        )

        /**
         * For national-format input, read against [region] - an ISO region code such as `"ca"`.
         *
         * The region is part of the policy identity, so it travels with everything derived under it.
         *
         * @throws IllegalArgumentException if [region] is not two ASCII letters, or if the phonenumber
         * library carries no metadata for it. Failing here is the point: a region that cannot be
         * resolved would otherwise surface as a normalization failure much later, or worse, as a
         * plausible number for somewhere else.
         */
        fun e164ForRegion(region: String): PhonePolicy = regionPolicy(region, lenient = false)

        /** As [e164ForRegion], with validity checking relaxed. */
        fun e164ForRegionLenient(region: String): PhonePolicy = regionPolicy(region, lenient = true)

        /** The policies published without a region. The region ones are built on demand. */
        internal val all: List<PhonePolicy> = listOf(E164, E164Lenient)

        private fun regionPolicy(region: String, lenient: Boolean): PhonePolicy {
            val normalized = region.lowercase()
            require(normalized.length == 2 && normalized.all { it in 'a'..'z' }) {
                "region must be a two-letter ISO code, was: $region"
            }
            require(hasMetadata(normalized.uppercase())) {
                "no phone metadata for region: $region"
            }
            val links = buildList {
                add(Base)
                add(PolicyLink("region-$normalized", LinkKind.Parameter))
                if (lenient) add(PolicyLink.Lenient)
            }
            return PhonePolicy(
                id = chainOf(*links.toTypedArray()),
                version = 1,
                region = normalized.uppercase(),
                requiresValidity = !lenient,
                dropsUnsupportedCharacters = lenient,
            )
        }

        /**
         * Whether the library carries metadata for a region.
         *
         * It exposes no list of regions, so this asks the only question it answers: parsing anything
         * against an unknown region fails immediately, before the number itself is considered. The probe
         * is a well-formed international number, so a failure can only mean the region.
         */
        private fun hasMetadata(region: String): Boolean = try {
            PhoneNumberUtil.parse(REGION_PROBE, region)
            true
        } catch (failure: PhoneNumberUtil.NumberParseException) {
            false
        }

        private const val REGION_PROBE = "+12125551234"

        private fun chainOf(vararg links: PolicyLink): String =
            when (val outcome = PolicyId.of(links.toList())) {
                is Outcome.Success -> outcome.data.rendered
                is Outcome.Failure -> error("not a valid policy chain: ${outcome.exception.message}")
            }
    }
}

/**
 * Every policy this module publishes without a region, and the links every phone policy is built from.
 *
 * A region policy is built on demand rather than enumerated - there are hundreds of regions and a caller
 * uses one or two - so resolution accepts any `region-xx` link whose metadata exists, and rebuilds the
 * policy from it. That keeps the round trip total: anything this module can produce, it can resolve.
 */
object PhonePolicies : PublishedPolicies() {

    override val policies: List<Policy> = PhonePolicy.all

    override val links: List<PolicyLink> = listOf(PhonePolicy.Base, PolicyLink.Lenient)

    override fun resolve(id: String, version: Int): Outcome<Policy> {
        val region = regionOf(id) ?: return super.resolve(id, version)
        val rebuilt = try {
            if (id.endsWith("+lenient")) {
                PhonePolicy.e164ForRegionLenient(region)
            } else {
                PhonePolicy.e164ForRegion(region)
            }
        } catch (failure: IllegalArgumentException) {
            return super.resolve(id, version)
        }
        return if (rebuilt.id == id && rebuilt.version == version) Outcome.Success(rebuilt) else super.resolve(id, version)
    }

    /** The region named by a chain like `phone.e164+region-ca+lenient`, if it names one. */
    private fun regionOf(id: String): String? = id.split('+')
        .firstOrNull { it.startsWith("region-") }
        ?.removePrefix("region-")
}
