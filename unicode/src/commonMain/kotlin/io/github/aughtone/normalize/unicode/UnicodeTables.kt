package io.github.aughtone.normalize.unicode

import io.github.aughtone.normalize.unicode.generated.NormalizationTables

/**
 * The frozen Unicode data, decoded into the lookups the algorithms need.
 *
 * Everything here comes from [NormalizationTables], which the generator produced from a pinned Unicode
 * release. **Nothing reads the platform's Unicode tables** - not `java.text.Normalizer`, not
 * `NSString`, not `String.prototype.normalize`. Those follow the operating system's Unicode version, so
 * the same input would normalize differently on an old Android build and a current iOS one, and since a
 * caller hashes the result and discards the input, the mismatch would be undetectable and
 * unrecoverable. That is the failure this whole suite exists to prevent.
 *
 * Decoding happens once, lazily: a program that never normalizes never pays for it, and a target that
 * never references a table lets dead-code elimination drop the data entirely.
 */
internal object UnicodeTables {

    /** The Unicode release every table here was frozen from. */
    const val VERSION: String = NormalizationTables.UNICODE_VERSION

    /** Canonical decompositions: a code point to the sequence it decomposes to, one step deep. */
    val canonical: Map<Int, IntArray> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        decodeMappings(NormalizationTables.canonicalDecompositions)
    }

    /** Compatibility decompositions, used by NFKC and NFKD and by nothing else. */
    val compatibility: Map<Int, IntArray> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        decodeMappings(NormalizationTables.compatibilityDecompositions)
    }

    /** Canonical combining classes. A code point absent from this map has class zero. */
    val combiningClasses: Map<Int, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val result = HashMap<Int, Int>(1024)
        for (entry in NormalizationTables.combiningClasses.split(';')) {
            if (entry.isEmpty()) continue
            val split = entry.indexOf(':')
            result[entry.substring(0, split).toInt(16)] = entry.substring(split + 1).toInt()
        }
        result
    }

    /**
     * Pairs that compose, keyed by starter and combining mark packed into one `Long`.
     *
     * Built by inverting the canonical decompositions and dropping every full composition exclusion -
     * singletons, non-starter decompositions and the script-specific list. Inverting rather than
     * shipping a second table keeps the two directions from disagreeing.
     */
    val compositions: Map<Long, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val exclusions = compositionExclusions
        val result = HashMap<Long, Int>(2048)
        for ((composite, mapping) in canonical) {
            if (mapping.size != 2) continue
            if (composite in exclusions) continue
            result[pack(mapping[0], mapping[1])] = composite
        }
        result
    }

    /** Full composition exclusions: characters that decompose but must never recompose. */
    val compositionExclusions: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val result = HashSet<Int>(2048)
        for (entry in NormalizationTables.compositionExclusions.split(';')) {
            if (entry.isNotEmpty()) result += entry.toInt(16)
        }
        result
    }

    /** The combining class of [codePoint]; zero for a starter. */
    fun combiningClass(codePoint: Int): Int = combiningClasses[codePoint] ?: 0

    /** Pack a starter and a following character into one key, so composition needs no nested maps. */
    fun pack(first: Int, second: Int): Long = (first.toLong() shl 32) or (second.toLong() and 0xFFFFFFFFL)

    private fun decodeMappings(encoded: String): Map<Int, IntArray> {
        val result = HashMap<Int, IntArray>(4096)
        for (entry in encoded.split(';')) {
            if (entry.isEmpty()) continue
            val split = entry.indexOf('>')
            val codePoint = entry.substring(0, split).toInt(16)
            val mapping = entry.substring(split + 1).split(' ')
            result[codePoint] = IntArray(mapping.size) { index -> mapping[index].toInt(16) }
        }
        return result
    }
}
