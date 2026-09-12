package io.github.aughtone.normalize.ubilibet

import io.github.aughtone.normalize.common.InternalNormalizeApi
import io.github.aughtone.normalize.ubilibet.generated.IdnaTables
import io.github.aughtone.normalize.unicode.RangeTable
import io.github.aughtone.normalize.unicode.TextPolicy
import io.github.aughtone.normalize.unicode.UnicodeProperties

/** The flags UTS-46 processing takes. Fixed per policy, never supplied by a caller. */
internal class Uts46Flags(
    val checkHyphens: Boolean,
    val checkBidi: Boolean,
    val checkJoiners: Boolean,
    val useStd3AsciiRules: Boolean,
    val verifyDnsLength: Boolean,
)

/**
 * UTS-46 processing, nontransitional, ending in an A-label.
 *
 * Transitional processing is deliberately absent - it is deprecated upstream, and a flag nobody should
 * use is still a policy identity somebody will publish tokens under. The steps below are the ones the
 * specification numbers: map, normalize to NFC, break into labels, convert and validate each, then
 * encode back to ASCII.
 *
 * Failures throw a typed [DomainNormalizationError], which `normalizeDomain` turns into an `Outcome`.
 * Unlike the specification, which continues processing to show a user as much as possible, this stops
 * at the first error: the output here feeds a hash, and a partially-converted domain is a value that
 * matches nothing while looking plausible.
 */
@OptIn(InternalNormalizeApi::class)
internal object Uts46 {

    private const val PUNYCODE_PREFIX = "xn--"
    private const val MAX_LABEL_OCTETS = 63
    private const val MAX_DOMAIN_OCTETS = 253

