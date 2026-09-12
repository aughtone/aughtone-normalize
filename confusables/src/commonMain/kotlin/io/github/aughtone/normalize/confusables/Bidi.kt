package io.github.aughtone.normalize.confusables

import io.github.aughtone.normalize.common.InternalNormalizeApi
import io.github.aughtone.normalize.unicode.UnicodeProperties

/**
 * The bidirectional character types of UAX #9.
 *
 * Kept as an enum rather than strings: the algorithm below compares types on nearly every line, and the
 * rules read better against names than against string literals.
 */
internal enum class BidiType {
    L, R, AL, EN, ES, ET, AN, CS, NSM, BN, B, S, WS, ON, LRE, RLE, LRO, RLO, PDF, LRI, RLI, FSI, PDI;

    /** Removed by rule X9: the explicit formatting characters and boundary neutrals. */
    val removedByX9: Boolean
        get() = this == RLE || this == LRE || this == RLO || this == LRO || this == PDF || this == BN

    /** An isolate initiator: LRI, RLI or FSI. */
    val isolateInitiator: Boolean get() = this == LRI || this == RLI || this == FSI

    /** A neutral or isolate formatting character, as rules N0-N2 use the term. */
    val neutralOrIsolate: Boolean
        get() = this == B || this == S || this == WS || this == ON ||
            this == FSI || this == LRI || this == RLI || this == PDI

    companion object {
        /** Map a property value from the frozen table onto a type. */
        fun of(name: String): BidiType = entries.firstOrNull { it.name == name } ?: L
    }
}

/**
 * The Unicode Bidirectional Algorithm, UAX #9, through rule L2.
 *
 * It is here because the UTS-39 skeleton is defined through it: `skeleton(X)` is `bidiSkeleton(LTR, X)`,
 * and that reorders the text for display before any confusable mapping happens. Text that looks the same
 * is the thing being detected, so the comparison has to happen in the order a reader would see.
 *
 * The implementation follows the rules as numbered, and the names below are the specification's: X1-X8
 * assign explicit levels, X9 removes formatting characters, X10 divides the text into isolating run
 * sequences, W1-W7 resolve weak types, N0-N2 resolve neutrals, I1-I2 resolve implicit levels, and L1-L2
 * produce the visual order. Its conformance suite is `BidiCharacterTest.txt`, which is what proves this
 * is the algorithm rather than something that resembles it.
 */
@OptIn(InternalNormalizeApi::class)
internal class Bidi(private val codePoints: IntArray, paragraphLevel: Int) {

    private val length = codePoints.size
    private val initialTypes: Array<BidiType> = Array(length) { BidiType.of(UnicodeProperties.bidiClass(codePoints[it])) }
    private val types: Array<BidiType> = initialTypes.copyOf()
    private val levels = IntArray(length)
    private val matchingPdi = IntArray(length) { -1 }
    private val matchingIsolate = IntArray(length) { -1 }
    private val paragraphLevel: Int = paragraphLevel

    /** The resolved level of every character, with formatting characters carrying their context's level. */
    fun resolveLevels(): IntArray {
        determineMatchingIsolates()
        determineExplicitLevels()
        for (sequence in isolatingRunSequences()) sequence.resolve()
        applyL1()
        return levels.copyOf()
    }

    /**
     * The visual order, as indices into the original text: rule L2, reversing progressively larger runs
     * from the highest level down to the lowest odd level.
     */
    fun visualOrder(): IntArray {
        val resolved = resolveLevels()
        val visible = (0 until length).filter { !initialTypes[it].removedByX9 }
        if (visible.isEmpty()) return IntArray(0)

        val order = visible.toMutableList()
        val orderLevels = visible.map { resolved[it] }.toMutableList()
        val highest = orderLevels.max()
        val lowestOdd = orderLevels.filter { it % 2 == 1 }.minOrNull() ?: return order.toIntArray()

        var level = highest
        while (level >= lowestOdd) {
            var index = 0
            while (index < order.size) {
                if (orderLevels[index] >= level) {
                    var end = index
                    while (end + 1 < order.size && orderLevels[end + 1] >= level) end++
                    order.subList(index, end + 1).reverse()
                    orderLevels.subList(index, end + 1).reverse()
                    index = end + 1
                } else {
                    index++
                }
            }
            level--
        }
        return order.toIntArray()
    }

