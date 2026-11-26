package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.common.InternalNormalizeApi
import io.github.aughtone.normalize.unicode.generated.TextTables

/**
 * The frozen Unicode data behind the text rules, decoded lazily from [TextTables].
 *
 * Nothing here consults the platform: `Char.isWhitespace()`, `lowercase()` and friends follow the
 * runtime's Unicode version, which is exactly the drift a stored token cannot survive. A chain whose rules
 * are all ASCII never touches this object, so its tables are never decoded.
 *
 * Only Unicode 17 exists today. When a second release is carried, this becomes one instance per
 * [UnicodeRelease] rather than a single object.
 */
@OptIn(InternalNormalizeApi::class)
internal object TextData {

    /** The Unicode release these tables were frozen from. */
    const val VERSION: String = TextTables.UNICODE_VERSION

    private val whiteSpace: RangeTable by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RangeTable.decode(TextTables.whiteSpace)
    }

    private val controls: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        TextTables.controls.split(';').filter { it.isNotEmpty() }.mapTo(HashSet()) { it.toInt(16) }
    }

    private val caseFolding: Map<Int, IntArray> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        decodeMappings(TextTables.caseFolding)
    }

    private val simpleLowercase: Map<Int, IntArray> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        decodeMappings(TextTables.simpleLowercase)
    }

    private val simpleUppercase: Map<Int, IntArray> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        decodeMappings(TextTables.simpleUppercase)
    }

    /**
     * General_Category for every assigned code point, as ranges carrying the two-letter value as the
     * UCD spells it - `Lu`, `Nd`, `Cf`.
     *
     * Ranges rather than a code point per entry, because `UnicodeData.txt` declares the CJK, Hangul and
     * Tangut blocks as `First>`/`Last>` pairs and the letters inside them are 119,331 code points. A
     * table that expanded them could not ship; one that skipped them would report every
     * supplementary-plane ideograph as a non-letter, which is the defect
     * aughtone/aughtone-normalize#44 fixed.
     */
    private val categories: RangeTable by lazy(LazyThreadSafetyMode.PUBLICATION) {
        RangeTable.decode(TextTables.generalCategories)
    }

    /** True if [codePoint] has the White_Space property. */
    fun isWhiteSpace(codePoint: Int): Boolean = whiteSpace.valueAt(codePoint) != null

    /** True if [codePoint] is a control character: General_Category Cc. */
    fun isControl(codePoint: Int): Boolean = codePoint in controls

    /** The full case folding of [codePoint], or `null` if it folds to itself. */
    fun caseFold(codePoint: Int): IntArray? = caseFolding[codePoint]

    /** The simple lowercase mapping of [codePoint], or `null` if it has none. */
    fun lowercase(codePoint: Int): IntArray? = simpleLowercase[codePoint]

    /** The simple uppercase mapping of [codePoint], or `null` if it has none. */
    fun uppercase(codePoint: Int): IntArray? = simpleUppercase[codePoint]

    /**
     * True if [codePoint] is a letter, a decimal digit or a combining mark: General_Category `L*`, `Nd`
     * or `M*`.
     *
     * The three together are what survives a keep-set rule. Marks ride along with letters rather than
     * being a separate choice, because a mark belongs to the base letter in front of it: dropping it
     * while keeping the letter would silently rewrite the word, and a policy that wants the diacritic
     * gone says so with `mark.removed`.
     *
     * Deliberately not public, and no public General_Category surface exists. A property lookup would
     * leave every caller assembling the same canonical form slightly differently, which is the whole
     * problem the rules remove - see aughtone/aughtone-normalize#43.
     */
    fun isLetterDigitOrMark(codePoint: Int): Boolean {
        val category = categories.valueAt(codePoint) ?: return false
        return category[0] == 'L' || category[0] == 'M' || category == "Nd"
    }

    /** True if [codePoint] is a combining mark: General_Category `Mn`, `Mc` or `Me`. */
    fun isMark(codePoint: Int): Boolean = categories.valueAt(codePoint)?.get(0) == 'M'

    /**
     * True if [codePoint] is a format character (`Cf`) or is not assigned at all.
     *
     * The two are grouped because a keep-set rule treats them identically: both are removed, and
     * neither may stand for a word boundary. A code point outside every range in the table is
     * unassigned, which is an answer rather than a gap - the ranges bridge no unassigned gap, by
     * construction.
     */
    fun isFormatOrUnassigned(codePoint: Int): Boolean {
        val category = categories.valueAt(codePoint)
        return category == null || category == "Cf"
    }

    private fun decodeMappings(encoded: String): Map<Int, IntArray> {
        val result = HashMap<Int, IntArray>(2048)
        for (entry in encoded.split(';')) {
            if (entry.isEmpty()) continue
            val split = entry.indexOf('>')
            val mapping = entry.substring(split + 1).split(' ')
            result[entry.substring(0, split).toInt(16)] = IntArray(mapping.size) { mapping[it].toInt(16) }
        }
        return result
    }
}
