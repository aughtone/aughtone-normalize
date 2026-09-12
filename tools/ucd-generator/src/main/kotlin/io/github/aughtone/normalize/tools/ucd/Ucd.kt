package io.github.aughtone.normalize.tools.ucd

import java.io.File
import java.security.MessageDigest

/**
 * Reading the pinned Unicode Character Database.
 *
 * The files are checked in rather than downloaded, and verified against their recorded checksums
 * before a single line is parsed. A build that reaches the network for the data it freezes is not
 * reproducible, and a table generated from silently different input is the exact failure this suite
 * exists to prevent - it would change canonical bytes with nothing to show for it in the diff.
 */
class UcdSource(private val directory: File) {

    /** The Unicode release this directory holds, taken from the directory name. */
    val version: String = directory.name

    /**
     * Verify every file against `SHA256SUMS`, failing on a mismatch, a missing file, or an
     * unrecorded one. An extra file is a failure too: it means the pinned set changed without the
     * checksums being regenerated, and nobody can say which state produced the tables.
     */
    fun verifyChecksums() {
        val sums = File(directory, "SHA256SUMS")
        require(sums.isFile) { "missing ${sums.path}: the pinned data must carry its checksums" }

        val recorded = sums.readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                require(parts.size == 2) { "malformed checksum line: $line" }
                parts[1].removePrefix("*") to parts[0]
            }

        val present = directory.listFiles { file -> file.isFile && file.name.endsWith(".txt") }
            ?.map { it.name }?.sorted().orEmpty()

        val unrecorded = present - recorded.keys
        require(unrecorded.isEmpty()) { "not covered by SHA256SUMS: ${unrecorded.joinToString()}" }

