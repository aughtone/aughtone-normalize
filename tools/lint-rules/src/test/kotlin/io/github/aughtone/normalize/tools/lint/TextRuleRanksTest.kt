package io.github.aughtone.normalize.tools.lint

import io.github.aughtone.normalize.unicode.AsciiTextPolicyBuilder
import io.github.aughtone.normalize.unicode.TextPolicy
import io.github.aughtone.normalize.unicode.TextPolicyError
import io.github.aughtone.normalize.unicode.TextRules
import io.github.aughtone.normalize.unicode.UnicodeRelease
import io.github.aughtone.normalize.unicode.UnicodeRules
import io.github.aughtone.normalize.unicode.UnicodeTextPolicyBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The detector's rank table against the runtime it describes.
 *
 * The runtime's ranks are internal to `:unicode`, so this checks them through what they decide: for every
 * pair of rules, written in both orders, a real policy warns exactly when the table says the pair is out
 * of order, and refuses exactly when the table gives both the same rank.
 */
class TextRuleRanksTest {

    private val rules: Map<String, UnicodeTextPolicyBuilder.() -> Unit> = mapOf(
        "stripControl" to { unicode { stripControl() } },
        "trim" to { unicode { trim() } },
        "collapseSpace" to { unicode { collapseSpace() } },
        "removeSpace" to { unicode { removeSpace() } },
        "lowercase" to { unicode { lowercase() } },
        "uppercase" to { unicode { uppercase() } },
        "casefold" to { unicode { casefold() } },
        "nfc" to { unicode { nfc() } },
        "nfd" to { unicode { nfd() } },
        "nfkc" to { unicode { nfkc() } },
        "nfkd" to { unicode { nfkd() } },
        "nonEmpty" to { nonEmpty() },
    )

    private lateinit var handler: (TextPolicy, io.github.aughtone.normalize.unicode.TextPolicyWarning) -> Unit

    @Before
    fun silenceWarnings() {
        handler = TextPolicy.warningHandler
        TextPolicy.warningHandler = { _, _ -> }
    }

    @After
    fun restoreWarnings() {
        TextPolicy.warningHandler = handler
    }

    @Test
    fun theTableNamesEveryRuleTheBuildersOffer() {
        val offered = (UnicodeRules::class.java.methods.toList() + AsciiTextPolicyBuilder::class.java.methods.toList())
            .filter { it.declaringClass in setOf(TextRules::class.java, UnicodeRules::class.java) || it.name == "nonEmpty" }
            .map { it.name }
            .filterNot { '$' in it }
            .toSet()
        assertEquals(offered, TextRuleRanks.ranks.keys)
        assertEquals(offered, rules.keys)
    }

    @Test
    fun everyPairWarnsOrRefusesExactlyAsTheTableSays() {
        for ((first, writeFirst) in rules) {
            for ((second, writeSecond) in rules) {
                if (first == second) continue
                val firstRank = TextRuleRanks.ranks.getValue(first)
                val secondRank = TextRuleRanks.ranks.getValue(second)
                val built = try {
                    TextPolicy(UnicodeRelease.U17) {
                        writeFirst()
                        writeSecond()
                    }
                } catch (refused: TextPolicyError.ContradictoryRules) {
                    if (firstRank != secondRank) fail("$first, $second: the runtime refuses a pair the table ranks apart")
                    continue
                }
                if (firstRank == secondRank) fail("$first, $second: the table ranks them together, the runtime allows both")
                assertEquals("$first then $second", firstRank > secondRank, built.warnings.isNotEmpty())
            }
        }
    }
}
