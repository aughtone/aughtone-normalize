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
    NonEmpty(5, "empty.refused", unicodeOnly = false, characterSet = false),
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
        TextRule.NonEmpty -> value.also { if (it.isEmpty()) throw TextNormalizationError.Empty() }
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
