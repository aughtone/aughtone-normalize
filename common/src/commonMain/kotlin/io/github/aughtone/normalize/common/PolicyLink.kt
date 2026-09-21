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
     * A rule-set, and the first link in every chain: `email`, `nfc.u17`.
     *
     * A base that another module can also apply as a step declares the [StepPhase] at which it runs -
     * NFC is a policy in its own right and a step inside an email chain, and it is one thing either
     * way. Without a phase, a base can only open a chain.
     */
    Base,

    /**
     * A comparable form a caller opted into, `form.ipv4.address`. Form links come after every other link
     * in a chain, in form-name order - see [ComparableForm].
     */
    Form,

    /** Side input the base needs, named in the identity so it travels with derived values: `region.ca`. */
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
 *
 * @property rank The phase's position in a chain; a later link may not have a lower rank than an earlier
 * one. **These values are frozen**: they decide which chains are valid, so changing one would make a
 * stored policy id stop parsing, or start parsing as a different chain. A new phase takes a new rank and
 * never renumbers the existing ones. The order is explicit rather than taken from declaration order, so
 * reordering the members cannot silently change it.
 */
enum class StepPhase(val rank: Int) {
    Map(0),
    Normalize(1),
    Encode(2),
}

/**
 * One link in a policy chain: a name, what it contributes, and - for a step - when it runs.
 *
 * A link's [name] is lowercase ASCII and may contain `.` inside itself (`space.collapsed`, `nfc.u17`);
 * links are joined with `:` to form a policy [Policy.id]. Constructing a link does not create a policy
 * and grants no authority: a chain resolves only if every link in it is one a module actually publishes,
 * which is what stops a caller naming a rule-set nobody wrote.
 *
 * @throws IllegalArgumentException if [name] is not a valid link name, or if the kind and phase
 * disagree - a step must declare a phase and nothing else may.
 */
class PolicyLink(
    /** The link as it appears in an id: `phone.e164`, `region.ca`, `space.trimmed`, `lenient`. */
    val name: String,

    /** What this link is, which fixes where it may sit in a chain. */
    val kind: LinkKind,

    /** For a step, when it runs relative to other steps; `null` for anything that is not a step. */
    val phase: StepPhase? = null,
) {
    init {
        require(isValidLinkName(name)) {
            "policy link name must be lowercase segments of [a-z0-9-] joined by '.', was: $name"
        }
        require(kind != LinkKind.Step || phase != null) {
            "a Step link must declare the phase at which it runs: $name"
        }
        require(phase == null || kind != LinkKind.Parameter && kind != LinkKind.Relaxation && kind != LinkKind.Form) {
            "only a rule-set or a step runs at a phase, not a qualifier: $name"
        }
        require((kind == LinkKind.Form) == name.startsWith(ComparableForm.FORM_PREFIX)) {
            "a Form link, and only a Form link, is named form.<form name>: $name"
        }
    }

    /**
     * The frozen data version this link was built against, if it names one: `u17` for `nfc.u17`,
     * `u15.1` for a link frozen against Unicode 15.1, and `null` for a link that carries no data such
     * as `email` or `lenient`.
     *
     * It is read from the name rather than stored separately, because the name is what a consumer keeps
     * and the two must never disagree about which data produced a value.
     */
    val dataVersion: String?
        get() {
            val segments = name.split('.')
            val last = segments.last()
            if (DATA_VERSION.matches(last)) return last
            // A minor release is two segments, `u15` then `1`, because a name carries no hyphens.
            val previous = segments.getOrNull(segments.size - 2) ?: return null
            return if (MINOR.matches(last) && DATA_VERSION.matches(previous)) "$previous.$last" else null
        }

    override fun toString(): String = name

    override fun equals(other: Any?): Boolean =
        this === other || (other is PolicyLink && name == other.name && kind == other.kind && phase == other.phase)

    override fun hashCode(): Int = (name.hashCode() * 31 + kind.hashCode()) * 31 + (phase?.hashCode() ?: 0)

    companion object {
        /** A data-version segment: `u17`, with a second segment for a non-zero minor - `u15.1`. */
        private val DATA_VERSION = Regex("u[0-9]+")

        /** The minor half of a data version, as its own segment. */
        private val MINOR = Regex("[0-9]+")

        /** One segment of a link name: lowercase alphanumerics. No hyphens - see [PolicyId]. */
        private val SEGMENT = Regex("[a-z0-9]+")

        /**
         * True if [name] is a well-formed link name: one or more [SEGMENT]s joined by `.`, with no
         * empty segment, no uppercase, no hyphen, and no `:` - that last one joins links rather than
         * living in one.
         */
        fun isValidLinkName(name: String): Boolean =
            name.isNotEmpty() && name.split('.').all { SEGMENT.matches(it) }

        /**
         * The standard relaxation link, shared by every module that ships a relaxed policy, so that
         * `pan.digits:lenient` and `phone.e164:lenient` mean the same thing by construction
         * rather than by coincidence.
         *
         * Declared after [SEGMENT] deliberately: companion properties initialize in declaration order,
         * and constructing a link validates its name against that regex.
         */
        val Lenient: PolicyLink = PolicyLink("lenient", LinkKind.Relaxation)
    }
}
