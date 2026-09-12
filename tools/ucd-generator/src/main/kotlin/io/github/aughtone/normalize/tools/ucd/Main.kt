package io.github.aughtone.normalize.tools.ucd

import java.io.File
import kotlin.system.exitProcess

/**
 * Generate the suite's frozen Unicode tables from a pinned release of the Unicode Character Database.
 *
 * ```
 * ucd-generator <ucd-directory> <repository-root> [--verify]
 * ```
 *
 * Without `--verify` it writes the baselines beside the pinned data and the generated sources into the
 * modules that carry them. With `--verify` it writes nothing and fails if what is checked in differs
 * from what the pinned data produces, which is how an edited table or a stale regeneration is caught by
 * `./gradlew check` rather than by a consumer.
 *
 * Tables land in the module that needs them, and a table more than one module needs lands in `:unicode`
 * so it exists once - see ADR-0003. The generator knows about table *sets*: which files feed one, how it
 * is encoded, which conformance data belongs with it. It knows nothing about NFC, UTS-46 or UTS-39.
 */
/** One case in every this many is compiled into the cross-platform bidi suite. */
private const val BIDI_SAMPLE = 8

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: ucd-generator <ucd-directory> <repository-root> [--verify]")
        exitProcess(2)
    }
    val ucdDirectory = File(args[0])
    val root = File(args[1])
    val verifying = args.contains("--verify")

    val source = UcdSource(ucdDirectory)
    source.verifyChecksums()
    println("ucd-generator: Unicode ${source.version}, checksums verified")

    val unicodeData = source.readUnicodeData()
    val normalization = normalizationTables(unicodeData, source.readCompositionExclusions())
    val bidiClasses = rangeTable("bidi-classes", Family.Idna, source.readBidiClasses())
    val idnaMappings = rangeTable("idna-mappings", Family.Idna, source.readIdnaMappings())
    val joiningTypes = rangeTable("joining-types", Family.Idna, source.readJoiningTypes())
    val marks = Table("general-category-marks", Family.Idna, unicodeData.marks.sorted().associate { hex(it) to "" })
    val confusables = Table(
        "confusable-mappings",
        Family.Confusables,
        source.readConfusables().toSortedMap().entries.associate { (codePoint, prototype) ->
            hex(codePoint) to ">" + prototype.joinToString(" ") { hex(it) }
        },
    )
    val defaultIgnorable = rangeTable("default-ignorable", Family.Confusables, source.readCoreProperty("Default_Ignorable_Code_Point"))
    val brackets = rangeTable("bidi-brackets", Family.Idna, source.readBidiBrackets())
    val mirroring = rangeTable("bidi-mirroring", Family.Idna, source.readBidiMirroring())
    val tables = normalization + listOf(bidiClasses, idnaMappings, joiningTypes, marks, confusables, defaultIgnorable, brackets, mirroring)

    val baselineDirectory = File(ucdDirectory, "baseline")
    val diffs = tables.map { table ->
        val baselineFile = File(baselineDirectory, "${table.name}.txt")
        val baseline = if (baselineFile.isFile) Table.decode(table.name, table.family, baselineFile.readText().trim()) else null
        TableDiff.between(baseline, table)
    }

    println("ucd-generator: material-change report")
    for (diff in diffs) println("  ${diff.report()}")

    val violations = diffs.filter { it.isMaterialViolation }
    if (violations.isNotEmpty()) {
        System.err.println(
            "\nucd-generator: REFUSING to regenerate.\n" +
                violations.joinToString("\n") { "  ${it.report()}" } +
                "\n\nAn existing entry changed in a table whose upstream guarantee forbids it. The suite's\n" +
                "delta packaging and its byte-stability promise both assume additions only here, so this is\n" +
                "investigated rather than accepted: a real upstream change of this kind needs a new policy,\n" +
                "not a regenerated table.",
        )
        exitProcess(1)
    }

    val outputs = buildMap {
        for (table in tables) put(File(baselineDirectory, "${table.name}.txt"), table.encode() + "\n")

        put(
            generated(root, "unicode", "commonMain", "NormalizationTables.kt"),
            Emit.encodedObject(
                source.version, "io.github.aughtone.normalize.unicode.generated", "NormalizationTables",
                """
                |/**
                | * The frozen normalization data for Unicode ${source.version}.
                | *
                | * Each value is an encoded table: entries joined by `;`, each a lowercase hexadecimal code point
                | * followed by `>` and a space-separated mapping, `:` and a combining class, or nothing at all for
                | * a membership table.
                | */
                """.trimMargin(),
                normalization.map { it.name to it.encode() },
            ),
        )
        put(
            generated(root, "unicode", "commonMain", "CharacterProperties.kt"),
            Emit.encodedObject(
                source.version, "io.github.aughtone.normalize.unicode.generated", "CharacterProperties",
                """
                |/**
                | * Character properties more than one module needs, which is why they live here rather than in the
                | * module that happens to use them first - see ADR-0003. Ranges are `first-last:value`, joined by `;`.
                | */
                """.trimMargin(),
                listOf(
                    bidiClasses.name to bidiClasses.encode(),
                    brackets.name to brackets.encode(),
                    mirroring.name to mirroring.encode(),
                ),
            ),
        )
        put(
            generated(root, "unicode", "commonTest", "NormalizationConformanceData.kt"),
            Emit.encodedObject(
                source.version, "io.github.aughtone.normalize.unicode.generated", "NormalizationConformanceData",
                """
                |/**
                | * `NormalizationTest.txt` for Unicode ${source.version}: five spellings of one text per case,
                | * separated by `|` and joined by `;`. Test-only, never part of a published artifact.
                | */
                """.trimMargin(),
                listOf("cases" to encodeNormalizationCases(source.readConformanceCases())),
            ),
        )

        put(
            generated(root, "ubilibet", "commonMain", "IdnaTables.kt"),
            Emit.encodedObject(
                source.version, "io.github.aughtone.normalize.ubilibet.generated", "IdnaTables",
                """
                |/**
                | * The frozen UTS-46 data for Unicode ${source.version}: every code point's IDNA status and mapping,
                | * the joining types the ContextJ rules need, and the code points whose general category is Mark,
                | * which a label may not begin with. Ranges are `first-last:value`, joined by `;`; a mapped entry
                | * carries `>` and its space-separated replacement.
                | */
                """.trimMargin(),
                listOf(
                    idnaMappings.name to idnaMappings.encode(),
                    joiningTypes.name to joiningTypes.encode(),
                    marks.name to marks.encode(),
                ),
            ),
        )
        put(
            generated(root, "confusables", "commonMain", "ConfusableTables.kt"),
            Emit.encodedObject(
                source.version, "io.github.aughtone.normalize.confusables.generated", "ConfusableTables",
                """
                |/**
                | * The frozen UTS-39 data for Unicode ${source.version}: each confusable character and the prototype
                | * it maps to, and the default-ignorable code points a skeleton removes. Mappings are
                | * `codepoint>prototype`, ranges are `first-last:value`, joined by `;`.
                | */
                """.trimMargin(),
                listOf(
                    confusables.name to confusables.encode(),
                    defaultIgnorable.name to defaultIgnorable.encode(),
                ),
            ),
        )
        put(
            generated(root, "confusables", "commonTest", "BidiConformanceData.kt"),
            Emit.encodedObject(
                source.version, "io.github.aughtone.normalize.confusables.generated", "BidiConformanceData",
                """
                |/**
                | * A deterministic sample of `BidiCharacterTest.txt` for Unicode ${source.version} - every
                | * ${BIDI_SAMPLE}th case, joined by `;`, each row verbatim from the file.
                | *
                | * The whole file is 94,000 cases and several megabytes once compiled, which is too much to ship
                | * to every target's test binary. The JVM suite reads the file itself and runs all of it; this
                | * sample is what proves the other targets agree. Test-only.
                | */
                """.trimMargin(),
                listOf("rows" to source.readBidiConformanceRows(BIDI_SAMPLE).joinToString(";")),
            ),
        )
        put(
            generated(root, "ubilibet", "commonTest", "IdnaConformanceData.kt"),
            Emit.encodedObject(
                source.version, "io.github.aughtone.normalize.ubilibet.generated", "IdnaConformanceData",
                """
                |/**
                | * `IdnaTestV2.txt` for Unicode ${source.version}, resolved: one row per case, joined by `;`, with
                | * three columns separated by `|` - the source, the expected nontransitional ToASCII result, and the
                | * status codes that result carries, space separated and empty when the case must succeed.
                | *
                | * Inherited blank columns and the file's `\uXXXX` escapes are already resolved here, so a test
                | * compares values rather than notation. Text is space-separated hexadecimal code points. Test-only.
                | */
                """.trimMargin(),
                listOf("rows" to encodeIdnaRows(source.readIdnaConformanceRows())),
            ),
        )
    }

    if (verifying) {
        val stale = outputs.filter { (file, expected) -> !file.isFile || file.readText() != expected }.keys
        if (stale.isEmpty()) {
            println("ucd-generator: checked-in tables match the pinned data")
            return
        }
        System.err.println(
            "\nucd-generator: the checked-in tables are not what the pinned data produces:\n" +
                stale.joinToString("\n") { "  ${it.relativeTo(root)}" } +
                "\n\nEither a generated file was edited by hand, or the pinned data moved without a\n" +
                "regeneration. Run: ./gradlew :tools:ucd-generator:generateUnicodeTables",
        )
        exitProcess(1)
    }

    for ((file, content) in outputs) {
        file.parentFile.mkdirs()
        file.writeText(content)
        println("ucd-generator: wrote ${file.relativeTo(root)} (${content.length} chars)")
    }
}

