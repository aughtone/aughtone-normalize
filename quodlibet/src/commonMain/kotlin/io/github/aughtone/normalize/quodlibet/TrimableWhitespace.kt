package io.github.aughtone.normalize.quodlibet

/**
 * A character that may sit around a value without being part of it, and is therefore trimmed from it.
 *
 * **One predicate, not two.** Whitespace and the invisible format characters are the same problem wearing
 * different Unicode properties: they arrive by the same accidents - a value copied out of a formatted
 * page, a file read with a byte-order mark - and none of them was typed by anybody. Splitting them into
 * "whitespace" and "invisible" would invite the question of which list a new character joins, when the
 * only question that matters is whether it is part of the value. None of these is.
 *
 * **The list is written out, and must stay written out.** `Char.isWhitespace()` reads a Unicode property,
 * and the policies that use this promise the opposite: only operations that cannot drift or expire when
 * Unicode ships a release. A frozen list keeps that promise - it is data in the same sense a corpus is,
 * and it moves only with a version. Do not "simplify" it into a property lookup; `TrimableWhitespaceTest`
 * pins every member so that change fails.
 *
 * What is in it, and why:
 *
 * - `U+0009`..`U+000D`, `U+0020` - the ASCII set, which is all that used to be trimmed.
 * - `U+0085`, `U+00A0`, `U+1680`, `U+2000`..`U+200A`, `U+2028`, `U+2029`, `U+202F`, `U+205F`, `U+3000` -
 *   the rest of Unicode's whitespace. A no-break space is what a value copied out of a formatted page
 *   carries; the others come from typesetting and from other keyboards. Trimming a space but not a
 *   no-break space gives two tokens for one value, silently.
 * - `U+200B`, `U+2060`, `U+FEFF` - zero-width space, word joiner, byte-order mark. Invisible in every
 *   tool a person would reach for to look for them, and a leading `U+FEFF` from a file read with a BOM is
 *   the most common of all of these.
 *
 * **Only the ends.** A value's interior is its own business: a quoted email local part may legitimately
 * contain a space, and nothing here touches it.
 */
internal fun Char.isTrimableWhitespace(): Boolean = when (code) {
    in 0x0009..0x000D, 0x0020 -> true
    0x0085, 0x00A0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    in 0x2000..0x200A -> true
    0x200B, 0x2060, 0xFEFF -> true
    else -> false
}

/** This string with [isTrimableWhitespace] removed from both ends, and its interior untouched. */
internal fun String.trimWhitespace(): String {
    var start = 0
    var end = length
    while (start < end && this[start].isTrimableWhitespace()) start++
    while (end > start && this[end - 1].isTrimableWhitespace()) end--
    return substring(start, end)
}
