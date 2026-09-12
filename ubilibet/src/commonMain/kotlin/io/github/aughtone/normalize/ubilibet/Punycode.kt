package io.github.aughtone.normalize.ubilibet

/**
 * Punycode, RFC 3492: the bootstring encoding that carries a Unicode label through systems that only
 * accept ASCII.
 *
 * The parameters below are the ones the RFC fixes for Punycode, and they are not tuning knobs - a
 * different bias or damping produces a different encoding of the same label, which would be a different
 * canonical form. The overflow checks are part of the specification too, not defensive padding: without
 * them a crafted label can be made to decode to something other than what it encodes.
 */
internal object Punycode {

    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128
    private const val DELIMITER = '-'
    private const val MAX_INT = Int.MAX_VALUE

    /** The ASCII prefix that marks an encoded label. */
    const val PREFIX: String = "xn--"

    /** Encode [input] to Punycode, or `null` if it overflows - which a legitimate label never does. */
    fun encode(input: String): String? {
        val codePoints = input.toCodePoints()
        val output = StringBuilder(input.length)

        for (codePoint in codePoints) {
            if (codePoint < 0x80) output.append(codePoint.toChar())
        }
        val basicLength = output.length
        var handled = basicLength
        if (basicLength > 0) output.append(DELIMITER)

        var n = INITIAL_N
        var delta = 0
        var bias = INITIAL_BIAS

        while (handled < codePoints.size) {
            var m = MAX_INT
            for (codePoint in codePoints) {
                if (codePoint in n until m) m = codePoint
            }
            if (m - n > (MAX_INT - delta) / (handled + 1)) return null
            delta += (m - n) * (handled + 1)
            n = m

            for (codePoint in codePoints) {
                if (codePoint < n) {
                    delta += 1
                    if (delta == 0) return null
                }
                if (codePoint == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = threshold(k, bias)
                        if (q < t) break
                        output.append(digitToChar(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    output.append(digitToChar(q))
                    bias = adapt(delta, handled + 1, handled == basicLength)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return output.toString()
    }

    /** Decode Punycode [input] (without its prefix), or `null` if it is not valid Punycode. */
    fun decode(input: String): String? {
        val output = mutableListOf<Int>()
        val lastDelimiter = input.lastIndexOf(DELIMITER)

        if (lastDelimiter > 0) {
            for (index in 0 until lastDelimiter) {
                val character = input[index]
                if (character.code >= 0x80) return null
                output += character.code
            }
        }

        var n = INITIAL_N
        var index = 0
        var bias = INITIAL_BIAS
        var position = if (lastDelimiter > 0) lastDelimiter + 1 else 0
        if (position > input.length) return null

        while (position < input.length) {
            val previousIndex = index
            var weight = 1
            var k = BASE
            while (true) {
                if (position >= input.length) return null
                val digit = charToDigit(input[position]) ?: return null
                position++
                if (digit > (MAX_INT - index) / weight) return null
                index += digit * weight
                val t = threshold(k, bias)
                if (digit < t) break
                if (weight > MAX_INT / (BASE - t)) return null
                weight *= BASE - t
                k += BASE
            }
            bias = adapt(index - previousIndex, output.size + 1, previousIndex == 0)
            if (index / (output.size + 1) > MAX_INT - n) return null
            n += index / (output.size + 1)
            index %= output.size + 1
            if (n > 0x10FFFF || (n in 0xD800..0xDFFF)) return null
            output.add(index, n)
            index++
        }
        return output.toCodePointString()
    }

    private fun threshold(k: Int, bias: Int): Int = when {
        k <= bias + TMIN -> TMIN
        k >= bias + TMAX -> TMAX
        else -> k - bias
    }

    private fun adapt(delta: Int, numPoints: Int, firstTime: Boolean): Int {
        var scaled = if (firstTime) delta / DAMP else delta / 2
        scaled += scaled / numPoints
        var k = 0
        while (scaled > ((BASE - TMIN) * TMAX) / 2) {
            scaled /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * scaled / (scaled + SKEW)
    }

    /** `0`-`25` become `a`-`z`, `26`-`35` become `0`-`9`, as the RFC's basic code points. */
    private fun digitToChar(digit: Int): Char = if (digit < 26) ('a' + digit) else ('0' + digit - 26)

    private fun charToDigit(character: Char): Int? = when (character) {
        in 'a'..'z' -> character - 'a'
        in 'A'..'Z' -> character - 'A'
        in '0'..'9' -> character - '0' + 26
        else -> null
    }
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