/** Where a generated file lives: always a `generated` package inside the module that carries it. */
private fun generated(root: File, module: String, sourceSet: String, fileName: String): File =
    File(root, "$module/src/$sourceSet/kotlin/io/github/aughtone/normalize/$module/generated/$fileName")

private fun encodeNormalizationCases(cases: List<ConformanceCase>): String =
    cases.joinToString(";") { case ->
        case.columns.joinToString("|") { column -> column.joinToString(" ") { hex(it) } }
    }

/**
 * Encode IDNA conformance rows as `source|toAsciiN|status`, joined by `;`.
 *
 * Three things the file's own format requires, done here so the test compares values rather than
 * notation: characters escaped as `\uXXXX` or `\x{XXXX}` are unescaped, blank columns are resolved to
 * what they inherit (toUnicode from source, toAsciiN from toUnicode, its status from toUnicode's), and
 * the literal `""` is read as the empty string. Text is emitted as hexadecimal code points so no
 * escaping question survives into the generated source - these rows are deliberately awful input, which
 * is the point of them.
 */
private fun encodeIdnaRows(rows: List<List<String>>): String =
    rows.mapNotNull { row ->
        if (row.size < 5) return@mapNotNull null
        val source = unescape(row[0])
        val toUnicode = inherit(row[1], source)
        val toAscii = inherit(row[3], toUnicode)
        val status = if (row[4].isBlank()) statusOf(row[2]) else statusOf(row[4])
        listOf(
            source.codePoints().toArray().joinToString(" ") { hex(it) },
            toAscii.codePoints().toArray().joinToString(" ") { hex(it) },
            status,
        ).joinToString("|")
    }.joinToString(";")

/** A blank column inherits the previous value; an explicit `""` is the empty string. */
private fun inherit(column: String, inherited: String): String = when {
    column.isBlank() -> inherited
    column.trim() == "\"\"" -> ""
    else -> unescape(column)
}

/** Status codes without their brackets, comma-free, so the test can split on a single character. */
private fun statusOf(column: String): String =
    column.trim().removePrefix("[").removeSuffix("]").split(',')
        .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

/** Resolve the `\uXXXX` and `\x{XXXX}` escapes the conformance file uses for confusing characters. */
private fun unescape(raw: String): String {
    val text = raw.trim()
    if (!text.contains('\\')) return text
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character != '\\' || index + 1 >= text.length) {
            out.append(character)
            index++
            continue
        }
        when (text[index + 1]) {
            'u' -> {
                out.append(text.substring(index + 2, index + 6).toInt(16).toChar())
                index += 6
            }

            'x' -> {
                val close = text.indexOf('}', index)
                out.appendCodePoint(text.substring(index + 3, close).toInt(16))
                index = close + 1
            }

            else -> {
                out.append(character)
                index++
            }
        }
    }
    return out.toString()
}
