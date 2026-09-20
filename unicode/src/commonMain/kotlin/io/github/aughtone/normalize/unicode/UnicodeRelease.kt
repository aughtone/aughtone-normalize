package io.github.aughtone.normalize.unicode

/**
 * A Unicode release this build carries frozen data for.
 *
 * A text policy that uses any Unicode data names exactly one release, once, and every Unicode rule in it
 * runs against that release's tables - `text.u17:space.trimmed:case.folded` folds with Unicode 17's data on every
 * platform, in every build, forever. A later release is a new member here and a new identity
 * (`text.u18:…`), never a change to what an existing one produces.
 *
 * @property segment How the release appears in a policy id: `u<major>`, with the minor appended only
 * when it is not zero. **Frozen**: it is part of every stored id that names this release.
 */
enum class UnicodeRelease(val segment: String) {

    /** Unicode 17.0.0. */
    U17("u17"),
    ;

    companion object {
        /** The release named by an id segment such as `u17`, or `null` if this build carries none. */
        internal fun ofSegment(segment: String): UnicodeRelease? = entries.firstOrNull { it.segment == segment }
    }
}
