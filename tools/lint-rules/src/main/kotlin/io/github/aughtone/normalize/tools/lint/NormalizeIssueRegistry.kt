package io.github.aughtone.normalize.tools.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** The lint checks bundled into the `:unicode` Android artifact. */
class NormalizeIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(TextPolicyRuleOrderDetector.ISSUE)
    override val api: Int = CURRENT_API
    override val vendor: Vendor = Vendor(vendorName = "Aught One Normalize", identifier = "io.github.aughtone.normalize")
}
