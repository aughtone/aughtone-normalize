package io.github.aughtone.normalize.confusables

import io.github.aughtone.normalize.common.InternalNormalizeApi
import io.github.aughtone.normalize.confusables.generated.ConfusableTables
import io.github.aughtone.normalize.unicode.RangeTable
import io.github.aughtone.normalize.unicode.TextPolicy
import io.github.aughtone.normalize.unicode.UnicodeProperties

/**
 * The UTS-39 skeleton: what two strings reduce to when the question is "do these look the same?".
 *
 * `skeleton(X)` is defined as `bidiSkeleton(LTR, X)`, so the text is first laid out the way a reader
 * would see it - reordered by the bidirectional algorithm, combining marks moved after their bases,
 * mirrored glyphs substituted - and only then reduced by the confusable mappings. Doing it in that order
 * matters: the whole point is to compare what is displayed, and display order is not storage order.
 */
@OptIn(InternalNormalizeApi::class)
internal object Skeleton {

    private val confusables: Map<Int, IntArray> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val result = HashMap<Int, IntArray>(8192)
        for (entry in ConfusableTables.confusableMappings.split(';')) {
            if (entry.isEmpty()) continue
            val split = entry.indexOf('>')
            val prototype = entry.substring(split + 1).split(' ')
            result[entry.substring(0, split).toInt(16)] = IntArray(prototype.size) { prototype[it].toInt(16) }
        }
        result
    }

    private val defaultIgnorable: RangeTable by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RangeTable.decode(ConfusableTables.defaultIgnorable)
    }

    /** `bidiSkeleton(LTR, X)`, which UTS-39 defines `skeleton(X)` to be. */
    fun of(value: String): String {
        val codePoints = value.toCodePoints()
        if (codePoints.isEmpty()) return ""

        val bidi = Bidi(codePoints, LEFT_TO_RIGHT)
        val levels = bidi.resolveLevels()
        val order = bidi.visualOrder()

        val display = IntArray(order.size) { codePoints[order[it]] }
        val displayLevels = IntArray(order.size) { levels[order[it]] }

        moveMarksAfterTheirBase(display, displayLevels)
        mirrorRightToLeftGlyphs(display, displayLevels)
        return internalSkeleton(display.toList().toCodePointString())
    }

    /**
     * Rule L3: a combining mark applied to a right-to-left base ends up before it after reordering, so
     * each cluster is turned back around. Without this, a mark and its base compare as two different
     * orders and two visually identical strings would not match.
     */
    private fun moveMarksAfterTheirBase(display: IntArray, levels: IntArray) {
        var index = 0
        while (index < display.size) {
            if (levels[index] % 2 == 0 || !display[index].isCombiningMark()) {
                index++
                continue
            }
            var end = index
            while (end < display.size && levels[end] % 2 == 1 && display[end].isCombiningMark()) end++
            // The cluster is the run of marks plus the base that follows it, inside the same run.
            if (end < display.size && levels[end] % 2 == 1) {
                display.reverseRange(index, end + 1)
                levels.reverseRange(index, end + 1)
                index = end + 1
            } else {
                index = end
            }
        }
    }

    /** Rule L4: a mirrored character is drawn as its pair when it resolves right-to-left. */
    private fun mirrorRightToLeftGlyphs(display: IntArray, levels: IntArray) {
        for (index in display.indices) {
            if (levels[index] % 2 == 0) continue
            val mirrored = UnicodeProperties.mirrorGlyph(display[index]) ?: continue
            display[index] = mirrored
        }
    }

    /**
     * `internalSkeleton`: NFD, drop the characters that are not meant to be seen, replace each remaining
     * character by its confusable prototype, then NFD again.
     *
     * The second normalization is not redundant - a prototype may itself be a composed character, and
     * without it two inputs that should reduce to the same skeleton can end up spelled differently.
     */
    private fun internalSkeleton(value: String): String {
        val decomposed = TextPolicy.NfdU17.apply(value).toCodePoints()
        val mapped = ArrayList<Int>(decomposed.size + 8)
        for (codePoint in decomposed) {
            if (defaultIgnorable.valueAt(codePoint) != null) continue
            val prototype = confusables[codePoint]
            if (prototype == null) mapped += codePoint else for (replacement in prototype) mapped += replacement
        }
        return TextPolicy.NfdU17.apply(mapped.toCodePointString())
    }

    private fun Int.isCombiningMark(): Boolean = UnicodeProperties.combiningClass(this) != 0

    private fun IntArray.reverseRange(from: Int, to: Int) {
        var start = from
        var end = to - 1
        while (start < end) {
            val swap = this[start]
            this[start] = this[end]
            this[end] = swap
            start++
            end--
        }
    }

    /** The direction `skeleton` is defined at: LTR, with the paragraph level forced to zero by HL1. */
    private const val LEFT_TO_RIGHT = 0
}

/** Split a string into code points, reading surrogate pairs as one character. */
internal fun String.toCodePoints(): IntArray {
    val result = ArrayList<Int>(length)
    var index = 0
    while (index < length) {
        val high = this[index]
        if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
            result += 0x10000 + ((high.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00)
            index += 2
        } else {
            result += high.code
            index += 1
        }
    }
    return result.toIntArray()
}

/** Build a string from code points, encoding supplementary characters as surrogate pairs. */
internal fun List<Int>.toCodePointString(): String {
    val builder = StringBuilder(size)
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
