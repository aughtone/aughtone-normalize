package io.github.aughtone.normalize.unicode

/**
 * The four Unicode normalization forms, implemented against the frozen tables.
 *
 * The algorithms are UAX #15: decompose recursively, put combining marks into canonical order, and -
 * for the composed forms - recompose. Hangul is handled arithmetically rather than from a table,
 * because the standard defines it that way and 11,172 table entries would say nothing a formula does
 * not.
 *
 * All of it is `internal`: callers go through a named policy, so no output is ever produced under rules
 * nobody chose.
 */
internal object Normalization {

    // Hangul, from UAX #15. Syllables compose and decompose by arithmetic.
    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT

    /** Above every real combining class, used to mark "nothing can compose here". */
    private const val BLOCKED = 256

    /** Decompose [value], canonically or compatibly, then put combining marks in canonical order. */
    fun decompose(value: String, compatibility: Boolean): String {
        val out = ArrayList<Int>(value.length + 8)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAtIndex(index)
            index += codePoint.charCount()
            decomposeInto(codePoint, compatibility, out)
        }
        reorder(out)
        return out.toStringFromCodePoints()
    }

    /** Decompose, then compose: NFC when [compatibility] is false, NFKC when it is true. */
    fun compose(value: String, compatibility: Boolean): String {
        val decomposed = ArrayList<Int>(value.length + 8)
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAtIndex(index)
            index += codePoint.charCount()
            decomposeInto(codePoint, compatibility, decomposed)
        }
        reorder(decomposed)
        return composeInPlace(decomposed).toStringFromCodePoints()
    }

    /** Append the full decomposition of [codePoint] to [out], recursively. */
    private fun decomposeInto(codePoint: Int, compatibility: Boolean, out: MutableList<Int>) {
        val hangulIndex = codePoint - S_BASE
        if (hangulIndex in 0 until S_COUNT) {
            out += L_BASE + hangulIndex / N_COUNT
            out += V_BASE + (hangulIndex % N_COUNT) / T_COUNT
            val trailing = hangulIndex % T_COUNT
            if (trailing != 0) out += T_BASE + trailing
            return
        }

        val mapping = UnicodeTables.canonical[codePoint]
            ?: if (compatibility) UnicodeTables.compatibility[codePoint] else null
        if (mapping == null) {
            out += codePoint
            return
        }
        for (mapped in mapping) decomposeInto(mapped, compatibility, out)
    }

    /**
     * Canonical ordering: sort each run of non-starters by combining class, without disturbing the
     * order of characters that share one. A stable insertion sort, because the runs are short and
     * stability is the requirement rather than an optimization.
     */
    private fun reorder(codePoints: MutableList<Int>) {
        for (index in 1 until codePoints.size) {
            val current = codePoints[index]
            val currentClass = UnicodeTables.combiningClass(current)
            if (currentClass == 0) continue
            var position = index
            while (position > 0) {
                val previousClass = UnicodeTables.combiningClass(codePoints[position - 1])
                if (previousClass <= currentClass) break
                codePoints[position] = codePoints[position - 1]
                position--
            }
            codePoints[position] = current
        }
    }

    /**
     * The canonical composition algorithm: walk the decomposed text, and for each starter try to
     * compose the characters after it, skipping anything blocked by an intervening mark of equal or
     * higher combining class.
     */
    private fun composeInPlace(codePoints: MutableList<Int>): List<Int> {
        if (codePoints.isEmpty()) return codePoints

        val out = ArrayList<Int>(codePoints.size)
        var starterPosition = 0
        var starter = codePoints[0]
        // A text starting with a combining mark has no starter to compose onto; 256 is above every
        // combining class, so nothing composes until a real starter appears.
        var lastClass = UnicodeTables.combiningClass(starter).let { if (it != 0) BLOCKED else 0 }
        out += starter

        for (index in 1 until codePoints.size) {
            val codePoint = codePoints[index]
            val currentClass = UnicodeTables.combiningClass(codePoint)
            val composite = composePair(starter, codePoint)
            // Composable unless blocked: something between the starter and this character has a
            // combining class at least as high, or is itself a starter.
            if (composite != null && (lastClass < currentClass || lastClass == 0)) {
                out[starterPosition] = composite
                starter = composite
            } else {
                if (currentClass == 0) {
                    starterPosition = out.size
                    starter = codePoint
                }
                lastClass = currentClass
                out += codePoint
            }
        }
        return out
    }

    /** Compose one pair, by the Hangul formula or the inverted canonical table. */
    private fun composePair(first: Int, second: Int): Int? {
        val leading = first - L_BASE
        if (leading in 0 until L_COUNT) {
            val vowel = second - V_BASE
            if (vowel in 0 until V_COUNT) return S_BASE + (leading * V_COUNT + vowel) * T_COUNT
        }
        val syllable = first - S_BASE
        if (syllable in 0 until S_COUNT && syllable % T_COUNT == 0) {
            val trailing = second - T_BASE
            if (trailing in 1 until T_COUNT) return first + trailing
        }
        return UnicodeTables.compositions[UnicodeTables.pack(first, second)]
    }
}

/** The code point at [index], reading a surrogate pair as one character. */
internal fun String.codePointAtIndex(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
    }
    return high.code
}

/** How many UTF-16 units this code point occupies. */
internal fun Int.charCount(): Int = if (this >= 0x10000) 2 else 1

/** Build a string from code points, encoding supplementary characters as surrogate pairs. */
internal fun List<Int>.toStringFromCodePoints(): String {
    val builder = StringBuilder(size + 8)
    for (codePoint in this) {
        if (codePoint >= 0x10000) {
            val offset = codePoint - 0x10000
            builder.append((0xD800 + (offset shr 10)).toChar())
            builder.append((0xDC00 + (offset and 0x3FF)).toChar())
        } else {
            builder.append(codePoint.toChar())
        }
    }
    return builder.toString()
}