    /** BD9: pair each isolate initiator with its matching PDI, scanning once with a depth counter. */
    private fun determineMatchingIsolates() {
        for (index in 0 until length) {
            if (!initialTypes[index].isolateInitiator) continue
            var depth = 1
            var scan = index + 1
            while (scan < length) {
                val type = initialTypes[scan]
                if (type.isolateInitiator) {
                    depth++
                } else if (type == BidiType.PDI) {
                    depth--
                    if (depth == 0) {
                        matchingPdi[index] = scan
                        matchingIsolate[scan] = index
                        break
                    }
                }
                scan++
            }
        }
    }

    /** X1-X8: the directional status stack, with embeddings, overrides and isolates. */
    private fun determineExplicitLevels() {
        val stack = ArrayDeque<StatusEntry>()
        stack.addLast(StatusEntry(paragraphLevel, Override.Neutral, isolate = false))
        var overflowIsolates = 0
        var overflowEmbeddings = 0
        var validIsolates = 0

        for (index in 0 until length) {
            when (val type = initialTypes[index]) {
                BidiType.RLE, BidiType.LRE, BidiType.RLO, BidiType.LRO -> {
                    levels[index] = stack.last().level
                    val next = if (type == BidiType.RLE || type == BidiType.RLO) {
                        nextOdd(stack.last().level)
                    } else {
                        nextEven(stack.last().level)
                    }
                    if (next <= MAX_DEPTH && overflowIsolates == 0 && overflowEmbeddings == 0) {
                        val override = when (type) {
                            BidiType.RLO -> Override.RightToLeft
                            BidiType.LRO -> Override.LeftToRight
                            else -> Override.Neutral
                        }
                        stack.addLast(StatusEntry(next, override, isolate = false))
                    } else if (overflowIsolates == 0) {
                        overflowEmbeddings++
                    }
                }

                BidiType.RLI, BidiType.LRI, BidiType.FSI -> {
                    val rightToLeft = type == BidiType.RLI ||
                        (type == BidiType.FSI && firstStrongLevel(index) == 1)
                    levels[index] = stack.last().level
                    stack.last().override.applyTo(index)
                    val next = if (rightToLeft) nextOdd(stack.last().level) else nextEven(stack.last().level)
                    if (next <= MAX_DEPTH && overflowIsolates == 0 && overflowEmbeddings == 0) {
                        validIsolates++
                        stack.addLast(StatusEntry(next, Override.Neutral, isolate = true))
                    } else {
                        overflowIsolates++
                    }
                }

                BidiType.PDI -> {
                    if (overflowIsolates > 0) {
                        overflowIsolates--
                    } else if (validIsolates > 0) {
                        overflowEmbeddings = 0
                        while (!stack.last().isolate) stack.removeLast()
                        stack.removeLast()
                        validIsolates--
                    }
                    levels[index] = stack.last().level
                    stack.last().override.applyTo(index)
                }

                BidiType.PDF -> {
                    levels[index] = stack.last().level
                    if (overflowIsolates > 0) {
                        // within an overflow isolate: nothing to terminate
                    } else if (overflowEmbeddings > 0) {
                        overflowEmbeddings--
                    } else if (!stack.last().isolate && stack.size >= 2) {
                        stack.removeLast()
                    }
                }

                BidiType.B -> {
                    // X8: a paragraph separator terminates everything and takes the paragraph level.
                    stack.clear()
                    stack.addLast(StatusEntry(paragraphLevel, Override.Neutral, isolate = false))
                    overflowIsolates = 0
                    overflowEmbeddings = 0
                    validIsolates = 0
                    levels[index] = paragraphLevel
                }

                else -> {
                    levels[index] = stack.last().level
                    stack.last().override.applyTo(index)
                }
            }
        }
    }

    /** P2/P3 applied inside an FSI, to decide whether it behaves as an RLI or an LRI. */
    private fun firstStrongLevel(isolateIndex: Int): Int {
        val end = if (matchingPdi[isolateIndex] >= 0) matchingPdi[isolateIndex] else length
        var index = isolateIndex + 1
        while (index < end) {
            when (initialTypes[index]) {
                BidiType.L -> return 0
                BidiType.R, BidiType.AL -> return 1
                BidiType.LRI, BidiType.RLI, BidiType.FSI ->
                    index = if (matchingPdi[index] >= 0) matchingPdi[index] else end
                else -> index++
            }
        }
        return 0
    }

