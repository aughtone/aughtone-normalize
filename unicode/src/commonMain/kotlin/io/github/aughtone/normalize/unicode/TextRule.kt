package io.github.aughtone.normalize.unicode

/**
 * One text rule, and where it runs.
 *
 * @property rank The rule's place in the fixed application order. **Frozen**: two callers enabling the
 * same rules must produce the same bytes whatever order they wrote them in, and a stored id lists its
 * rules in this order. Rules sharing a rank are alternatives - two of them in one policy contradict.
 * @property link The rule's name in a policy id.
 * @property unicodeOnly True for a rule that has no ASCII meaning and needs a Unicode release.
 * @property characterSet True for a rule whose behaviour depends on the character set it runs in;
 * false for one, like `empty.refused`, that means the same thing in either.
 */
internal enum class TextRule(
    val rank: Int,
    val link: String,
    val unicodeOnly: Boolean,
    val characterSet: Boolean,
) {
    StripControl(0, "control.removed", unicodeOnly = false, characterSet = true),
    Trim(1, "space.trimmed", unicodeOnly = false, characterSet = true),
    CollapseSpace(2, "space.collapsed", unicodeOnly = false, characterSet = true),
    RemoveSpace(2, "space.removed", unicodeOnly = false, characterSet = true),
    Lowercase(3, "case.lower", unicodeOnly = false, characterSet = true),
    Uppercase(3, "case.upper", unicodeOnly = false, characterSet = true),
    Casefold(3, "case.folded", unicodeOnly = true, characterSet = true),
    Nfc(4, "nfc", unicodeOnly = true, characterSet = true),
    Nfd(4, "nfd", unicodeOnly = true, characterSet = true),
    Nfkc(4, "nfkc", unicodeOnly = true, characterSet = true),
    Nfkd(4, "nfkd", unicodeOnly = true, characterSet = true),
    RemoveMarks(5, "mark.removed", unicodeOnly = true, characterSet = true),
    KeepAlnum(6, "alnum.only", unicodeOnly = false, characterSet = true),
    KeepAlnumSpaced(6, "alnum.only.spaced", unicodeOnly = false, characterSet = true),
    NonEmpty(7, "empty.refused", unicodeOnly = false, characterSet = false),
    ;

    companion object {
        /** The rule an id link names, ignoring any `.ascii` marker, or `null` if none does. */
        fun ofLink(link: String): TextRule? = entries.firstOrNull { it.link == link }
    }
}

/**
 * A rule as configured in a policy: the rule, and whether it runs over ASCII only.
 *
 * [ascii] is meaningless for a rule with no character set, and is always false for one.
 */
internal data class ConfiguredRule(val rule: TextRule, val ascii: Boolean) {

    /**
     * Apply this rule to [value]. ASCII rules touch only ASCII code points; Unicode rules read the frozen
     * tables in [TextData].
     */
    fun apply(value: String): String = when (rule) {
        TextRule.StripControl -> filter(value) { !(isControl(it) && !isSpace(it)) }
        TextRule.Trim -> trim(value)
        TextRule.CollapseSpace -> collapse(value)
        TextRule.RemoveSpace -> filter(value) { !isSpace(it) }
        TextRule.Lowercase -> map(value) { if (ascii) asciiLower(it) else TextData.lowercase(it) }
        TextRule.Uppercase -> map(value) { if (ascii) asciiUpper(it) else TextData.uppercase(it) }
        TextRule.Casefold -> map(value) { TextData.caseFold(it) }
        TextRule.Nfc -> Normalization.compose(value, compatibility = false)
        TextRule.Nfd -> Normalization.decompose(value, compatibility = false)
        TextRule.Nfkc -> Normalization.compose(value, compatibility = true)
        TextRule.Nfkd -> Normalization.decompose(value, compatibility = true)
        TextRule.RemoveMarks -> filter(value) { !TextData.isMark(it) }
        TextRule.KeepAlnum -> keepAlnum(value, spaceTheRest = false)
        TextRule.KeepAlnumSpaced -> keepAlnum(value, spaceTheRest = true)
        TextRule.NonEmpty -> value.also { if (it.isEmpty()) throw TextNormalizationError.Empty() }
    }