    private val mappings: RangeTable by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RangeTable.decode(IdnaTables.idnaMappings)
    }
    private val joiningTypes: RangeTable by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RangeTable.decode(IdnaTables.joiningTypes)
    }
    private val marks: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        IdnaTables.generalCategoryMarks.split(';').filter { it.isNotEmpty() }.map { it.toInt(16) }.toHashSet()
    }

    /** Run the whole pipeline and return the A-label form. */
    fun toAscii(input: String, flags: Uts46Flags): String {
        val mapped = map(input, flags)
        val normalized = TextPolicy.NfcU17.apply(mapped)
        val labels = normalized.split('.')

        val unicodeLabels = labels.map { label -> convert(label) }
        if (flags.checkBidi && unicodeLabels.any { it.isBidiLabel() }) {
            for (label in unicodeLabels) label.checkBidiRule()
        }
        for (label in unicodeLabels) validate(label, flags)

        val asciiLabels = unicodeLabels.map { label -> encode(label) }
        if (flags.verifyDnsLength) verifyDnsLength(asciiLabels)
        return asciiLabels.joinToString(".")
    }

    /** Step 1: replace, remove or refuse each code point by its IDNA status. */
    private fun map(input: String, flags: Uts46Flags): String {
        val out = StringBuilder(input.length)
        for (codePoint in input.toCodePoints()) {
            val status = mappings.valueAt(codePoint) ?: throw DomainNormalizationError.DisallowedCodePoint()
            val mapping = status.substringAfter('>', "")
            when (status.substringBefore('>')) {
                "v", "d" -> out.appendCodePoint(codePoint)
                "i" -> Unit
                "m" -> out.append(mapping.toMappedString())
                "s3v" -> if (flags.useStd3AsciiRules) {
                    throw DomainNormalizationError.DisallowedCodePoint()
                } else {
                    out.appendCodePoint(codePoint)
                }

                "s3m" -> if (flags.useStd3AsciiRules) {
                    throw DomainNormalizationError.DisallowedCodePoint()
                } else {
                    out.append(mapping.toMappedString())
                }

                else -> throw DomainNormalizationError.DisallowedCodePoint()
            }
        }
        return out.toString()
    }

    /** Step 4, first half: a label already in Punycode is decoded before it is validated. */
    private fun convert(label: String): String {
        if (!label.startsWith(PUNYCODE_PREFIX)) return label
        if (label.toCodePoints().any { it > 0x7F }) throw DomainNormalizationError.PunycodeDecodeFailed()
        val decoded = Punycode.decode(label.substring(PUNYCODE_PREFIX.length))
            ?: throw DomainNormalizationError.PunycodeDecodeFailed()
        // An A-label that decodes to nothing, or to something still ASCII, is not an A-label: it is an
        // ordinary label wearing the prefix, and treating it as one would let two spellings mean the
        // same host.
        if (decoded.isEmpty() || decoded.toCodePoints().all { it <= 0x7F }) {
            throw DomainNormalizationError.PunycodeDecodeFailed()
        }
        return decoded
    }

    /** Step 4, second half: the validity criteria of section 4.1, in the order the spec lists them. */
    private fun validate(label: String, flags: Uts46Flags) {
        if (label.isEmpty()) return

        if (TextPolicy.NfcU17.apply(label) != label) throw DomainNormalizationError.NotNormalized()

        if (flags.checkHyphens) {
            if (label.length >= 4 && label[2] == '-' && label[3] == '-') {
                throw DomainNormalizationError.HyphenRule()
            }
            if (label.startsWith('-') || label.endsWith('-')) throw DomainNormalizationError.HyphenRule()
        } else if (label.startsWith(PUNYCODE_PREFIX)) {
            // With hyphen checking relaxed, the converted label may not itself begin with the A-label
            // prefix - including when it arrived as Punycode and decoded to something that does, which
            // would otherwise let a name be wrapped in the prefix twice and mean two different hosts.
            throw DomainNormalizationError.HyphenRule()
        }

        val codePoints = label.toCodePoints()
        if (codePoints.first() in marks) throw DomainNormalizationError.LeadingCombiningMark()

        for (codePoint in codePoints) {
            val status = (mappings.valueAt(codePoint) ?: "x").substringBefore('>')
            if (status != "v" && status != "d") throw DomainNormalizationError.DisallowedCodePoint()
            if (flags.useStd3AsciiRules && codePoint <= 0x7F && !codePoint.isStd3Ascii()) {
                throw DomainNormalizationError.DisallowedCodePoint()
            }
        }

        if (flags.checkJoiners) checkJoiners(codePoints)
    }

    /**
     * ContextJ, from IDNA2008 Appendix A. A zero-width joiner is legitimate after a virama, where it
     * does real orthographic work; a non-joiner is also legitimate between the two halves of a joining
     * sequence. Anywhere else, an invisible character in a hostname is a spoofing tool.
     */
    private fun checkJoiners(codePoints: IntArray) {
        for ((index, codePoint) in codePoints.withIndex()) {
            if (codePoint != ZWNJ && codePoint != ZWJ) continue
            val previous = codePoints.getOrNull(index - 1) ?: throw DomainNormalizationError.JoinerRule()
            if (UnicodeProperties.isVirama(previous)) continue
            if (codePoint == ZWJ) throw DomainNormalizationError.JoinerRule()
            if (!hasJoiningContext(codePoints, index)) throw DomainNormalizationError.JoinerRule()
        }
    }

    /** `(Joining_Type:{L,D})(Joining_Type:T)* ZWNJ (Joining_Type:T)*(Joining_Type:{R,D})`. */
    private fun hasJoiningContext(codePoints: IntArray, position: Int): Boolean {
        var before = position - 1
        while (before >= 0 && joiningType(codePoints[before]) == "T") before--
        if (before < 0 || joiningType(codePoints[before]) !in setOf("L", "D")) return false

        var after = position + 1
        while (after < codePoints.size && joiningType(codePoints[after]) == "T") after++
        return after < codePoints.size && joiningType(codePoints[after]) in setOf("R", "D")
    }

    private fun joiningType(codePoint: Int): String = joiningTypes.valueAt(codePoint) ?: "U"

    /** A label is right-to-left when it contains an R, AL or AN character. */
    private fun String.isBidiLabel(): Boolean =
        toCodePoints().any { UnicodeProperties.bidiClass(it) in RTL_CLASSES }

    /**
     * The bidi rule, RFC 5893 section 2: all six conditions, applied to every label once any label in
     * the domain is right-to-left. The rule exists so a name cannot be displayed in one order and
     * resolved in another.
     */
    private fun String.checkBidiRule() {
        if (isEmpty()) return
        val classes = toCodePoints().map { UnicodeProperties.bidiClass(it) }

        val first = classes.first()
        val rightToLeft = when (first) {
            "R", "AL" -> true
            "L" -> false
            else -> throw DomainNormalizationError.BidiRule()
        }

        val allowed = if (rightToLeft) RTL_ALLOWED else LTR_ALLOWED
        if (classes.any { it !in allowed }) throw DomainNormalizationError.BidiRule()

        val last = classes.last { it != "NSM" }
        val allowedEnding = if (rightToLeft) RTL_ENDINGS else LTR_ENDINGS
        if (last !in allowedEnding) throw DomainNormalizationError.BidiRule()

        if (rightToLeft && classes.contains("EN") && classes.contains("AN")) {
            throw DomainNormalizationError.BidiRule()
        }
    }

    /** Step 4.2: back to ASCII, which is what a token and every DNS resolver can carry. */
    private fun encode(label: String): String {
        if (label.toCodePoints().all { it <= 0x7F }) return label
        val encoded = Punycode.encode(label) ?: throw DomainNormalizationError.PunycodeEncodeFailed()
        return PUNYCODE_PREFIX + encoded
    }

    private fun verifyDnsLength(labels: List<String>) {
        // The name length excludes the root label and its dot, exactly as the specification says. The
        // per-label rule does not: an empty root label is still an empty label, which is why a name
        // written with a trailing dot is refused under this check rather than quietly trimmed.
        val withoutRoot = if (labels.size > 1 && labels.last().isEmpty()) labels.dropLast(1) else labels
        val total = withoutRoot.sumOf { it.length } + (withoutRoot.size - 1)
        if (total < 1 || total > MAX_DOMAIN_OCTETS) throw DomainNormalizationError.NameTooLong()
        for (label in labels) {
            if (label.isEmpty()) throw DomainNormalizationError.EmptyLabel()
            if (label.length > MAX_LABEL_OCTETS) throw DomainNormalizationError.LabelTooLong()
        }
    }

    private fun Int.isStd3Ascii(): Boolean =
        this in 0x61..0x7A || this in 0x30..0x39 || this == 0x2D

    private fun String.toMappedString(): String =
        split(' ').filter { it.isNotEmpty() }.map { it.toInt(16) }.toCodePointString()

    private fun StringBuilder.appendCodePoint(codePoint: Int) {
        append(listOf(codePoint).toCodePointString())
    }

    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D
    private val RTL_CLASSES = setOf("R", "AL", "AN")
    private val RTL_ALLOWED = setOf("R", "AL", "AN", "EN", "ES", "CS", "ET", "ON", "BN", "NSM")
    private val LTR_ALLOWED = setOf("L", "EN", "ES", "CS", "ET", "ON", "BN", "NSM")
    private val RTL_ENDINGS = setOf("R", "AL", "EN", "AN")
    private val LTR_ENDINGS = setOf("L", "EN")
}
