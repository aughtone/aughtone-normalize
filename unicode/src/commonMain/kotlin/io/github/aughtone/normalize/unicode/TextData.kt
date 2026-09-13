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
