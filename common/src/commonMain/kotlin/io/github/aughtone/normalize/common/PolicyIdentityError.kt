package io.github.aughtone.normalize.common

/**
 * Why a policy id could not be parsed, ordered, or resolved to a policy.
 *
 * **These messages deliberately quote the id.** Every other error type in this suite is value-free,
 * because it describes a value being normalized - an address, a card number - that must never reach a
 * log. A policy id is not that: it is public identity, it is written in configuration files and stored
 * in plain columns, and a resolution failure that will not say which link it could not resolve is not
 * debuggable. The distinction is deliberate; do not "fix" it by stripping the id.
 *
 * Match on the subclass. The message text is not API.
 */
sealed class PolicyIdentityError(message: String) : Exception(message) {

    /** A link in the id is not a well-formed link name - uppercase, an empty segment, stray punctuation. */
    class MalformedLink(val link: String) : PolicyIdentityError("policy id: malformed link '$link'")

    /** The id was empty, or consisted only of separators. */
    class EmptyId : PolicyIdentityError("policy id: empty")

    /** A chain must start with exactly one base link, and this one has none. */
    class MissingBase(val id: String) : PolicyIdentityError("policy id '$id': no base link")

    /** A chain must start with exactly one base link, and this one names more than one. */
    class MultipleBases(val id: String) : PolicyIdentityError("policy id '$id': more than one base link")

    /**
     * The links are all known but appear in the wrong order. A chain runs base, parameters,
     * relaxations, then steps in phase order, and is never silently reordered: one policy has exactly
     * one valid id.
     */
    class OutOfOrder(val id: String, val link: String) : PolicyIdentityError("policy id '$id': link '$link' is out of order")

    /** The same link appears twice. */
    class DuplicateLink(val id: String, val link: String) : PolicyIdentityError("policy id '$id': duplicate link '$link'")

    /** No resolver knows this link, so the chain cannot be trusted to mean what it appears to mean. */
    class UnknownLink(val id: String, val link: String) : PolicyIdentityError("policy id '$id': no module publishes link '$link'")

    /** The chain is well-formed and every link is known, but no module publishes this exact policy. */
    class UnknownPolicy(val id: String) : PolicyIdentityError("policy id '$id': no module publishes this policy")

    /**
     * The id is published, at a different version. The id and version together identify the bytes, so a
     * stored `(id, 1)` must never resolve to version 2 - the rules changed, which is what a version
     * bump means.
     */
    class VersionMismatch(val id: String, val requested: Int, val available: List<Int>) :
        PolicyIdentityError("policy id '$id': version $requested is not published (published: ${available.joinToString()})")
}
