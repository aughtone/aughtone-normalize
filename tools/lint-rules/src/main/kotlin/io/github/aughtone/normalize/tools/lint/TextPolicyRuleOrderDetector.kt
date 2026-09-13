package io.github.aughtone.normalize.tools.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Reports a `TextPolicy { }` builder whose rules are written in a different order from the one they run in.
 *
 * The builder sorts rules by a fixed rank, so the policy is correct whatever order they are written in,
 * and it reports the mismatch at runtime. This shows the same thing in the editor, so a reader of the
 * code is not misled about what runs first.
 */
class TextPolicyRuleOrderDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> = listOf("invoke")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (!context.evaluator.isMemberInClass(method, POLICY_COMPANION)) return
        // Found by type rather than position: with named arguments the builder need not be written last.
        val body = node.valueArguments.filterIsInstance<ULambdaExpression>().singleOrNull() ?: return

        val written = mutableListOf<String>()
        body.body.accept(
            object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    val called = node.resolve() ?: return false
                    // A policy built inside another builder is reported on its own call, not merged in here.
                    if (context.evaluator.isMemberInClass(called, POLICY_COMPANION)) return true
                    val name = called.name
                    if (name in TextRuleRanks.ranks && called.containingClass?.qualifiedName in RULE_OWNERS) {
                        written += name
                    }
                    return false
                }
            },
        )

        val applied = written.sortedBy { TextRuleRanks.ranks.getValue(it) }
        if (applied == written) return

        context.report(
            ISSUE,
            node,
            context.getCallLocation(node, includeReceiver = false, includeArguments = false),
            "These text rules run as ${applied.joinToString(", ") { "`$it()`" }}, not in the order they are written",
        )
    }

    companion object {
        private const val PACKAGE = "io.github.aughtone.normalize.unicode"
        private const val POLICY_COMPANION = "$PACKAGE.TextPolicy.Companion"

        /** The classes that declare rule functions. Anything else with the same names is not a text rule. */
        private val RULE_OWNERS = setOf(
            "$PACKAGE.TextRules",
            "$PACKAGE.AsciiRules",
            "$PACKAGE.UnicodeRules",
            "$PACKAGE.AsciiTextPolicyBuilder",
            "$PACKAGE.UnicodeTextPolicyBuilder",
        )

        val ISSUE: Issue = Issue.create(
            id = "TextPolicyRuleOrder",
            briefDescription = "Text rules written out of application order",
            explanation = """
                A `TextPolicy` applies its rules in a fixed order - strip control characters, trim, \
                collapse or remove space, change case, normalize, then check non-empty - whatever order \
                they are written in. The policy is still correct, but code that lists them differently \
                reads as though they run that way. Write them in the order they run.
                """,
            category = Category.CORRECTNESS,
            priority = 5,
            severity = Severity.WARNING,
            implementation = Implementation(TextPolicyRuleOrderDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}

/**
 * Each rule function's rank in the application order. A copy of the runtime's frozen ranks, which are
 * internal to `:unicode`; the detector's tests build real policies against `:unicode` and fail if this
 * table and the runtime disagree about any pair of rules.
 */
object TextRuleRanks {
    val ranks: Map<String, Int> = mapOf(
        "stripControl" to 0,
        "trim" to 1,
        "collapseSpace" to 2,
        "removeSpace" to 2,
        "lowercase" to 3,
        "uppercase" to 3,
        "casefold" to 3,
        "nfc" to 4,
        "nfd" to 4,
        "nfkc" to 4,
        "nfkd" to 4,
        "nonEmpty" to 5,
    )
}