    /**
     * Keep the letters, decimal digits and combining marks; drop everything else, or stand a single
     * space where a run of it was.
     *
     * The run collapsing is part of this rule rather than left to `space.collapsed`, which has already
     * run by now: substituting per character would turn `Hello, World!!!` into `hello  world   ` with
     * nothing left to tidy it. A leading or trailing run therefore produces nothing at all, which is
     * the same reason - the trim rule is behind us too.
     *
     * Some code points are **deleted even by the spacing variant**, and never stand for a run, because
     * substituting a space for them would invent a word boundary nobody typed. Over Unicode those are
     * the format characters and the unassigned code points: a zero-width joiner sits inside a word by
     * design, and a noncharacter is not a separator, it is nothing. Over ASCII they are every non-ASCII
     * code point, for the same reason one step further out - `ø` in `Jørn` is a letter in the middle of
     * a word, and an ASCII rule that spaced it would break the word in two. An ASCII rule reads no
     * frozen table, so it cannot tell a non-ASCII separator from a non-ASCII letter; deleting is the
     * choice that invents nothing, and a caller who needs `\u00A0` to read as a boundary wants the
     * Unicode variant.
     */
    private fun keepAlnum(value: String, spaceTheRest: Boolean): String {
        val out = ArrayList<Int>(value.length)
        var pendingSpace = false
        for (codePoint in value.toCodePointList()) {
            if (isKept(codePoint)) {
                if (pendingSpace && out.isNotEmpty()) out += SPACE
                pendingSpace = false
                out += codePoint
                continue
            }
            if (!spaceTheRest) continue
            if (if (ascii) codePoint > ASCII_MAX else TextData.isFormatOrUnassigned(codePoint)) continue
            pendingSpace = true
        }
        return out.toStringFromCodePoints()
    }

    /**
     * What the keep-set rules keep: `A`-`Z`, `a`-`z` and `0`-`9`, or General_Category `L*`, `Nd` and `M*`.
     *
     * **The ASCII variant keeps ASCII and drops everything else, including every non-ASCII letter.**
     * `.ascii` means the rule's character set is ASCII, as it does on every other rule - but a rule that
     * *keeps* its set and one that *removes* its set behave very differently when it is narrowed, and
     * this is the one where it matters: `Jørn` becomes `Jrn`, not `Jorn`. `ø` has no canonical
     * decomposition, so there is no base letter to fold it to and nothing for `mark.removed` to strip.
     * Dropping it is what an ASCII keep-set does; mapping it to `o` would be transliteration, which is
     * editorial, language-specific and not something a frozen policy can promise. Keep the Unicode
     * variant unless every caller of the result is genuinely ASCII-only.
     *
     * Case is not this rule's business: `A`-`Z` survives it, and a policy that wants one case enables a
     * case rule, which runs first.
     */
    private fun isKept(codePoint: Int): Boolean =
        if (ascii) {
            codePoint in 'a'.code..'z'.code || codePoint in 'A'.code..'Z'.code || codePoint in '0'.code..'9'.code
        } else {
            TextData.isLetterDigitOrMark(codePoint)
        }

    /** The six ASCII whitespace characters, or the White_Space property. */
    private fun isSpace(codePoint: Int): Boolean =
        if (ascii) codePoint == 0x20 || codePoint in 0x09..0x0D else TextData.isWhiteSpace(codePoint)

    /** C0 controls and DEL, or General_Category Cc. */
    private fun isControl(codePoint: Int): Boolean =
        if (ascii) codePoint < 0x20 || codePoint == 0x7F else TextData.isControl(codePoint)

    private fun trim(value: String): String {
        val codePoints = value.toCodePointList()
        var start = 0
        var end = codePoints.size
        while (start < end && isSpace(codePoints[start])) start++
        while (end > start && isSpace(codePoints[end - 1])) end--
        return codePoints.subList(start, end).toStringFromCodePoints()
    }

    private fun collapse(value: String): String {
        val out = ArrayList<Int>(value.length)
        var inRun = false
        for (codePoint in value.toCodePointList()) {
            if (isSpace(codePoint)) {
                if (!inRun) out += SPACE
                inRun = true
            } else {
                out += codePoint
                inRun = false
            }
        }
        return out.toStringFromCodePoints()
    }

    private inline fun filter(value: String, keep: (Int) -> Boolean): String =
        value.toCodePointList().filter(keep).toStringFromCodePoints()

    private inline fun map(value: String, mapping: (Int) -> IntArray?): String {
        val out = ArrayList<Int>(value.length)
        for (codePoint in value.toCodePointList()) {
            val mapped = mapping(codePoint)
            if (mapped == null) out += codePoint else for (each in mapped) out += each
        }
        return out.toStringFromCodePoints()
    }

    private fun asciiLower(codePoint: Int): IntArray? =
        if (codePoint in 'A'.code..'Z'.code) intArrayOf(codePoint + CASE_OFFSET) else null

    private fun asciiUpper(codePoint: Int): IntArray? =
        if (codePoint in 'a'.code..'z'.code) intArrayOf(codePoint - CASE_OFFSET) else null

    private companion object {
        const val SPACE = 0x20
        const val CASE_OFFSET = 0x20
        const val ASCII_MAX = 0x7F
    }
}

/** The string's code points, in order. Surrogates are already known to be paired when this runs. */
internal fun String.toCodePointList(): List<Int> {
    val out = ArrayList<Int>(length)
    var index = 0
    while (index < length) {
        val codePoint = codePointAtIndex(index)
        out += codePoint
        index += codePoint.charCount()
    }
    return out
}
