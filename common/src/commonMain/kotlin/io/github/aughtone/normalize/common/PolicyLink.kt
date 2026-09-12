package io.github.aughtone.normalize.common

/**
 * What a link contributes to a policy chain. The kind decides where the link may appear: a chain is a
 * [Base], then its [Parameter]s, then its [Relaxation]s, then any [Step]s.
 *
 * The order is not cosmetic. One policy must have exactly one valid id, or the id stops identifying the
 * policy - so a chain in any other order is refused rather than sorted into place.
 */
enum class LinkKind {
    /**
     * A rule-set, and the first link in every chain: `email.byte-stable`, `nfc.u17`.
     *
     * A base that another module can also apply as a step declares the [StepPhase] at which it runs -
     * NFC is a policy in its own right and a step inside an email chain, and it is one thing either
     * way. Without a phase, a base can only open a chain.
     */
    Base,

    /** Side input the base needs, named in the identity so it travels with derived values: `region-ca`. */
    Parameter,

    /** Relaxes one of the base's default rules while keeping every guarantee: `lenient`. */
    Relaxation,

    /** A transform that only ever follows a base, contributed by another module. */
    Step,
}

/**
 * When a [LinkKind.Step] runs. Steps in a chain are ordered by phase, because there is one correct
 * order and the others are degenerate rather than useful - punycoding before normalizing, for instance,
 * applies the normalization to ASCII, where it does nothing.
 */
enum class StepPhase { Map, Normalize, Encode }

/**
 * One link in a policy chain: a name, what it contributes, and - for a step - when it runs.
 *
 * A link's [name] is lowercase ASCII and may contain `.` inside itself (`email.byte-stable`, `nfc.u17`);
 * links are joined with `+` to form a policy [Policy.id]. Constructing a link does not create a policy
 * and grants no authority: a chain resolves only if every link in it is one a module actually publishes,
 * which is what stops a caller naming a rule-set nobody wrote.
 *
 * @throws IllegalArgumentException if [name] is not a valid link name, or if the kind and phase
 * disagree - a step must declare a phase and nothing else may.
 */
class PolicyLink(
    val name: String,
    val kind: LinkKind,
    val phase: StepPhase? = null,
) {
    init {
        require(isValidLinkName(name)) {
            "policy link name must be lowercase segments of [a-z0-9-] joined by '.', was: $name"
        }
        require(kind != LinkKind.Step || phase != null) {
            "a Step link must declare the phase at which it runs: $name"
        }
        require(phase == null || kind != LinkKind.Parameter && kind != LinkKind.Relaxation) {
            "only a rule-set or a step runs at a phase, not a qualifier: $name"
        }
    }

    /**
     * The frozen data version this link was built against, if it names one: `u17` for `nfc.u17`,
     * `u15-1` for a link frozen against Unicode 15.1, and `null` for a link that carries no data such
     * as `email.byte-stable` or `lenient`.
     *
     * It is read from the name rather than stored separately, because the name is what a consumer keeps
     * and the two must never disagree about which data produced a value.
     */
    val dataVersion: String?
        get() = name.substringAfterLast('.', "").takeIf { DATA_VERSION.matches(it) }

    override fun toString(): String = name

    override fun equals(other: Any?): Boolean =
        this === other || (other is PolicyLink && name == other.name && kind == other.kind && phase == other.phase)

    override fun hashCode(): Int = (name.hashCode() * 31 + kind.hashCode()) * 31 + (phase?.hashCode() ?: 0)

    companion object {
        /** A data-version segment: `u17`, or `u15-1` when the Unicode minor version is not zero. */
        private val DATA_VERSION = Regex("u[0-9]+(-[0-9]+)?")

        /** One segment of a link name: lowercase alphanumerics, single hyphens between them. */
        private val SEGMENT = Regex("[a-z0-9]+(-[a-z0-9]+)*")

        /**
         * True if [name] is a well-formed link name: one or more [SEGMENT]s joined by `.`, with no
         * empty segment, no uppercase and no `+` - that last one joins links rather than living in one.
         */
        fun isValidLinkName(name: String): Boolean =
            name.isNotEmpty() && name.split('.').all { SEGMENT.matches(it) }

        /**
         * The standard relaxation link, shared by every module that ships a relaxed policy, so that
         * `email.byte-stable+lenient` and `phone.e164+lenient` mean the same thing by construction
         * rather than by coincidence.
         *
         * Declared after [SEGMENT] deliberately: companion properties initialize in declaration order,
         * and constructing a link validates its name against that regex.
         */
        val Lenient: PolicyLink = PolicyLink("lenient", LinkKind.Relaxation)
    }
}