    /** BD13 and X10: the level runs, chained across isolates, each with its sos and eos. */
    private fun isolatingRunSequences(): List<Sequence> {
        val runs = mutableListOf<MutableList<Int>>()
        var current: MutableList<Int>? = null
        var currentLevel = -1
        for (index in 0 until length) {
            if (initialTypes[index].removedByX9) continue
            if (current == null || levels[index] != currentLevel) {
                current = mutableListOf()
                runs += current
                currentLevel = levels[index]
            }
            current += index
        }

        val runOfIndex = HashMap<Int, Int>()
        for ((runIndex, run) in runs.withIndex()) runOfIndex[run.first()] = runIndex

        val used = BooleanArray(runs.size)
        val sequences = mutableListOf<Sequence>()
        for ((runIndex, run) in runs.withIndex()) {
            if (used[runIndex]) continue
            val first = run.first()
            if (initialTypes[first] == BidiType.PDI && matchingIsolate[first] >= 0) continue

            val indices = mutableListOf<Int>()
            var currentRun = runIndex
            while (true) {
                used[currentRun] = true
                indices += runs[currentRun]
                val last = runs[currentRun].last()
                if (!initialTypes[last].isolateInitiator || matchingPdi[last] < 0) break
                val nextRun = runOfIndex[matchingPdi[last]] ?: break
                currentRun = nextRun
            }
            sequences += buildSequence(indices)
        }
        return sequences
    }

    private fun buildSequence(indices: List<Int>): Sequence {
        val sequenceLevel = levels[indices.first()]

        var before = indices.first() - 1
        while (before >= 0 && initialTypes[before].removedByX9) before--
        val previousLevel = if (before >= 0) levels[before] else paragraphLevel
        val sos = if (maxOf(sequenceLevel, previousLevel) % 2 == 1) BidiType.R else BidiType.L

        val last = indices.last()
        val endsWithUnmatchedIsolate = initialTypes[last].isolateInitiator && matchingPdi[last] < 0
        var after = last + 1
        while (after < length && initialTypes[after].removedByX9) after++
        val nextLevel = if (endsWithUnmatchedIsolate || after >= length) paragraphLevel else levels[after]
        val eos = if (maxOf(levels[last], nextLevel) % 2 == 1) BidiType.R else BidiType.L

        return Sequence(indices, sequenceLevel, sos, eos)
    }

    /** L1: segment and paragraph separators, and trailing whitespace, return to the paragraph level. */
    private fun applyL1() {
        var index = 0
        while (index < length) {
            val original = initialTypes[index]
            if (original == BidiType.B || original == BidiType.S) {
                levels[index] = paragraphLevel
                var scan = index - 1
                while (scan >= 0 && initialTypes[scan].resetBeforeSeparator()) {
                    levels[scan] = paragraphLevel
                    scan--
                }
            }
            index++
        }
        var tail = length - 1
        while (tail >= 0 && initialTypes[tail].resetBeforeSeparator()) {
            levels[tail] = paragraphLevel
            tail--
        }
    }

    private fun BidiType.resetBeforeSeparator(): Boolean =
        this == BidiType.WS || isolateInitiator || this == BidiType.PDI || removedByX9

