package io.github.aughtone.normalize.common

/** The canonical string plus the identity of the policy that produced it, shared by every normalizer's result. */
interface Normalized {
    val canonical: String
    val policyId: String
    val policyVersion: Int
}
