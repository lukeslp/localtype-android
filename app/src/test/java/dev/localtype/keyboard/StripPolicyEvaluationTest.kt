package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [CorpusStripRanker] on hand-made lines, and, when a corpus is configured (`-Dlocaltype.corpus`, as for
 * CorpusEvaluationTest), the dev comparison of the personal-slot policies in docs/next-word-quality-2026-11-12.md.
 * The corpus run asserts nothing about a policy's figures; it writes `build/reports/corpus-eval/strip-<split>.txt`.
 */
class StripPolicyEvaluationTest {
    private fun strip(policy: CorpusStripRanker.Policy) = CorpusStripRanker("t", "", policy)

    private fun type(ranker: CorpusStripRanker, sentence: String) {
        val words = sentence.split(' ')
        for (i in words.indices) {
            val context = words.take(i).joinToString("") { "$it " }
            ranker.rank(context, "", 3)
            ranker.typed(context, words[i])
        }
    }

    @Test
    fun `with nothing personal the strip is Chime's first three`() {
        val chime = File("src/main/assets/${NeuralAsset.FILE_NAME}").inputStream().use { CifgModel.load(it) }
        val ranker = strip(CorpusStripRanker.NONE)
        for (context in listOf("", "i will see you ", "please close the ")) {
            assertEquals(chime.newSession().predict(context, 3).map { it.word }, ranker.rank(context, "", 3))
        }
    }

    @Test
    fun `a follower typed twice after one word takes the last slot as shipped, and each policy decides for itself`() {
        // "zebra" is no model's guess after "the"; typed there twice it is the person's own follower of one word.
        fun after(policy: CorpusStripRanker.Policy, sentences: List<String>): List<String> {
            val ranker = strip(policy)
            sentences.forEach { type(ranker, it) }
            ranker.rank("", "", 3)
            ranker.typed("", "see")
            ranker.rank("see ", "", 3)
            ranker.typed("see ", "the")
            return ranker.rank("see the ", "", 3)
        }
        val twice = listOf("we saw the zebra", "they fed the zebra")
        assertEquals("zebra", after(CorpusStripRanker.SHIPPED, twice).last())
        assertTrue("zebra" !in after(CorpusStripRanker.NONE, twice))
        assertTrue("zebra" !in after(CorpusStripRanker.LONGER_CONTEXT, twice))
        assertTrue("zebra" !in after(CorpusStripRanker.IN_CHIMES_TWELVE, twice))
        // Two of the three words typed after "the" so far: a habit at any share tried.
        assertEquals("zebra", after(CorpusStripRanker.share(0.25), twice).last())
        // Two among a dozen other words after "the": no longer a quarter of them.
        val diluted = twice + List(10) { "open the ${listOf("door", "window", "gate", "box", "file", "book", "bag", "car", "shop", "letter")[it]}" }
        assertEquals("zebra", after(CorpusStripRanker.SHIPPED, diluted).last())
        assertTrue("zebra" !in after(CorpusStripRanker.share(0.25), diluted))
        // Behind two own words a follower needs one use, and every policy but NONE keeps it.
        val longer = listOf("we will see the zebra")
        for (policy in listOf(CorpusStripRanker.SHIPPED, CorpusStripRanker.LONGER_CONTEXT, CorpusStripRanker.IN_CHIMES_TWELVE, CorpusStripRanker.share(0.25))) {
            assertEquals("zebra", after(policy, longer).last())
        }
    }

    /**
     * The 36 contexts of the October comparison with another keyboard (verification/editor-fixture/opportunity_comparison.py),
     * asked of Chime alone with nothing learned: where each target sits in its twelve. It prints and asserts only the count
     * of contexts, so a new model file can't fail it.
     */
    @Test
    fun `where the October comparison's targets sit in Chime's twelve`() {
        val comparison = File("../verification/editor-fixture/opportunity_comparison.py")
        assumeTrue("needs verification/editor-fixture, which the public source leaves out", comparison.isFile)
        val source = comparison.readText()
        val targets = Regex("""\("([a-z' ]+ )", "([a-z']+)"\)""").findAll(source.substringAfter("TARGETS").substringBefore("def cases"))
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(36, targets.size)
        val chime = File("src/main/assets/${NeuralAsset.FILE_NAME}").inputStream().use { CifgModel.load(it) }
        val vocabulary = (0 until chime.vocabSize).map(chime::token).toSet()
        val ranks = targets.map { (context, word) ->
            val twelve = chime.newSession().predict(context, CorpusStripRanker.NEURAL_WORDS).map { it.word }
            Triple(context, word, twelve.indexOf(word))
        }
        fun count(test: (Int) -> Boolean) = ranks.count { test(it.third) }
        println("October targets in Chime's twelve: ${count { it in 0..2 }} in the first three, ${count { it in 3..11 }} at four to twelve, " +
            "${count { it < 0 }} absent (${ranks.count { it.third < 0 && it.second !in vocabulary }} not in its vocabulary)")
        for ((context, word, rank) in ranks) println("  %-40s %-14s %s".format(context, word, if (rank < 0) "-" else (rank + 1).toString()))
    }

    @Test
    fun `the personal-slot policies on a corpus split`() {
        val settings = CorpusEvaluation.Settings.fromSystem()
        assumeTrue("set -Dlocaltype.corpus=<model-data>/corpus or LOCALTYPE_CORPUS", settings != null)
        settings!!
        require(settings.split == "dev") { "the strip policies are a dev comparison; the test split is spent" }
        val report = StringBuilder()
        // A ranker learns as it goes, so each column gets rankers that have learned nothing.
        for (column in CorpusEvaluation.columns(settings).filter { it.name != "pooled" }) {
            val result = CorpusEvaluation.evaluate(listOf(column), CorpusStripRanker.candidates(), settings).results.single()
            report.appendLine("${column.label}: ${column.lines.size} lines")
            for (score in result.scores) {
                val against = result.interval("strip-shipped", score.ranker.name)
                val versus = against?.let { (savings, hit3) ->
                    "  vs shipped: savings %+.2f [%+.2f, %+.2f]".format(savings.difference * 100, savings.low * 100, savings.high * 100) +
                        hit3?.let { ", hit@3 %+.2f [%+.2f, %+.2f]".format(it.difference * 100, it.low * 100, it.high * 100) }.orEmpty()
                }.orEmpty()
                report.appendLine("  %-14s savings %.2f%%  next hit@3 %.2f%%%s".format(score.ranker.name, score.savings * 100, score.nextHit3 * 100, versus))
            }
        }
        File("build/reports/corpus-eval").mkdirs()
        File("build/reports/corpus-eval/strip-${settings.split}.txt").writeText(report.toString())
        println(report)
    }
}
