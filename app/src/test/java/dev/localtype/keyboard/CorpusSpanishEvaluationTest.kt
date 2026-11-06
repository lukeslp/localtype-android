package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Decision 3 for Spanish (docs/languages-route-a-2026-10-17.md, "The Spanish prior's protocol"): the candidate
 * corpus prior is scored on the Spanish dev split against the model Spanish ships with, at the six weights the
 * English prior was tried at; the weight is chosen on dev by the rule written before any score; and the test split
 * is read once, at that weight only, against the bar written with it. The prior ships only if it passes.
 *
 * Runs only when pointed at a candidate build: `-Dlocaltype.spanishBuild=<model-data>/languages/es/es-candidate-2`
 * (its `corpus/` split and `corpus_prior_es.bin`), with `-Dlocaltype.spanishSplit=dev|test` and, for a quick look,
 * `-Dlocaltype.spanishLines=N`. The test split refuses to run until [CHOSEN] holds dev's choice and [BAR_AGREED] is true:
 * Luke agrees to the bar before the one reading of test.
 */
class CorpusSpanishEvaluationTest {
    private val spanish = LanguageProfile.SPANISH

    private class Setup(val build: File, val split: String, val lines: Int) {
        val corpus = File(build, "corpus")
        val prior = File(build, "corpus_prior_es.bin")
    }

    private fun setup(): Setup? {
        val build = System.getProperty("localtype.spanishBuild")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val split = System.getProperty("localtype.spanishSplit")?.trim()?.takeIf { it.isNotEmpty() } ?: "dev"
        require(split in CorpusEvaluation.SPLITS) { "localtype.spanishSplit must be one of ${CorpusEvaluation.SPLITS}" }
        val lines = System.getProperty("localtype.spanishLines")?.trim()?.takeIf { it.isNotEmpty() }?.toInt() ?: LINES
        require(lines in 1..LINES) { "localtype.spanishLines must be 1..$LINES" }
        // A quick look is for dev; the test split's one reading is the preregistered size.
        require(split == "dev" || lines == LINES) { "the test split reads $LINES lines per column, as preregistered" }
        return Setup(File(build), split, lines)
    }

    /** words_es.txt as the keyboard loads it with Suggest swear words off. */
    private val dictionary: WordDictionary by lazy { WordDictionary.parse(File("src/main/assets/words_es.txt").readLines().asSequence(), spanish) }

    /** The model Spanish ships with: no seed phrases or sentence starters, its one-letter words, ¿ and ¡ as opening marks. */
    private fun model(prior: CorpusPrior? = null, weights: AdaptiveNgramModel.PriorWeights = AdaptiveNgramModel.PriorWeights.DEFAULT) =
        AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList(), prior = prior,
            priorWeights = weights, openingMarks = spanish.openingMarks, oneLetterWords = spanish.oneLetterWords)

    private fun cold() = CorpusDeviceRanker("es-cold", "the model Spanish ships with: words_es.txt, no seeds, no starters, no prior",
        spanish.oneLetterWords) { model() }

    private fun withPrior(prior: () -> CorpusPrior, weights: AdaptiveNgramModel.PriorWeights) =
        CorpusDeviceRanker("es-" + CorpusDeviceRanker.priorName(weights), "es-cold plus corpus_prior_es.bin at weight ${weights.weight}",
            spanish.oneLetterWords) { model(prior(), weights) }

    /** A seeded [lines]-line sample of each source, as the English human columns are whole sources. */
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
    fun `the spanish prior is scored by the protocol written before it`() {
        val setup = setup()
        assumeTrue("set -Dlocaltype.spanishBuild=<model-data>/languages/es/<build> to score the Spanish prior", setup != null)
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
        val result = CorpusEvaluation.evaluate(columns(setup), rankers, oneLetterWords = spanish.oneLetterWords)
        val dir = File("build/reports/corpus-eval").apply { mkdirs() }
        val report = File(dir, "es-${setup.split}.txt").apply { writeText(result.report()) }
        println(result.report())
        println("report: ${report.path}; seconds per ranker: " + result.seconds.entries.joinToString { "${it.key} %.1f".format(it.value) })
        for (column in result.results) {
            assertTrue("no targets in ${column.text.column.name}", column.text.targets.isNotEmpty())
            for (s in column.scores) assertTrue("${s.ranker.name} savings ${s.savings}", s.savings in 0.0..1.0)
        }
        if (setup.split == "dev") {
            val choice = choose(result)
            println("dev choice by the written rule: $choice")
        } else {
            val gate = gate(result, "es-" + CorpusDeviceRanker.priorName(checkNotNull(CHOSEN)))
            gate.forEach { println(it) }
            println("Spanish prior gate: ${if (gate.all { it.endsWith("pass") }) "PASSED" else "FAILED"}")
        }
    }

    /** The written rule: the weight with the highest mean of web and news potential savings, a tie within 0.0001 to the lower weight. */
    private fun choose(result: CorpusEvaluation.Result): String {
        val web = result.column("web")!!
        val news = result.column("news")!!
        val means = CorpusEvaluation.PRIOR_SETTINGS.map { w ->
            val name = "es-" + CorpusDeviceRanker.priorName(w)
            w to (web.score(name)!!.savings + news.score(name)!!.savings) / 2
        }
        val best = means.maxOf { it.second }
        val (weights, mean) = means.filter { it.second >= best - 1e-4 }.minBy { it.first.weight }
        val cold = (web.score("es-cold")!!.savings + news.score("es-cold")!!.savings) / 2
        return "weight ${weights.weight}: mean savings %.4f against es-cold %.4f".format(mean, cold)
    }

    /** The bar, per column: savings above es-cold with the paired 95% interval above zero, and next-word hit@3 not lower. */
    private fun gate(result: CorpusEvaluation.Result, candidate: String): List<String> = COLUMNS.map { (name, _) ->
        val column = result.column(name)!!
        val savings = column.interval("es-cold", candidate)!!.first
        val cold = column.score("es-cold")!!
        val with = column.score(candidate)!!
        val pass = savings.low > 0.0 && with.hit3 >= cold.hit3
        "$name: savings %+.4f [%+.4f, %+.4f], hit@3 %d against %d: %s".format(savings.difference, savings.low, savings.high, with.hit3, cold.hit3,
            if (pass) "pass" else "fail")
    }

    companion object {
        /** Lines per source column, from each source's eligible lines in the split, by [CorpusEvaluation.sample] with its seed. */
        const val LINES = 5_000

        val COLUMNS = listOf("web" to "spa_web_2016_1M", "news" to "spa_news_2024_1M")

        /** `corpus_prior_es.bin` of `es-candidate-2` (its report.json): the asset this protocol is about. */
        const val PRIOR_SHA256 = "fc267ff44fc9657eebc75eb1a3f61804712a3fa3837640dc5e4f45a82fb02f3d"

        /**
         * Dev's choice by the written rule, recorded before the test split is read: weight 80, mean potential savings
         * 37.10% against es-cold's 32.03% on 2026-10-18 (web +4.95 points, news +5.20; weight 20 was within 0.13 of it).
         */
        val CHOSEN: AdaptiveNgramModel.PriorWeights? = AdaptiveNgramModel.PriorWeights(weight = 80f)

        /** Luke agrees to the bar before the test split's one reading: he did on 2026-10-18, at the weight dev chose. */
        const val BAR_AGREED = true
    }
}
