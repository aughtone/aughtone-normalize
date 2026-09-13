package io.github.aughtone.normalize.tools.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class TextPolicyRuleOrderDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = TextPolicyRuleOrderDetector()

    override fun getIssues(): List<Issue> = listOf(TextPolicyRuleOrderDetector.ISSUE)

    // The detector reads Kotlin source only, so the tests run where no Android SDK is installed.
    override fun lint(): TestLintTask = super.lint().allowMissingSdk()

    /** The shape of the `:unicode` builder API, enough for lint to resolve calls against. */
    private val stubs: TestFile = kotlin(
        """
        package io.github.aughtone.normalize.unicode

        enum class UnicodeRelease { U17 }

        class TextPolicy {
            companion object {
                operator fun invoke(configure: AsciiTextPolicyBuilder.() -> Unit): TextPolicy = TextPolicy()
                operator fun invoke(release: UnicodeRelease, configure: UnicodeTextPolicyBuilder.() -> Unit): TextPolicy = TextPolicy()
            }
        }

        sealed class TextRules {
            fun stripControl() {}
            fun trim() {}
            fun collapseSpace() {}
            fun removeSpace() {}
            fun lowercase() {}
            fun uppercase() {}
        }

        class AsciiRules : TextRules()

        class UnicodeRules : TextRules() {
            fun casefold() {}
            fun nfc() {}
            fun nfd() {}
            fun nfkc() {}
            fun nfkd() {}
        }

        open class AsciiTextPolicyBuilder {
            fun ascii(configure: AsciiRules.() -> Unit) {}
            fun nonEmpty() {}
        }

        class UnicodeTextPolicyBuilder : AsciiTextPolicyBuilder() {
            fun unicode(configure: UnicodeRules.() -> Unit) {}
        }
        """,
    ).indented()

    fun testRulesInApplicationOrderAreNotReported() {
        lint().files(
            stubs,
            kotlin(
                """
                package test

                import io.github.aughtone.normalize.unicode.TextPolicy
                import io.github.aughtone.normalize.unicode.UnicodeRelease

                val ascii = TextPolicy { ascii { trim(); lowercase() } }
                val mixed = TextPolicy(UnicodeRelease.U17) {
                    ascii { trim() }
                    unicode { casefold(); nfc() }
                    nonEmpty()
                }
                """,
            ).indented(),
        ).run().expectClean()
    }

    fun testRulesOutOfOrderAreReportedWithTheApplicationOrder() {
        lint().files(
            stubs,
            kotlin(
                """
                package test

                import io.github.aughtone.normalize.unicode.TextPolicy

                val policy = TextPolicy { ascii { lowercase(); trim() } }
                """,
            ).indented(),
        ).run().expect(
            """
            src/test/test.kt:5: Warning: These text rules run as trim(), lowercase(), not in the order they are written [TextPolicyRuleOrder]
            val policy = TextPolicy { ascii { lowercase(); trim() } }
                         ~~~~~~~~~~
            0 errors, 1 warnings
            """,
        )
    }

    fun testOrderIsReadAcrossAsciiAndUnicodeBlocks() {
        lint().files(
            stubs,
            kotlin(
                """
                package test

                import io.github.aughtone.normalize.unicode.TextPolicy
                import io.github.aughtone.normalize.unicode.UnicodeRelease

                val policy = TextPolicy(UnicodeRelease.U17) {
                    unicode { nfc() }
                    ascii { trim() }
                }
                """,
            ).indented(),
        ).run().expectWarningCount(1)
    }

    fun testATopLevelRuleCountsInTheOrder() {
        lint().files(
            stubs,
            kotlin(
                """
                package test

                import io.github.aughtone.normalize.unicode.TextPolicy

                val policy = TextPolicy {
                    nonEmpty()
                    ascii { trim() }
                }
                """,
            ).indented(),
        ).run().expectWarningCount(1)
    }

    fun testUnrelatedFunctionsWithTheSameNamesAreIgnored() {
        lint().files(
            stubs,
            kotlin(
                """
                package test

                class Other {
                    fun lowercase() {}
                    fun trim() {}
                    operator fun invoke(block: Other.() -> Unit) {}
                }

                fun use(other: Other) {
                    other { lowercase(); trim() }
                }
                """,
            ).indented(),
        ).run().expectClean()
    }

    fun testANestedPolicyIsReportedOnItsOwn() {
        lint().files(
            stubs,
            kotlin(
                """
                package test

                import io.github.aughtone.normalize.unicode.TextPolicy

                val outer = TextPolicy {
                    ascii { trim() }
                    val inner = TextPolicy { ascii { lowercase(); trim() } }
                    ascii { lowercase() }
                }
                """,
            ).indented(),
        ).run().expectWarningCount(1)
    }
}
