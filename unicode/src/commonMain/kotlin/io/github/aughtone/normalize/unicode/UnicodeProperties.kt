package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.common.InternalNormalizeApi
import io.github.aughtone.normalize.unicode.generated.CharacterProperties

/**
 * Frozen character properties that more than one module needs.
 *
 * They live here rather than in whichever module used them first, because a property carried twice is a
 * property that can disagree with itself - `:ubilibet` needs bidi classes for the RFC 5893 bidi rule and
 * combining classes for the joiner rules, and `:confusables` will need the same bidi data. See ADR-0003.
 *
 * A caller that only normalizes text never touches this, so dead-code elimination drops the table on JS,
 * wasm and native, and on JVM the class is simply never loaded.
 */
@InternalNormalizeApi
object UnicodeProperties {

    /** The Unicode release these properties were frozen from. */
    const val UNICODE_VERSION: String = CharacterProperties.UNICODE_VERSION

    /** The canonical combining class of [codePoint]; zero for a starter. */
    fun combiningClass(codePoint: Int): Int = UnicodeTables.combiningClass(codePoint)

    /**
     * True if [codePoint] is a virama - combining class 9.
     *
     * The joiner rules in UTS-46 turn on this: a zero-width joiner is only legitimate directly after a
     * virama, where it does real orthographic work rather than hiding a spoofed label.
     */
    fun isVirama(codePoint: Int): Boolean = combiningClass(codePoint) == VIRAMA_CLASS

    /**
     * The bidi class of [codePoint] - `L`, `R`, `AL`, `AN`, `EN`, `NSM` and the rest - or `L` for a code
     * point the table does not cover.
     *
     * Unassigned code points fall back to `L` because UTS-46 disallows them outright: a label containing
     * one is refused before the bidi rule ever runs, so the fallback is unreachable in practice and
     * exists only so this function is total.
     */
    fun bidiClass(codePoint: Int): String = bidiClasses.valueAt(codePoint) ?: "L"

    /**
     * The bracket this code point pairs with, and whether it opens or closes, or `null` if it is not a
     * paired bracket. Used by rule N0 of the bidirectional algorithm.
     *
     * U+2329 and U+3008 are canonically equivalent, as are their closing partners, and the standard
     * says no further such pairs will be added; a caller matching brackets has to treat them as equal.
     */
    fun pairedBracket(codePoint: Int): PairedBracket? {
        val encoded = brackets.valueAt(codePoint) ?: return null
        val parts = encoded.split(' ')
        return PairedBracket(opening = parts[0] == "o", paired = parts[1].toInt(16))
    }

    /** The glyph this code point is drawn as when it resolves right-to-left, or `null` if it is not mirrored. */
    fun mirrorGlyph(codePoint: Int): Int? = mirroring.valueAt(codePoint)?.toInt(16)

    private const val VIRAMA_CLASS = 9

    private val bidiClasses: RangeTable by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RangeTable.decode(CharacterProperties.bidiClasses)
    }

    private val brackets: RangeTable by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RangeTable.decode(CharacterProperties.bidiBrackets)
    }

    private val mirroring: RangeTable by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RangeTable.decode(CharacterProperties.bidiMirroring)
    }
}

/** A paired bracket: which direction it faces, and the code point it pairs with. */
@InternalNormalizeApi
class PairedBracket(val opening: Boolean, val paired: Int)

/**
 * A property table keyed by code point range, decoded once from its generated form.
 *
 * Ranges rather than one entry per character: these tables cover the whole code space, and expanding
 * them would turn thousands of entries into a million that say the same thing. Lookup is a binary
 * search over the range starts.
 */
@InternalNormalizeApi
class RangeTable private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    private val values: Array<String>,
) {

    /** The value covering [codePoint], or `null` if no range does. */
    fun valueAt(codePoint: Int): String? {
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                codePoint < starts[middle] -> high = middle - 1
                codePoint > ends[middle] -> low = middle + 1
                else -> return values[middle]
            }
        }
        return null
    }

    companion object {
        /** Decode `first-last:value` entries joined by `;`, as the generator emits them. */
        fun decode(encoded: String): RangeTable {
            val records = encoded.split(';').filter { it.isNotEmpty() }
            val starts = IntArray(records.size)
            val ends = IntArray(records.size)
            val values = Array(records.size) { "" }
            for ((index, record) in records.withIndex()) {
                val split = record.indexOf(':')
                val key = record.substring(0, split)
                val dash = key.indexOf('-')
                starts[index] = if (dash < 0) key.toInt(16) else key.substring(0, dash).toInt(16)
                ends[index] = if (dash < 0) starts[index] else key.substring(dash + 1).toInt(16)
                values[index] = record.substring(split + 1)
            }
            return RangeTable(starts, ends, values)
        }
    }
}
