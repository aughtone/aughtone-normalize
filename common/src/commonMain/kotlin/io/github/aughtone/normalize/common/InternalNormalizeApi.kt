package io.github.aughtone.normalize.common

/**
 * Marks API that exists so the suite's own modules can share frozen data, not for callers to use.
 *
 * Character properties are shared this way: `:ubilibet` needs combining classes and bidi classes that
 * `:unicode` carries, and one module reaching into another's data beats two modules shipping the same
 * table and disagreeing about it. Making that access public is unavoidable across a module boundary;
 * making it obviously internal is not, hence this annotation.
 *
 * Nothing marked with it carries the suite's stability promise. It may change shape in any release,
 * where a policy's canonical output may not. A caller who opts in is choosing to track this repository
 * rather than its published contract.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal to the normalization suite: shared between its modules, with no stability promise.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalNormalizeApi
