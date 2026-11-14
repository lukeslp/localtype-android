package dev.localtype.keyboard

import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Potential keystroke savings with each bundled word pack on and off, on small public-domain samples of the text the
 * packs are for (FDA drug labels for the medical pack, the Congressional Record for politics) and on everyday phrases
 * (MacKenzie and Soukoreff's set, which the editor benchmark already carries), scored by [CorpusEvaluation.score]
 * exactly as the corpus scorer scores rankers: a word's saving is its length less the prefix length at which it first
 * shows among the three, over letters plus a space per word, with a paired bootstrap by line.
 *
 * Two keyboards are scored. "n-gram" is the strip with Chime off: [AdaptiveNgramModel] with the bundled prior, cold,
 * learning nothing. "strip" is Chime on, as the service merges it ([NeuralRanking] with the pack reservation of
 * [WordPacks.reserved]) and learning each word at its space the way the service does, a pack word included.
 * The figures go to `build/reports/word-packs/savings.txt` and the doc quotes them.
 */
class WordPackSavingsTest {
    private val fixtures = File("src/test/fixtures/packs")
    private val medical by lazy { bundled("medical") }
    private val politics by lazy { bundled("politics") }
    private val day = java.time.LocalDate.parse(OBSERVED).toEpochDay()

    private fun bundled(id: String): WordPack =
        WordPack.strict(File("src/main/assets/${WordPackCatalog.bundled(id)!!.asset}").readText())

    private fun packs(vararg on: WordPack) = WordPacks.of(on.toList(), day)

    /** The n-gram strip with Chime off, cold. */
    private class Ngram(override val name: String, private val packs: WordPacks) : CorpusRanker {
        private val model by lazy {
            AdaptiveNgramModel(dictionary = CorpusDeviceRanker.shippedDictionary, prior = CorpusDeviceRanker.bundledPrior, packs = packs)
        }

        override fun rank(context: String, prefix: String, limit: Int): List<String> = model.predict(context + prefix, limit)
    }

    /** Chime on: the service's merge, with the pack reservation, learning each word at its space as the service does. */
    private class Strip(override val name: String, private val packs: WordPacks) : CorpusRanker {
        private val chime by lazy { File("src/main/assets/${NeuralAsset.FILE_NAME}").inputStream().use { CifgModel.load(it) } }
        private val session by lazy { chime.newSession() }
        private val model by lazy {
            AdaptiveNgramModel(dictionary = CorpusDeviceRanker.shippedDictionary, prior = CorpusDeviceRanker.bundledPrior, packs = packs)
        }
        private val lexicon by lazy { TypoCorpus.lexicon }
        private val state = PersonalLearningState()
        private val own = ArrayList<String>()

        override fun rank(context: String, prefix: String, limit: Int): List<String> {
            if (context.isBlank()) own.clear()
            val before = context + prefix
            val neural = session.predict(before, CorpusStripRanker.NEURAL_WORDS).map { it.word }
            val fallback = model.predict(before, CorpusStripRanker.FALLBACK_WORDS)
            val last = own.lastOrNull()
            return NeuralRanking.rank(
                neural, fallback, if (last == null) emptyList() else model.learnedFollowers(last), prefix, emptySet(), CorpusStripRanker.SLOTS,
                shared = packs.reserved(prefix, fallback),
                followers = if (last == null) emptyList() else state.followers(own, NeuralRanking.PERSONAL_POLICY.maxContext),
                common = { model.dictionary.score(it) >= NeuralRanking.COMMON_SCORE },
            )
        }

        override fun typed(context: String, word: String) {
            if (context.isBlank()) own.clear()
            // The service's rule: dictionary, learned, kept, lexicon, and from now on a switched-on pack's words.
            if (!model.learnTypedWord(context + word, persist = false) { it in lexicon || it in packs }) {
                own.clear()
                return
            }
            if (own.isNotEmpty()) state.record(own.toList(), word, LearningOrigin.TYPED)
            own += AdaptiveNgramModel.normalize(word)
            if (own.size > CorpusStripRanker.MAX_OWN) own.removeAt(0)
        }
    }

    private class Row(val text: String, val keyboard: String, val off: CorpusEvaluation.Score, val on: List<Pair<String, CorpusEvaluation.Score>>,
                      val intervals: List<CorpusEvaluation.Interval>)

    private fun lines(file: File): List<String> = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }

    private fun measure(textName: String, lines: List<String>, variants: List<Pair<String, WordPacks>>): List<Row> {
        val text = CorpusEvaluation.ColumnText(CorpusEvaluation.Column(textName, textName, lines))
        return listOf("n-gram" to { name: String, p: WordPacks -> Ngram(name, p) as CorpusRanker },
            "strip" to { name: String, p: WordPacks -> Strip(name, p) as CorpusRanker }).map { (keyboard, make) ->
            val off = CorpusEvaluation.score(make("off", WordPacks.EMPTY), text)
            val on = variants.map { (name, p) -> name to CorpusEvaluation.score(make(name, p), text) }
            val intervals = on.map { (name, score) ->
                CorpusEvaluation.pairedBootstrap(off.savedByLine, score.savedByLine, text.baseline, names = "off" to name)
            }
            Row(textName, keyboard, off, on, intervals)
        }
    }

    /**
     * The words whose first offer comes later (or never) with [packs] on, in the cold n-gram strip: the cost a pack puts
     * on text it isn't for, word by word. Each entry is "word: prefix length off → on" (∅ when never offered).
     */
    private fun losses(lines: List<String>, packs: WordPacks): List<String> {
        val text = CorpusEvaluation.ColumnText(CorpusEvaluation.Column("losses", "losses", lines))
        val off = Ngram("off", WordPacks.EMPTY)
        val on = Ngram("on", packs)
        fun first(ranker: Ngram, t: CorpusText.Target): Int? =
            (0 until minOf(t.length, CorpusEvaluation.MAX_PREFIX + 1)).firstOrNull { p -> t.word in ranker.rank(t.context, t.prefix(p), 3) }
        return text.targets.filter { it.offerable }.mapNotNull { t ->
            val a = first(off, t)
            val b = first(on, t)
            if ((b ?: Int.MAX_VALUE) > (a ?: Int.MAX_VALUE)) "${t.word}: ${a ?: "∅"} → ${b ?: "∅"}" else null
        }
    }

    private fun pct(x: Double) = String.format(Locale.ROOT, "%.2f%%", 100 * x)
    private fun pts(x: Double) = String.format(Locale.ROOT, "%+.2f", 100 * x)

    @Test
    fun `keystroke savings with each pack on and off`() {
        val labels = File(fixtures, "fda-labels.txt")
        val record = File(fixtures, "congressional-record.txt")
        assumeTrue("domain samples present", labels.isFile && record.isFile)
        val variants = listOf("medical" to packs(medical), "politics" to packs(politics), "both" to packs(medical, politics))
        val texts = mutableListOf("FDA drug labels" to lines(labels), "Congressional Record" to lines(record))
        // The everyday control lives with the editor benchmark, which the public source doesn't carry.
        val phrases = File("../verification/editor-fixture/phrases/mackenzie-soukoreff-2003.txt")
        if (phrases.isFile) texts += "Everyday phrases" to lines(phrases)
        val rows = texts.flatMap { (name, text) -> measure(name, text, variants) }
        val report = buildString {
            appendLine("Word packs: potential keystroke savings (letters plus a space per word saved by a tap, over all of them)")
            appendLine("packs observed $OBSERVED; medical ${medical.size} words (${medical.describe()}), politics ${politics.size} words (${politics.describe()})")
            appendLine("Interval: 95% paired bootstrap by line of on minus off, ${CorpusEvaluation.RESAMPLES} resamples, in points")
            appendLine()
            appendLine("text | keyboard | words | baseline chars | off | pack on | savings | on - off [95% CI]")
            for (row in rows) for ((i, pair) in row.on.withIndex()) {
                val (name, score) = pair
                val interval = row.intervals[i]
                appendLine("${row.text} | ${row.keyboard} | ${row.off.targets} | ${row.off.baseline} | ${pct(row.off.savings)} | $name | " +
                    "${pct(score.savings)} | ${pts(interval.difference)} [${pts(interval.low)}, ${pts(interval.high)}]")
            }
        }
        val lossReport = buildString {
            appendLine()
            appendLine("Words offered later (prefix length off → on) with both packs on, n-gram strip:")
            for ((name, text) in texts) appendLine("$name: ${losses(text, packs(medical, politics)).ifEmpty { listOf("none") }.joinToString("; ")}")
        }
        println(report + lossReport)
        File("build/reports/word-packs").apply { mkdirs() }.resolve("savings.txt").writeText(report + lossReport)

        fun row(text: String, keyboard: String) = rows.single { it.text == text && it.keyboard == keyboard }
        fun gain(text: String, keyboard: String, pack: String): CorpusEvaluation.Interval =
            row(text, keyboard).let { it.intervals[it.on.indexOfFirst { p -> p.first == pack }] }
        for (keyboard in listOf("n-gram", "strip")) {
            // Each pack helps the text it is for, beyond the bootstrap's noise.
            assertTrue("$keyboard medical", gain("FDA drug labels", keyboard, "medical").low > 0.0)
            assertTrue("$keyboard politics", gain("Congressional Record", keyboard, "politics").low > 0.0)
            // And costs at most a tenth of a point on text it isn't for.
            assertTrue("$keyboard politics on labels", gain("FDA drug labels", keyboard, "politics").difference > -0.001)
            assertTrue("$keyboard medical on the Record", gain("Congressional Record", keyboard, "medical").difference > -0.001)
            if (phrases.isFile) assertTrue("$keyboard both on phrases", gain("Everyday phrases", keyboard, "both").difference > -0.001)
        }
    }

    companion object {
        /** The day the measurement pins its packs to: the packs' own observed date, so officeholders count in full. */
        const val OBSERVED = "2026-11-13"
    }
}
