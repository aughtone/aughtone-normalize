package io.github.aughtone.normalize.email

/**
 * Why a value could not be normalized under the named policy.
 *
 * A Throwable subclass, because aughtone-types `Outcome.Failure` carries a `Throwable`: it rides on
 * `Outcome.Failure(exception)`, and `(o.exception as? EmailNormalizationError)` recovers the typed reason.
 * The messages are **value-free** — they never echo the input, so a rejected address cannot leak into a
 * log. Rejection is routine per-record flow, not an exceptional event.
 */
sealed class EmailNormalizationError(message: String) : Exception(message) {
    /** No `@` in the value. */
    class MissingAtSign : EmailNormalizationError("email: missing '@'")

    /** The local part (before `@`) is empty, before or after normalization. */
    class EmptyLocalPart : EmailNormalizationError("email: empty local part")

    /** The domain (after `@`) is empty. */
    class EmptyDomain : EmailNormalizationError("email: empty domain")

    /**
     * The value contains an unpaired UTF-16 surrogate — a broken half-character with no valid UTF-8
     * form. Encoding it to bytes is not consistent across platforms, so it is rejected rather than
     * hashed. A well-formed email address never contains one.
     */
    class UnpairedSurrogate : EmailNormalizationError("email: unpaired surrogate")
}
