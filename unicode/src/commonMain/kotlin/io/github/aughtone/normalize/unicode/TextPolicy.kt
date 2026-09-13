package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.common.LinkKind
import io.github.aughtone.normalize.common.NormalizationStep
import io.github.aughtone.normalize.common.Policy
import io.github.aughtone.normalize.common.PolicyId
import io.github.aughtone.normalize.common.PolicyIdentityError
import io.github.aughtone.normalize.common.PolicyLink
import io.github.aughtone.normalize.common.StepPhase
import io.github.aughtone.types.outcome.Outcome
import io.github.aughtone.types.outcome.runOutcome

/**
 * A text normalization policy, configured by the caller and frozen by its identity.
 *
 * Build one by naming the rules you want and the character set each runs over:
 *
 * ```
 * TextPolicy { ascii { trim(); lowercase() } }                               // text+trim+lower
 *
 * TextPolicy(UnicodeRelease.U17) { unicode { trim(); casefold(); nfc() } }  // text.u17+trim+casefold+nfc
 *
 * TextPolicy(UnicodeRelease.U17) {                                           // text.u17+trim+lower.ascii+non-empty
 *     unicode { trim() }
 *     ascii { lowercase() }
 *     nonEmpty()
 * }
 * ```
 *
 * ## Blocks choose the character set, never the order
 *
 * Rules always run in one fixed order - strip control characters, trim, collapse or remove spaces, case,
 * normalization form, then the non-empty check - however they were written. Two callers enabling the same
 * rules therefore always produce the same bytes. Writing them in a different order is allowed, and it
 * is reported through [warnings] because code that reads as one order and runs as another is confusing.
 * [id] always lists the rules in the order they actually run.
 *
 * ## The id states the Unicode release once, and only when it is used
 *
 * A policy whose rules are all ASCII has the base `text` and names no release, so a new Unicode release
 * never gives it a second id for the same bytes. A policy with any Unicode rule has the base `text.u17`,
 * every Unicode rule in it runs against that release's frozen tables, and a rule running over ASCII
 * inside it is marked `.ascii`. Nothing reads the platform's Unicode data.
 *
 * ## Canonical and compatibility forms are not interchangeable
 *
 * `nfc` and `nfd` are lossless. `nfkc` and `nfkd` fold ligatures, superscripts and full-width characters
 * into plain ones: right for search and loose matching, wrong for a token you expect to round-trip.
 *
 * ## Not an identifier normalizer
 *
 * This is for general text fields. An email address, a domain, a phone number or a handle has its own
 * rules and its own normalizer - `normalizeEmail`, `normalizeDomain`, `normalizePhone`,
 * `normalizeUsername` - and running one through generic text rules loses the structure those rules
 * exist to respect.
 *
 * ## Also a step
 *
 * A text policy is a [NormalizationStep], so a module that carries no Unicode data can accept one and
 * compose it whole: `username.basic+text.u17+nfc`.
 */
class TextPolicy internal constructor(
    /** The Unicode release this policy's Unicode rules run against, or `null` when every rule is ASCII. */
    val release: UnicodeRelease?,
    internal val rules: List<ConfiguredRule>,
    /** Anything about how this policy was written that is worth knowing, such as rules out of order. */
    val warnings: List<TextPolicyWarning>,
) : Policy, NormalizationStep {

    override val links: List<PolicyLink> = buildList {
        add(PolicyLink(if (release == null) BASE else "$BASE.${release.segment}", LinkKind.Base, StepPhase.Map))
        for (configured in rules) add(PolicyLink(linkName(configured), LinkKind.Parameter))
    }

    override val id: String = PolicyId.of(links).dataOrThrow().rendered

    override val version: Int = 1

    /**
     * Apply every rule in order. Used when this policy is composed into another module's chain; a
     * direct caller goes through `normalizeText`, which also checks the input and reports the identity
     * to store beside the result.
     */
    override fun apply(value: String): String = rules.fold(value) { text, configured -> configured.apply(text) }

    /** `.ascii` marks a rule running over ASCII only inside a policy that names a Unicode release. */
    private fun linkName(configured: ConfiguredRule): String =
        if (release != null && configured.ascii && configured.rule.characterSet) {
            "${configured.rule.link}$ASCII_MARKER"
        } else {
            configured.rule.link
        }

    override fun equals(other: Any?): Boolean =
        this === other || (other is TextPolicy && other.id == id && other.version == version)

    override fun hashCode(): Int = 31 * id.hashCode() + version

    override fun toString(): String = id

    companion object {
        internal const val BASE = "text"
        internal const val ASCII_MARKER = ".ascii"

        /**
         * Receives every warning a newly built policy carries. Prints by default, so a mistake is visible
         * without any setup; replace it to route warnings to your own logging, or set it to `{}` to
         * silence them. A warning never changes a policy's id or its output.
         */
        var warningHandler: (TextPolicy, TextPolicyWarning) -> Unit = { policy, warning ->
            println("aughtone-normalize: $policy: $warning")
        }

        /** Build a policy whose rules are all ASCII. It names no Unicode release and never goes stale. */
        operator fun invoke(configure: AsciiTextPolicyBuilder.() -> Unit): TextPolicy =
            AsciiTextPolicyBuilder().apply(configure).build(release = null)

        /** Build a policy whose Unicode rules run against [release]'s frozen data. */
        operator fun invoke(release: UnicodeRelease, configure: UnicodeTextPolicyBuilder.() -> Unit): TextPolicy =
            UnicodeTextPolicyBuilder().apply(configure).build(release)

        /** Canonical composition, frozen against Unicode 17. The form to reach for by default. */
        val NfcU17: TextPolicy = TextPolicy(UnicodeRelease.U17) { unicode { nfc() } }

        /** Canonical decomposition, frozen against Unicode 17. */
        val NfdU17: TextPolicy = TextPolicy(UnicodeRelease.U17) { unicode { nfd() } }

        /** Compatibility composition, frozen against Unicode 17. **Lossy** - see the class docs. */
        val NfkcU17: TextPolicy = TextPolicy(UnicodeRelease.U17) { unicode { nfkc() } }

        /** Compatibility decomposition, frozen against Unicode 17. **Lossy** - see the class docs. */
        val NfkdU17: TextPolicy = TextPolicy(UnicodeRelease.U17) { unicode { nfkd() } }

        /**
         * A convenience configuration: ASCII trim and ASCII lowercase, the byte-stable replacement for a
         * platform `trim().lowercase()`. Exactly `TextPolicy { ascii { trim(); lowercase() } }`.
         */
        val TrimLowercase: TextPolicy = TextPolicy { ascii { trim(); lowercase() } }

        /**
         * A convenience configuration for caseless matching of general text: Unicode trim, full case
         * folding, then NFC. Exactly `TextPolicy(U17) { unicode { trim(); casefold(); nfc() } }`.
         */
        val CaselessU17: TextPolicy = TextPolicy(UnicodeRelease.U17) { unicode { trim(); casefold(); nfc() } }

        /** Every named configuration, in the order they are documented. */
        internal val all: List<TextPolicy> = listOf(NfcU17, NfdU17, NfkcU17, NfkdU17, TrimLowercase, CaselessU17)

        /** Every base and rule link a text policy can contain, for parsing a chain that includes one. */
        internal val publishedLinks: List<PolicyLink> = buildList {
            add(PolicyLink(BASE, LinkKind.Base, StepPhase.Map))
            for (release in UnicodeRelease.entries) add(PolicyLink("$BASE.${release.segment}", LinkKind.Base, StepPhase.Map))
            for (rule in TextRule.entries) {
                add(PolicyLink(rule.link, LinkKind.Parameter))
                if (rule.characterSet && !rule.unicodeOnly) add(PolicyLink("${rule.link}$ASCII_MARKER", LinkKind.Parameter))
            }
        }

        /**
         * Rebuild the policy a stored text id names, or fail. The id must be exactly what building that
         * configuration renders: rules in application order, a release only when a rule uses it, and
         * `.ascii` only where it means something. Any other spelling is refused rather than accepted as
         * equivalent, because a policy with two ids has values that never match each other.
         */
        internal fun parse(id: String): Outcome<TextPolicy> = runOutcome {
            val names = PolicyId.split(id).dataOrThrow()
            val base = names.first()
            val release = when {
                base == BASE -> null
                base.startsWith("$BASE.") ->
                    UnicodeRelease.ofSegment(base.removePrefix("$BASE.")) ?: throw PolicyIdentityError.UnknownLink(id, base)
                else -> throw PolicyIdentityError.UnknownLink(id, base)
            }
            val rules = names.drop(1).map { name ->
                val marked = name.endsWith(ASCII_MARKER)
                val rule = TextRule.ofLink(name.removeSuffix(ASCII_MARKER)) ?: throw PolicyIdentityError.UnknownLink(id, name)
                if (marked && (release == null || !rule.characterSet || rule.unicodeOnly)) {
                    throw PolicyIdentityError.NotCanonical(id)
                }
                // A Unicode-only rule needs data, and an id without a release names none to use.
                if (rule.unicodeOnly && release == null) throw PolicyIdentityError.NotCanonical(id)
                ConfiguredRule(rule, ascii = if (rule.characterSet) release == null || marked else false)
            }
            val policy = try {
                TextPolicyBuilder.build(release, rules, report = false)
            } catch (error: TextPolicyError) {
                throw PolicyIdentityError.NotCanonical(id)
            }
            if (policy.id != id) throw PolicyIdentityError.NotCanonical(id)
            policy
        }
    }
}