    /** One isolating run sequence, and the W, N and I rules that run over it. */
    private inner class Sequence(
        val indices: List<Int>,
        val level: Int,
        val sos: BidiType,
        val eos: BidiType,
    ) {
        private val sequenceTypes: Array<BidiType> = Array(indices.size) { types[indices[it]] }

        fun resolve() {
            resolveWeak()
            resolveBrackets()
            resolveNeutrals()
            resolveImplicit()
            for ((position, index) in indices.withIndex()) types[index] = sequenceTypes[position]
        }

        private fun typeAt(position: Int): BidiType = sequenceTypes[position]

        private fun resolveWeak() {
            // W1: an NSM takes the type of what precedes it, or ON after an isolate boundary.
            for (position in sequenceTypes.indices) {
                if (sequenceTypes[position] != BidiType.NSM) continue
                val previous = if (position == 0) sos else typeAt(position - 1)
                sequenceTypes[position] = if (previous.isolateInitiator || previous == BidiType.PDI) BidiType.ON else previous
            }

            // W2: a European number after an Arabic letter becomes an Arabic number.
            for (position in sequenceTypes.indices) {
                if (sequenceTypes[position] != BidiType.EN) continue
                var scan = position - 1
                var strong = sos
                while (scan >= 0) {
                    val type = typeAt(scan)
                    if (type == BidiType.L || type == BidiType.R || type == BidiType.AL) {
                        strong = type
                        break
                    }
                    scan--
                }
                if (strong == BidiType.AL) sequenceTypes[position] = BidiType.AN
            }

            // W3: Arabic letters become R.
            for (position in sequenceTypes.indices) {
                if (sequenceTypes[position] == BidiType.AL) sequenceTypes[position] = BidiType.R
            }

            // W4: a single separator between two numbers of the same kind joins them.
            for (position in 1 until sequenceTypes.size - 1) {
                val type = sequenceTypes[position]
                val previous = typeAt(position - 1)
                val next = typeAt(position + 1)
                if (type == BidiType.ES && previous == BidiType.EN && next == BidiType.EN) {
                    sequenceTypes[position] = BidiType.EN
                } else if (type == BidiType.CS && previous == next && (previous == BidiType.EN || previous == BidiType.AN)) {
                    sequenceTypes[position] = previous
                }
            }

            // W5: a run of European terminators beside a European number becomes European numbers.
            for (position in sequenceTypes.indices) {
                if (sequenceTypes[position] != BidiType.ET) continue
                var start = position
                while (start > 0 && sequenceTypes[start - 1] == BidiType.ET) start--
                var end = position
                while (end < sequenceTypes.size - 1 && sequenceTypes[end + 1] == BidiType.ET) end++
                val before = if (start == 0) sos else typeAt(start - 1)
                val after = if (end == sequenceTypes.size - 1) eos else typeAt(end + 1)
                if (before == BidiType.EN || after == BidiType.EN) {
                    for (scan in start..end) sequenceTypes[scan] = BidiType.EN
                }
            }

            // W6: every remaining separator and terminator becomes neutral.
            for (position in sequenceTypes.indices) {
                val type = sequenceTypes[position]
                if (type == BidiType.ES || type == BidiType.ET || type == BidiType.CS) {
                    sequenceTypes[position] = BidiType.ON
                }
            }

            // W7: a European number in left-to-right context becomes L.
            for (position in sequenceTypes.indices) {
                if (sequenceTypes[position] != BidiType.EN) continue
                var scan = position - 1
                var strong = sos
                while (scan >= 0) {
                    val type = typeAt(scan)
                    if (type == BidiType.L || type == BidiType.R) {
                        strong = type
                        break
                    }
                    scan--
                }
                if (strong == BidiType.L) sequenceTypes[position] = BidiType.L
            }
        }

        /** N0 and BD16: bracket pairs take a direction from what they enclose, or from their context. */
        private fun resolveBrackets() {
            val embedding = if (level % 2 == 1) BidiType.R else BidiType.L
            val opposite = if (embedding == BidiType.L) BidiType.R else BidiType.L
            val stack = ArrayDeque<Pair<Int, Int>>()
            val pairs = mutableListOf<Pair<Int, Int>>()

            for (position in indices.indices) {
                if (sequenceTypes[position] != BidiType.ON) continue
                val bracket = UnicodeProperties.pairedBracket(codePoints[indices[position]]) ?: continue
                if (bracket.opening) {
                    if (stack.size == BRACKET_STACK) return
                    stack.addLast(canonical(bracket.paired) to position)
                } else {
                    val closing = canonical(codePoints[indices[position]])
                    val depth = stack.indexOfLast { it.first == closing }
                    if (depth < 0) continue
                    pairs += stack[depth].second to position
                    while (stack.size > depth) stack.removeLast()
                }
            }

            for ((open, close) in pairs.sortedBy { it.first }) {
                var found: BidiType? = null
                for (position in open + 1 until close) {
                    val strong = strongOf(sequenceTypes[position]) ?: continue
                    if (strong == embedding) {
                        found = embedding
                        break
                    }
                    found = opposite
                }
                when (found) {
                    embedding -> setBracketPair(open, close, embedding)
                    opposite -> {
                        var context = sos
                        for (position in open - 1 downTo 0) {
                            val strong = strongOf(sequenceTypes[position]) ?: continue
                            context = strong
                            break
                        }
                        setBracketPair(open, close, if (context == opposite) opposite else embedding)
                    }

                    else -> Unit
                }
            }
        }

        /** Inside N0, European and Arabic numbers count as right-to-left. */
        private fun strongOf(type: BidiType): BidiType? = when (type) {
            BidiType.L -> BidiType.L
            BidiType.R, BidiType.EN, BidiType.AN -> BidiType.R
            else -> null
        }

        private fun setBracketPair(open: Int, close: Int, direction: BidiType) {
            sequenceTypes[open] = direction
            sequenceTypes[close] = direction
            // Any NSM following a paired bracket takes the bracket's new type, per N0's final note.
            for (position in listOf(open, close)) {
                var scan = position + 1
                while (scan < sequenceTypes.size && initialTypes[indices[scan]] == BidiType.NSM) {
                    sequenceTypes[scan] = direction
                    scan++
                }
            }
        }

        /** N1 and N2: neutrals take a surrounding direction when it agrees, otherwise the embedding one. */
        private fun resolveNeutrals() {
            val embedding = if (level % 2 == 1) BidiType.R else BidiType.L
            var position = 0
            while (position < sequenceTypes.size) {
                if (!sequenceTypes[position].neutralOrIsolate) {
                    position++
                    continue
                }
                var end = position
                while (end + 1 < sequenceTypes.size && sequenceTypes[end + 1].neutralOrIsolate) end++

                val before = if (position == 0) sos else strongContext(sequenceTypes[position - 1])
                val after = if (end == sequenceTypes.size - 1) eos else strongContext(sequenceTypes[end + 1])
                val resolved = if (before == after && before != null) before else embedding
                for (scan in position..end) sequenceTypes[scan] = resolved
                position = end + 1
            }
        }

        /** For N1, numbers influence neutrals as though they were R. */
        private fun strongContext(type: BidiType): BidiType? = when (type) {
            BidiType.L -> BidiType.L
            BidiType.R, BidiType.EN, BidiType.AN -> BidiType.R
            else -> null
        }

        /** I1 and I2: the resolved types raise the level, by one or two depending on the direction. */
        private fun resolveImplicit() {
            for ((position, index) in indices.withIndex()) {
                val type = sequenceTypes[position]
                levels[index] = if (level % 2 == 0) {
                    when (type) {
                        BidiType.R -> level + 1
                        BidiType.AN, BidiType.EN -> level + 2
                        else -> level
                    }
                } else {
                    when (type) {
                        BidiType.L, BidiType.EN, BidiType.AN -> level + 1
                        else -> level
                    }
                }
            }
        }
    }

    private class StatusEntry(val level: Int, val override: Override, val isolate: Boolean)

    private enum class Override { Neutral, LeftToRight, RightToLeft }

    private fun Override.applyTo(index: Int) {
        when (this) {
            Override.Neutral -> Unit
            Override.LeftToRight -> types[index] = BidiType.L
            Override.RightToLeft -> types[index] = BidiType.R
        }
    }

    /** The least odd level greater than [level], as rules X2, X4 and X5a compute it. */
    private fun nextOdd(level: Int): Int = if (level % 2 == 0) level + 1 else level + 2

    /** The least even level greater than [level], as rules X3, X5 and X5b compute it. */
    private fun nextEven(level: Int): Int = if (level % 2 == 0) level + 2 else level + 1

    private companion object {
        /** The stack in BD16 holds exactly this many bracket pairs before the rule gives up. */
        const val BRACKET_STACK = 63

        /** UAX #9's maximum explicit depth. */
        const val MAX_DEPTH = 125

        /** U+2329 and U+3008 are canonically equivalent, as are their closing partners. */
        fun canonical(codePoint: Int): Int = when (codePoint) {
            0x3008 -> 0x2329
            0x3009 -> 0x232A
            else -> codePoint
        }
    }
}
