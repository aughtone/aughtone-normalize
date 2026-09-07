package io.github.aughtone.normalize.email

import io.github.aughtone.normalize.common.Normalized

/**
 * The canonical string plus the policy identity that produced it. Store all three fields beside any
 * hash / token derived from [canonical], so the value can be reproduced under the exact epoch. There is
 * no Unicode-version field: this canonical form has no Unicode dependency, so [policyVersion] alone
 * identifies the rules completely.
 */
data class NormalizedEmail(
    override val canonical: String,
    override val policyId: String,
    override val policyVersion: Int,
) : Normalized