/** Why a text policy could not be built. Thrown when the policy is created, never while normalizing. */
sealed class TextPolicyError(message: String) : IllegalArgumentException(message) {

    /** The same rule appears twice, in one block or across both. A rule runs once, over one character set. */
    class RepeatedRule(val rule: String) : TextPolicyError("text policy: '$rule' is configured more than once")

    /** Two rules that are alternatives to each other: `lowercase` with `casefold`, or two normalization forms. */
    class ContradictoryRules(val first: String, val second: String) :
        TextPolicyError("text policy: '$first' and '$second' cannot both apply")

    /**
     * A step composed after this policy was frozen against a different Unicode release. One chain runs
     * against one release's data, so `text.u17+…+skeleton.u18` is refused rather than mixed.
     */
    class MismatchedRelease(val policyRelease: String, val stepRelease: String) :
        TextPolicyError("text policy: a $stepRelease step cannot follow a $policyRelease policy")

    /** A Unicode release was given but every rule runs over ASCII, so the id would name data nothing uses. */
    class UnusedRelease(val release: String) :
        TextPolicyError("text policy: $release is named but no rule uses Unicode data; build it without a release")
}

/** Something about how a text policy was written that deserves attention. Never affects its output. */
sealed class TextPolicyWarning {

    /**
     * The rules were written in a different order from the one they run in. Nothing is wrong with the
     * output - rules always run in [applied] order - but the code reads as if they ran in [written] order.
     */
    data class OrderDiffersFromApplication(val written: List<String>, val applied: List<String>) : TextPolicyWarning() {
        override fun toString(): String =
            "rules written as [${written.joinToString()}] run as [${applied.joinToString()}]"
    }
}

@DslMarker
internal annotation class TextPolicyDsl

/** The rules a character-set block offers. */
@TextPolicyDsl
sealed class TextRules(private val ascii: Boolean, private val sink: MutableList<ConfiguredRule>) {

    internal fun add(rule: TextRule) {
        sink += ConfiguredRule(rule, ascii)
    }

    /** Remove control characters, keeping the whitespace ones for the space rules to handle. */
    fun stripControl() = add(TextRule.StripControl)

    /** Remove leading and trailing whitespace. */
    fun trim() = add(TextRule.Trim)

    /** Replace each run of whitespace with a single U+0020 SPACE. */
    fun collapseSpace() = add(TextRule.CollapseSpace)

    /** Remove whitespace everywhere. */
    fun removeSpace() = add(TextRule.RemoveSpace)

    /** Map to lowercase: `A`-`Z` only over ASCII, simple case mappings over Unicode. */
    fun lowercase() = add(TextRule.Lowercase)

    /** Map to uppercase: `a`-`z` only over ASCII, simple case mappings over Unicode. */
    fun uppercase() = add(TextRule.Uppercase)
}

/** Rules that run over ASCII only: the six ASCII whitespace characters, C0 controls and DEL, and `A`-`Z`. */
class AsciiRules internal constructor(sink: MutableList<ConfiguredRule>) : TextRules(ascii = true, sink)

/** Rules that run over Unicode, against the policy's frozen release. */
class UnicodeRules internal constructor(sink: MutableList<ConfiguredRule>) : TextRules(ascii = false, sink) {

    /** Full case folding (statuses C and F), the folding Unicode defines for caseless matching. */
    fun casefold() = add(TextRule.Casefold)

    /** Canonical composition. */
    fun nfc() = add(TextRule.Nfc)

    /** Canonical decomposition. */
    fun nfd() = add(TextRule.Nfd)

    /** Compatibility composition. **Lossy.** */
    fun nfkc() = add(TextRule.Nfkc)

    /** Compatibility decomposition. **Lossy.** */
    fun nfkd() = add(TextRule.Nfkd)
}

/** Configures a policy whose rules are all ASCII. */
@TextPolicyDsl
open class AsciiTextPolicyBuilder internal constructor() {

    internal val written = mutableListOf<ConfiguredRule>()

    /** Rules that run over ASCII only. */
    fun ascii(configure: AsciiRules.() -> Unit) {
        AsciiRules(written).configure()
    }

    /** Refuse a value that is empty once every other rule has run. */
    fun nonEmpty() {
        written += ConfiguredRule(TextRule.NonEmpty, ascii = false)
    }

    internal fun build(release: UnicodeRelease?): TextPolicy = TextPolicyBuilder.build(release, written, report = true)
}

/** Configures a policy that may use Unicode rules as well as ASCII ones. */
@TextPolicyDsl
class UnicodeTextPolicyBuilder internal constructor() : AsciiTextPolicyBuilder() {

    /** Rules that run over Unicode, against the policy's release. */
    fun unicode(configure: UnicodeRules.() -> Unit) {
        UnicodeRules(written).configure()
    }
}

internal object TextPolicyBuilder {

    /** Validate [written], sort it into application order, and build the policy. */
    fun build(release: UnicodeRelease?, written: List<ConfiguredRule>, report: Boolean): TextPolicy {
        // The builders make these unreachable; they guard every other path to a policy, such as parsing.
        for (configured in written) {
            require(!configured.rule.unicodeOnly || (release != null && !configured.ascii)) {
                "text policy: '${configured.rule.link}' needs Unicode data and a release"
            }
        }
        val seen = HashSet<TextRule>()
        val byRank = HashMap<Int, TextRule>()
        for (configured in written) {
            if (!seen.add(configured.rule)) throw TextPolicyError.RepeatedRule(configured.rule.link)
            val existing = byRank.put(configured.rule.rank, configured.rule)
            if (existing != null) throw TextPolicyError.ContradictoryRules(existing.link, configured.rule.link)
        }
        if (release != null && written.none { it.rule.characterSet && !it.ascii }) {
            throw TextPolicyError.UnusedRelease(release.segment)
        }

        val applied = written.sortedBy { it.rule.rank }
        val warnings = if (applied != written) {
            listOf(TextPolicyWarning.OrderDiffersFromApplication(written.map { it.rule.link }, applied.map { it.rule.link }))
        } else {
            emptyList()
        }

        val policy = TextPolicy(release, applied, warnings)
        if (report) for (warning in warnings) TextPolicy.warningHandler(policy, warning)
        return policy
    }
}
