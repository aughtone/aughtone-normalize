package io.github.aughtone.normalize.tools.ucd

/**
 * Turning encoded tables into Kotlin source.
 *
 * Kotlin source rather than a packaged resource, because a resource needs a per-target loader and this
 * data has to be readable on JVM, Android, iOS, JS, wasm and native alike. The cost is the JVM's limit
 * on a single string constant - 65535 bytes of modified UTF-8 - so every table is emitted as chunks
 * well inside that and joined once, lazily, on first use.
 *
 * Everything here is text a module decodes for itself. The generator knows how a table is *encoded*
 * and nothing about what it means, which is what lets one tool serve normalization, IDNA and
 * confusables without learning any of them.
 */
object Emit {

    /** Chunk size in characters. The data is ASCII, so a character is a byte, and this is half the cap. */
    private const val CHUNK = 30_000

    private const val HEADER = """// GENERATED FILE - DO NOT EDIT.
//
// Produced by the :tools:ucd-generator module from the pinned Unicode data in ucd/%s.
// Regenerate with: ./gradlew :tools:ucd-generator:generateUnicodeTables
//
// Editing this file by hand changes canonical bytes with nothing in the diff to explain why, and
// `./gradlew check` fails when it no longer matches what the pinned data produces.
"""

    /**
     * An object of encoded strings, one per named entry.
     *
     * [entries] maps a property name to its encoded content. Each becomes a lazily assembled `val`
     * backed by chunk constants, so a target that never touches one never pays for it and dead-code
     * elimination can drop it entirely.
     */
    fun encodedObject(
        version: String,
        packageName: String,
        objectName: String,
        kdoc: String,
        entries: List<Pair<String, String>>,
    ): String = buildString {
        append(HEADER.format(version))
        append("\npackage $packageName\n\n")
        append(kdoc.trimEnd())
        append("\ninternal object $objectName {\n\n")
        append("    /** The Unicode release this data was frozen from. */\n")
        append("    const val UNICODE_VERSION: String = \"$version\"\n")
        for ((name, encoded) in entries) {
            append("\n")
            append(property(name, encoded))
        }
        append("}\n")
    }

    /** One encoded string as a lazily joined property plus its chunk constants. */
    private fun property(name: String, encoded: String): String {
        val identifier = name.split('-').mapIndexed { index, part ->
            if (index == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
        val chunks = encoded.chunked(CHUNK).ifEmpty { listOf("") }

        return buildString {
            append("    /** ${chunks.size} chunk(s), ${encoded.length} characters. */\n")
            append("    val $identifier: String by lazy(LazyThreadSafetyMode.PUBLICATION) {\n")
            append("        buildString(${encoded.length}) {\n")
            for (index in chunks.indices) append("            append(${identifier.uppercase()}_$index)\n")
            append("        }\n")
            append("    }\n")
            for ((index, chunk) in chunks.withIndex()) {
                append("\n    private const val ${identifier.uppercase()}_$index: String =\n")
                append("        \"${chunk.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")}\"\n")
            }
        }
    }
}
