package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Decision 3 for Portuguese, the Spanish protocol again (docs/languages-route-a-2026-10-17.md, "The Portuguese prior's
 * protocol"): the candidate corpus prior is scored on the Portuguese dev split against the model Portuguese ships with,
 * at the six weights the English and Spanish priors were tried at; the weight is chosen on dev by the rule written before
 * any score; and the test split is read once, at that weight only, against the bar written with it. The prior ships only
 * if it passes.
 *
 * Runs only when pointed at a candidate build: `-Dlocaltype.portugueseBuild=<model-data>/languages/pt/pt-candidate-1`
 * (its `corpus/` split and `corpus_prior_pt.bin`), with `-Dlocaltype.portugueseSplit=dev|test` and, for a quick look on dev,
 * `-Dlocaltype.portugueseLines=N`. The test split refuses to run until [CHOSEN] holds dev's choice and [BAR_AGREED] is true:
 * Luke agrees to the bar before the one reading of test.
 */
class CorpusPortugueseEvaluationTest {
    private val portuguese = LanguageProfile.PORTUGUESE

    private class Setup(val build: File, val split: String, val lines: Int) {
        val corpus = File(build, "corpus")
        val prior = File(build, "corpus_prior_pt.bin")
    }

    private fun setup(): Setup? {
        val build = System.getProperty("localtype.portugueseBuild")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val split = System.getProperty("localtype.portugueseSplit")?.trim()?.takeIf { it.isNotEmpty() } ?: "dev"
        require(split in CorpusEvaluation.SPLITS) { "localtype.portugueseSplit must be one of ${CorpusEvaluation.SPLITS}" }
        val lines = System.getProperty("localtype.portugueseLines")?.trim()?.takeIf { it.isNotEmpty() }?.toInt() ?: LINES
        require(lines in 1..LINES) { "localtype.portugueseLines must be 1..$LINES" }
        // A quick look is for dev; the test split's one reading is the preregistered size.
        require(split == "dev" || lines == LINES) { "the test split reads $LINES lines per column, as preregistered" }
        return Setup(File(build), split, lines)
    }

    /** words_pt.txt as the keyboard loads it with Suggest swear words off. */
    private val dictionary: WordDictionary by lazy { WordDictionary.parse(File("src/main/assets/words_pt.txt").readLines().asSequence(), portuguese) }

    /** The model Portuguese ships with: no seed phrases or sentence starters, no prior, its one-letter words. */
    private fun model(prior: CorpusPrior? = null, weights: AdaptiveNgramModel.PriorWeights = AdaptiveNgramModel.PriorWeights.DEFAULT) =
        AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(), prior = prior,
            priorWeights = weights, openingMarks = portuguese.openingMarks, oneLetterWords = portuguese.oneLetterWords)

    private fun cold() = CorpusDeviceRanker("pt-cold", "the model Portuguese ships with: words_pt.txt, no seeds, no starters, no prior",
        portuguese.oneLetterWords) { model() }

    private fun withPrior(prior: () -> CorpusPrior, weights: AdaptiveNgramModel.PriorWeights) =
        CorpusDeviceRanker("pt-" + CorpusDeviceRanker.priorName(weights), "pt-cold plus corpus_prior_pt.bin at weight ${weights.weight}",
            portuguese.oneLetterWords) { model(prior(), weights) }

    /** A seeded [LINES]-line sample of each source. */
    private fun columns(setup: Setup): List<CorpusEvaluation.Column> {
        val text = File(setup.corpus, "${setup.split}.txt").readLines()
        val sources = File(setup.corpus, "${setup.split}.sources.txt").readLines()
        require(text.size == sources.size)
        val eligible = text.indices.filter { CorpusText.hasToken(text[it]) }
        return COLUMNS.map { (name, source) ->
            val ids = eligible.filter { sources[it] == source }
            CorpusEvaluation.Column(name, source, CorpusEvaluation.sample(ids, setup.lines, CorpusEvaluation.SEED).map { text[it] })
        }
    }

    @Test
    fun `the portuguese prior is scored by the protocol written before it`() {
        val setup = setup()
        assumeTrue("set -Dlocaltype.portugueseBuild=<model-data>/languages/pt/<build> to score the Portuguese prior", setup != null)
        setup!!
        val prior by lazy { setup.prior.inputStream().use { CorpusPrior.read(it) } }
        assertEquals("the protocol names this prior", PRIOR_SHA256, prior.sha256)
        val rankers = when (setup.split) {
            "test" -> {
                val chosen = CHOSEN ?: error("the test split scores only the weight chosen on dev: record it in CHOSEN first")
                check(BAR_AGREED) { "the test split stays unread until Luke agrees to the bar (docs/languages-route-a-2026-10-17.md)" }
                listOf(cold(), withPrior({ prior }, chosen))
            }
            else -> listOf(cold()) + CorpusEvaluation.PRIOR_SETTINGS.map { withPrior({ prior }, it) }
        }
        val result = CorpusEvaluation.evaluate(columns(setup), rankers, oneLetterWords = portuguese.oneLetterWords)
        val dir = File("build/reports/corpus-eval").apply { mkdirs() }
        val report = File(dir, "pt-${setup.split}.txt").apply { writeText(result.report()) }
        println(result.report())
        println("report: ${report.path}; seconds per ranker: " + result.seconds.entries.joinToString { "${it.key} %.1f".format(it.value) })
        for (column in result.results) {
            assertTrue("no targets in ${column.text.column.name}", column.text.targets.isNotEmpty())
            for (s in column.scores) assertTrue("${s.ranker.name} savings ${s.savings}", s.savings in 0.0..1.0)
        }
        if (setup.split == "dev") {
            println("dev choice by the written rule: ${choose(result)}")
        } else {
            val gate = gate(result, "pt-" + CorpusDeviceRanker.priorName(checkNotNull(CHOSEN)))
            gate.forEach { println(it) }
            println("Portuguese prior gate: ${if (gate.all { it.endsWith("pass") }) "PASSED" else "FAILED"}")
        }
    }

    /** The written rule: the weight with the highest mean of the two columns' potential savings, a tie within 0.0001 to the lower weight. */
    private fun choose(result: CorpusEvaluation.Result): String {
        val columns = COLUMNS.map { (name, _) -> result.column(name)!! }
        fun mean(ranker: String) = columns.sumOf { it.score(ranker)!!.savings } / columns.size
        val means = CorpusEvaluation.PRIOR_SETTINGS.map { w -> w to mean("pt-" + CorpusDeviceRanker.priorName(w)) }
        val best = means.maxOf { it.second }
        val (weights, chosen) = means.filter { it.second >= best - 1e-4 }.minBy { it.first.weight }
        return "weight ${weights.weight}: mean savings %.4f against pt-cold %.4f".format(chosen, mean("pt-cold"))
    }

    /** The bar, per column: savings above pt-cold with the paired 95% interval above zero, and next-word hit@3 not lower. */
    private fun gate(result: CorpusEvaluation.Result, candidate: String): List<String> = COLUMNS.map { (name, _) ->
        val column = result.column(name)!!
        val savings = column.interval("pt-cold", candidate)!!.first
        val cold = column.score("pt-cold")!!
        val with = column.score(candidate)!!
        val pass = savings.low > 0.0 && with.hit3 >= cold.hit3
        "$name: savings %+.4f [%+.4f, %+.4f], hit@3 %d against %d: %s".format(savings.difference, savings.low, savings.high, with.hit3, cold.hit3,
            if (pass) "pass" else "fail")
    }

    companion object {
        /** Lines per source column, from each source's eligible lines in the split, by [CorpusEvaluation.sample] with its seed. */
        const val LINES = 5_000

        val COLUMNS = listOf("news" to "por_news_2024_1M", "brazil" to "por-br_newscrawl_2011_1M")

        /** `corpus_prior_pt.bin` of `pt-candidate-1` (its report.json): the asset this protocol is about. */
        const val PRIOR_SHA256 = "024814ee3c8ac34cd5d8b027878c5a9fa9b90d971d59ad0519fac5db625522a7"

        /**
         * Dev's choice by the written rule, recorded before the test split is read: weight 80, mean potential savings 35.90%
         * against pt-cold's 30.40% on 2026-10-18 (news +5.85 points, Brazilian newscrawl +5.15; weight 20 was within 0.11 of it).
         */
        val CHOSEN: AdaptiveNgramModel.PriorWeights? = AdaptiveNgramModel.PriorWeights(weight = 80f)

        /** Luke agrees to the bar before the test split's one reading: he did on 2026-10-18, at the weight dev chose. */
        const val BAR_AGREED = true
    }
}
