package io.github.aughtone.normalize.tools.ucd

/**
 * A named body of frozen data, encoded as text.
 *
 * Text rather than a binary blob because it diffs: the material-change check compares a regeneration
 * against the checked-in baseline entry by entry, and a reviewer can read what moved. The runtime
 * decodes it once, on first use.
 *
 * The encoding is deliberately dull - lowercase hexadecimal, one entry per record, no padding:
 *
 * ```
 * decompositions      00c0>0041 0300;00c1>0041 0301
 * combining classes   0300:230;0301:230
 * exclusions          0340;0341;0343
 * ```
 */
class Table(val name: String, val family: Family, val entries: Map<String, String>) {

    /** The whole table as one string, entries joined by `;`. */
    fun encode(): String = entries.entries.joinToString(";") { (key, value) ->
        if (value.isEmpty()) key else "$key$value"
    }

    companion object {
        /** Parse an encoded table back into entries, so a baseline can be compared with a regeneration. */
        fun decode(name: String, family: Family, encoded: String): Table {
            if (encoded.isEmpty()) return Table(name, family, emptyMap())
            val entries = LinkedHashMap<String, String>()
            for (record in encoded.split(';')) {
                val split = record.indexOfFirst { it == '>' || it == ':' }
                if (split < 0) entries[record] = "" else entries[record.substring(0, split)] = record.substring(split)
            }
            return Table(name, family, entries)
        }
    }
}

/**
 * Which stability rules a table is held to. The rules differ by upstream guarantee, and one global
 * rule would either wave through a change that must never happen or stop the build on a routine one.
 */
enum class Family(val label: String, val modificationsAllowed: Boolean) {
    /**
     * Normalization data. The Unicode stability policy forbids changing an existing decomposition or
     * combining class, so a modification here is a hard stop: it would change the canonical bytes of
     * values already normalized, and the delta packaging in DOC-0001 assumes additions only.
     */
    Normalization("normalization", modificationsAllowed = false),

    /**
     * IDNA data. UTS-46 guarantees only that a character already valid keeps its mapping; a character
     * that was disallowed may change. Modifications are therefore expected and reported rather than
     * fatal, and the module that carries the table decides what a change means for its policies.
     */
    Idna("IDNA", modificationsAllowed = true),

    /**
     * Confusable data. UTS-39 states outright that a mapping may change between releases and that
     * stored skeletons must be recomputed, so modifications are ordinary here.
     */
    Confusables("confusables", modificationsAllowed = true),
}

/** What changed between a checked-in baseline and a regeneration. */
class TableDiff(
    val table: Table,
    val added: List<String>,
    val removed: List<String>,
    val modified: List<String>,
) {
    val isUnchanged: Boolean get() = added.isEmpty() && removed.isEmpty() && modified.isEmpty()

    /** True when this diff must stop the build: a change the table's family forbids. */
    val isMaterialViolation: Boolean
        get() = !table.family.modificationsAllowed && (modified.isNotEmpty() || removed.isNotEmpty())

    fun report(): String = buildString {
        append("${table.name} (${table.family.label}): ")
        if (isUnchanged) {
            append("no change")
            return@buildString
        }
        append("${added.size} added, ${modified.size} modified, ${removed.size} removed")
        for (key in modified.take(REPORTED)) append("\n    modified: U+${key.uppercase()}")
        for (key in removed.take(REPORTED)) append("\n    removed:  U+${key.uppercase()}")
        val hidden = modified.size + removed.size - (modified.take(REPORTED).size + removed.take(REPORTED).size)
        if (hidden > 0) append("\n    ... and $hidden more")
    }

    companion object {
        private const val REPORTED = 20

        /**
         * Classify every difference between [baseline] and [current] as an addition, a removal or a
         * modification of an existing entry. That distinction is the whole point of the check: the
         * suite's delta packaging and its byte-stability promise both rest on normalization changes
         * being additions and nothing else.
         */
        fun between(baseline: Table?, current: Table): TableDiff {
            if (baseline == null) return TableDiff(current, added = emptyList(), removed = emptyList(), modified = emptyList())
            val added = current.entries.keys.filter { it !in baseline.entries }
            val removed = baseline.entries.keys.filter { it !in current.entries }
            val modified = current.entries.filter { (key, value) ->
                val previous = baseline.entries[key]
                previous != null && previous != value
            }.keys.toList()
            return TableDiff(current, added.sorted(), removed.sorted(), modified.sorted())
        }
    }
}

/** Build the normalization tables from parsed UCD data. */
fun normalizationTables(data: UnicodeData, exclusions: Set<Int>): List<Table> = listOf(
    Table(
        "canonical-decompositions",
        Family.Normalization,
        data.canonicalDecompositions.toSortedMap().entries.associate { (codePoint, mapping) ->
            hex(codePoint) to ">" + mapping.joinToString(" ") { hex(it) }
        },
    ),
    Table(
        "compatibility-decompositions",
        Family.Normalization,
        data.compatibilityDecompositions.toSortedMap().entries.associate { (codePoint, mapping) ->
            hex(codePoint) to ">" + mapping.joinToString(" ") { hex(it) }
        },
    ),
    Table(
        "combining-classes",
        Family.Normalization,
        data.combiningClasses.toSortedMap().entries.associate { (codePoint, ccc) -> hex(codePoint) to ":$ccc" },
    ),
    Table(
        "composition-exclusions",
        Family.Normalization,
        exclusions.sorted().associate { hex(it) to "" },
    ),
)

/**
 * Build a table from code point ranges, as the derived property files publish them.
 *
 * Ranges rather than one entry per character: the IDNA table covers the whole code space, and expanding
 * it would turn 9,000 entries into a million that say the same thing.
 */
fun rangeTable(name: String, family: Family, entries: List<RangeEntry>): Table = Table(
    name,
    family,
    entries.associate { entry ->
        val key = if (entry.first == entry.last) hex(entry.first) else "${hex(entry.first)}-${hex(entry.last)}"
        key to ":" + entry.value
    },
)

/** Lowercase hexadecimal with no padding, the one spelling used everywhere in an encoded table. */
fun hex(value: Int): String = value.toString(16)