        for ((name, expected) in recorded) {
            val file = File(directory, name)
            require(file.isFile) { "SHA256SUMS names a missing file: $name" }
            val actual = sha256(file)
            require(actual == expected) {
                "$name does not match its recorded checksum.\n  expected $expected\n  actual   $actual\n" +
                    "The pinned data changed. Regenerate the checksums deliberately, never to make this pass."
            }
        }
    }

    /**
     * Parse `UnicodeData.txt` into the fields normalization needs: canonical and compatibility
     * decompositions, and canonical combining classes.
     *
     * Ranges (`First>`/`Last>` pairs) are skipped deliberately: no character inside one carries a
     * decomposition or a non-zero combining class, and expanding them would add tens of thousands of
     * entries that all say nothing.
     */
    fun readUnicodeData(): UnicodeData {
        val canonical = LinkedHashMap<Int, List<Int>>()
        val compatibility = LinkedHashMap<Int, List<Int>>()
        val combiningClasses = LinkedHashMap<Int, Int>()
        val marks = sortedSetOf<Int>()

        File(directory, "UnicodeData.txt").forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val fields = line.split(';')
            require(fields.size >= 6) { "malformed UnicodeData line: $line" }
            val name = fields[1]
            if (name.endsWith(", First>") || name.endsWith(", Last>")) return@forEachLine

            val codePoint = fields[0].toInt(16)

            // General_Category=M: a label may not begin with one, and combining class alone does not
            // identify them - a spacing mark has class zero and is still a mark.
            if (fields[2].startsWith("M")) marks += codePoint

            val ccc = fields[3].toInt()
            if (ccc != 0) combiningClasses[codePoint] = ccc

            val decomposition = fields[5]
            if (decomposition.isNotEmpty()) {
                if (decomposition.startsWith('<')) {
                    val mapping = decomposition.substringAfter('>').trim()
                    compatibility[codePoint] = mapping.split(' ').map { it.toInt(16) }
                } else {
                    canonical[codePoint] = decomposition.split(' ').map { it.toInt(16) }
                }
            }
        }
        return UnicodeData(canonical, compatibility, combiningClasses, marks)
    }

    /**
     * Read `Full_Composition_Exclusion` from `DerivedNormalizationProps.txt`.
     *
     * The derived property is the right source rather than `CompositionExclusions.txt` alone: it
     * already folds in singleton decompositions and non-starter decompositions, which are excluded
     * from composition by rule instead of by being listed.
     */
    fun readCompositionExclusions(): Set<Int> {
        val exclusions = sortedSetOf<Int>()
        File(directory, "DerivedNormalizationProps.txt").forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            val fields = line.split(';').map { it.trim() }
            if (fields.size < 2 || fields[1] != "Full_Composition_Exclusion") return@forEachLine
            for (codePoint in parseCodePointRange(fields[0])) exclusions += codePoint
        }
        require(exclusions.isNotEmpty()) { "no Full_Composition_Exclusion entries found: wrong file?" }
        return exclusions
    }

    /**
     * Read `NormalizationTest.txt` into its five columns, dropping comments and the part markers.
     * Every line is a conformance case: `NFC(c1) == NFC(c2) == NFC(c3) == c2`, and so on.
     */
    fun readConformanceCases(): List<ConformanceCase> {
        val cases = mutableListOf<ConformanceCase>()
        File(directory, "NormalizationTest.txt").forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("@")) return@forEachLine
            val columns = line.split(';').dropLast(1)
            require(columns.size == 5) { "malformed NormalizationTest line: $raw" }
            cases += ConformanceCase(columns.map { column -> column.trim().split(' ').map { it.toInt(16) } })
        }
        require(cases.isNotEmpty()) { "no conformance cases found: wrong file?" }
        return cases
    }

    /**
     * Read the UTS-46 mapping table: every code point's status, and the mapping for those that have
     * one. This is the table that decides what a domain label may contain and what it becomes.
     */
    fun readIdnaMappings(): List<RangeEntry> = readRanges("IdnaMappingTable.txt") { fields ->
        val status = when (val raw = fields[1]) {
            "valid" -> "v"
            "ignored" -> "i"
            "mapped" -> "m"
            "deviation" -> "d"
            "disallowed" -> "x"
            "disallowed_STD3_valid" -> "s3v"
            "disallowed_STD3_mapped" -> "s3m"
            else -> error("unknown IDNA status: $raw")
        }
        val mapping = fields.getOrNull(2)?.trim().orEmpty()
        if (mapping.isEmpty()) status else status + ">" + mapping.split(' ').joinToString(" ") { it.lowercase() }
    }

    /** Read joining types, which the UTS-46 ContextJ rules need to validate zero-width joiners. */
    fun readJoiningTypes(): List<RangeEntry> = readRanges("DerivedJoiningType.txt") { fields -> fields[1] }

    /**
     * Read bidi classes, which the RFC 5893 bidi rule needs. This table lands in `:unicode` rather than
     * in the module that uses it: more than one module needs the property, and a property carried twice
     * is a property that can disagree with itself.
     */
    fun readBidiClasses(): List<RangeEntry> = readRanges("DerivedBidiClass.txt") { fields -> fields[1] }

    /** Read `IdnaTestV2.txt` into its columns, dropping comments and section markers. */
    fun readIdnaConformanceRows(): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        File(directory, "IdnaTestV2.txt").forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            rows += line.split(';').map { it.trim() }
        }
        require(rows.isNotEmpty()) { "no IDNA conformance rows found: wrong file?" }
        return rows
    }

    /**
     * Read the UTS-39 confusable mappings: a source character to the prototype it is confusable with.
     * The file carries only `MA` (mixed-script any-case) entries in recent releases, and a prototype may
     * be several code points - `oe` for the `oe` ligature.
     */
    fun readConfusables(): Map<Int, List<Int>> {
        val mappings = LinkedHashMap<Int, List<Int>>()
        File(directory, "confusables.txt").forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            val fields = line.split(';').map { it.trim() }
            require(fields.size >= 3) { "malformed confusables line: $raw" }
            mappings[fields[0].toInt(16)] = fields[1].split(' ').filter { it.isNotEmpty() }.map { it.toInt(16) }
        }
        require(mappings.isNotEmpty()) { "no confusable mappings found: wrong file?" }
        return mappings
    }

    /** Read one boolean property out of `DerivedCoreProperties.txt`, such as Default_Ignorable_Code_Point. */
    fun readCoreProperty(name: String): List<RangeEntry> {
        val entries = mutableListOf<RangeEntry>()
        File(directory, "DerivedCoreProperties.txt").forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("@")) return@forEachLine
            val fields = line.split(';').map { it.trim() }
            if (fields.size < 2 || fields[1] != name) return@forEachLine
            val range = parseCodePointRange(fields[0])
            entries += RangeEntry(range.first, range.last, "y")
        }
        require(entries.isNotEmpty()) { "no $name entries found: wrong file?" }
        return entries.sortedBy { it.first }
    }

    /** Read `BidiBrackets.txt`: each bracket, its pair, and whether it opens or closes. */
    fun readBidiBrackets(): List<RangeEntry> {
        val entries = mutableListOf<RangeEntry>()
        File(directory, "BidiBrackets.txt").forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            val fields = line.split(';').map { it.trim() }
            require(fields.size >= 3) { "malformed BidiBrackets line: $raw" }
            val codePoint = fields[0].toInt(16)
            entries += RangeEntry(codePoint, codePoint, fields[2] + " " + fields[1].lowercase())
        }
        require(entries.isNotEmpty()) { "no bracket pairs found: wrong file?" }
        return entries.sortedBy { it.first }
    }

    /** Read `BidiMirroring.txt`: the glyph each mirrored character becomes when displayed right-to-left. */
    fun readBidiMirroring(): List<RangeEntry> {
        val entries = mutableListOf<RangeEntry>()
        File(directory, "BidiMirroring.txt").forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            val fields = line.split(';').map { it.trim() }
            require(fields.size >= 2) { "malformed BidiMirroring line: $raw" }
            val codePoint = fields[0].toInt(16)
            entries += RangeEntry(codePoint, codePoint, fields[1].lowercase())
        }
        require(entries.isNotEmpty()) { "no mirroring pairs found: wrong file?" }
        return entries.sortedBy { it.first }
    }

    /**
     * Read `BidiCharacterTest.txt`: input code points, the paragraph direction to use, the resolved
     * paragraph level, each character's resolved level, and the visual order.
     *
     * [everyNth] samples the file deterministically. The whole file is 94,000 cases and several megabytes
     * once compiled into a test binary, which is too much to ship to every target; the JVM suite reads
     * the file itself and runs all of it.
     */
    fun readBidiConformanceRows(everyNth: Int): List<String> {
        val rows = mutableListOf<String>()
        var index = 0
        File(directory, "BidiCharacterTest.txt").forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachLine
            if (index++ % everyNth != 0) return@forEachLine
            rows += line
        }
        require(rows.isNotEmpty()) { "no bidi conformance rows found: wrong file?" }
        return rows
    }

    /**
     * Read a `start..end ; value ; ...` property file into ranges, skipping comments and `@missing`
     * defaults. Those defaults cover unassigned code points, which UTS-46 disallows outright, so a
     * table built from the explicit ranges alone cannot be silently short.
     */
    private fun readRanges(fileName: String, value: (List<String>) -> String): List<RangeEntry> {
        val entries = mutableListOf<RangeEntry>()
        File(directory, fileName).forEachLine { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty() || line.startsWith("@")) return@forEachLine
            val fields = line.split(';').map { it.trim() }
            require(fields.size >= 2) { "malformed line in $fileName: $raw" }
            val range = parseCodePointRange(fields[0])
            entries += RangeEntry(range.first, range.last, value(fields))
        }
        require(entries.isNotEmpty()) { "no ranges found in $fileName: wrong file?" }
        return entries.sortedBy { it.first }
    }

    private fun parseCodePointRange(field: String): IntRange {
        val parts = field.split("..")
        val first = parts[0].trim().toInt(16)
        val last = if (parts.size == 2) parts[1].trim().toInt(16) else first
        return first..last
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

/** The normalization fields of `UnicodeData.txt`, keyed by code point. */
class UnicodeData(
    val canonicalDecompositions: Map<Int, List<Int>>,
    val compatibilityDecompositions: Map<Int, List<Int>>,
    val combiningClasses: Map<Int, Int>,
    val marks: Set<Int>,
)

/** A code point range carrying one property value, as the derived property files publish them. */
class RangeEntry(val first: Int, val last: Int, val value: String)

/** One row of `NormalizationTest.txt`: five spellings of the same text. */
class ConformanceCase(val columns: List<List<Int>>)
