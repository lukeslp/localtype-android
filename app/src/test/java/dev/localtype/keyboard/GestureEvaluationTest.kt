package dev.localtype.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The swipe decoder on paths that can be checked by hand and on drawn swipes, in every run. A table of
 * recorded swipes is scored only when `LOCALTYPE_GESTURES` names one (written by `model/gestures.py`;
 * `LOCALTYPE_GESTURE_ROWS` takes the first N). That run asserts that the counts add up and never a
 * figure, so a decoder change cannot fail it; the figures go to `build/reports/gesture-eval/`.
 */
class GestureEvaluationTest {
    private fun index(vararg words: Pair<String, Int>) = GestureIndex(words.toList())

    private fun ideal(word: String) = GestureEvaluation.synthetic(listOf(word), spread = 0f, seed = 1).single()

    private fun read(index: GestureIndex, swipe: GestureEvaluation.Swipe, limit: Int = 3) =
        GestureDecoder(index).decode(swipe.xs, swipe.ys, swipe.xs.size, swipe.aspect, limit).candidates.map { it.word }

    @Test
    fun `a path is the letters a finger passes`() {
        assertEquals("its", GestureIndex.fold("It’s"))
        assertEquals("manana", GestureIndex.fold("mañana"))
        assertEquals("tambien", GestureIndex.fold("también"))
        assertEquals("email", GestureIndex.fold("e-mail"))
        assertEquals("leter", GestureIndex.path("letter"))
        assertEquals("pero", GestureIndex.path("perro"))
        assertEquals("", GestureIndex.path("42"))
    }

    @Test
    fun `every spelling that shares a path is offered, the commoner first`() {
        val words = index("its" to 60, "it's" to 70, "it" to 80, "outs" to 40)
        assertEquals(listOf("it's", "its"), read(words, ideal("its")).take(2))
        val spanish = index("pero" to 80, "perro" to 55, "para" to 85)
        assertEquals(listOf("pero", "perro"), read(spanish, ideal("perro")).take(2))
        assertEquals(1, spanish.groups.count { "perro" in it.words })
        assertEquals(spanish.groupOf("pero"), spanish.groupOf("perro"))
        assertEquals(-1, spanish.groupOf("perra"))
    }

    @Test
    fun `a word outside the index is never invented`() {
        val words = index("hello" to 60, "help" to 55)
        assertTrue(read(words, ideal("world")).all { it == "hello" || it == "help" })
        assertEquals(emptyList<String>(), read(index(), ideal("hello")))
    }

    @Test
    fun `a path drawn through the key centres offers its word`() {
        val index = GestureIndex(GestureEvaluation.dictionaryWords())
        val common = TypoCorpus.dictionary.allWords().filter { it.length >= 3 && it.all { c -> c in 'a'..'z' } && TypoCorpus.dictionary.score(it) >= 60 }
        val report = GestureEvaluation.score(GestureEvaluation.synthetic(common, spread = 0f, seed = 1).asSequence(), index, "bundled")
        assertEquals(common.size, report.known.swipes)
        assertEquals("pruning must never drop the word whose own path was drawn", report.known.swipes, report.survived)
        // Not always first: the weights are set on real swipes, which stray, so a commoner word with a
        // path close by can outrank the word whose path this is. It must still be offered.
        assertTrue("an ideal path's own word was not among the three: ${report.known}", report.known.top3 >= report.known.swipes * 0.93)
    }

    /** The floor for drawn swipes. They say nothing about a person's; the recordings measure those. */
    @Test
    fun `drawn swipes clear the floor`() {
        val index = GestureIndex(GestureEvaluation.dictionaryWords())
        val pool = TypoCorpus.dictionary.allWords().filter { it.length >= 2 && it.all { c -> c in 'a'..'z' } && TypoCorpus.dictionary.score(it) >= 45 }
        val words = pool.shuffled(kotlin.random.Random(11)).take(1500)
        val report = GestureEvaluation.score(GestureEvaluation.synthetic(words, spread = 0.18f, seed = 7).asSequence(), index, "bundled")
        println(report.lines().joinToString("\n"))
        assertEquals(words.size, report.known.swipes)
        assertTrue("first choice fell: ${report.known}", report.known.first >= words.size * FLOOR_FIRST)
        assertTrue("top three fell: ${report.known}", report.known.top3 >= words.size * FLOOR_TOP3)
    }

    @Test
    fun `a prepared table is read as written`() {
        val swipe = GestureEvaluation.parse("7\tsession\tThe\tSay \t1.3453\t-\t4.408,0.600,0;5.853,1.692,177;2.500,0.500,400;")!!
        assertEquals("The", swipe.word)
        assertEquals("Say ", swipe.context)
        assertEquals(3, swipe.xs.size)
        assertEquals(1.692f, swipe.ys[1], 1e-6f)
        assertEquals(null, GestureEvaluation.parse("7\tsession\tThe\t\t1.3\t-\t"))
        assertEquals(null, GestureEvaluation.parse("not a row"))
    }

    @Test
    fun `the target is the word without the sentence's punctuation`() {
        assertEquals("years", GestureEvaluation.target("years."))
        assertEquals("however", GestureEvaluation.target("However,"))
        assertEquals("don't", GestureEvaluation.target("“Don’t”"))
        assertEquals("well-known", GestureEvaluation.target("(well-known)"))
        assertEquals("", GestureEvaluation.target("—"))
        // With the prompt's full stop left on, a word the dictionary has was counted as one it lacks.
        val index = index("years" to 60, "tears" to 55)
        val swipe = ideal("years")
        val shown = GestureEvaluation.Swipe("1", "s", "years.", "", swipe.aspect, "-", swipe.xs, swipe.ys)
        val report = GestureEvaluation.score(sequenceOf(shown), index, "two words")
        assertEquals(0, report.unknown)
        assertEquals(1, report.known.first)
    }

    @Test
    fun `a row that does not say where its word sat gets no context`() {
        val index = index("years" to 60, "tears" to 60)
        val swipe = ideal("years")
        fun scored(context: String) = GestureEvaluation.score(
            sequenceOf(GestureEvaluation.Swipe("1", "s", "years", context, swipe.aspect, "-", swipe.xs, swipe.ys)), index, "two words",
        ) { _, start, word -> if (start && word == "tears") 100f else 0f }
        assertEquals("a sentence start takes the bonus", 0, scored("").known.first)
        assertEquals("an unknown place does not", 1, scored(GestureEvaluation.UNKNOWN_CONTEXT).known.first)
    }

    @Test
    fun `recorded swipes are scored when a table is given`() {
        val path = System.getenv("LOCALTYPE_GESTURES")?.trim().orEmpty()
        assumeTrue("set LOCALTYPE_GESTURES to a table from model/gestures.py", path.isNotEmpty())
        val file = File(path)
        assertTrue("$file is not a file", file.isFile)
        val rows = System.getenv("LOCALTYPE_GESTURE_ROWS")?.trim()?.toIntOrNull() ?: Int.MAX_VALUE
        // "bundled" is what the strip can offer today. "lexicon" adds every known word at one low score,
        // to see how far the 132,000-word list, which has no frequencies, would close the gap.
        val vocabulary = System.getenv("LOCALTYPE_GESTURE_VOCABULARY")?.trim()?.ifEmpty { null } ?: "bundled"
        val words = when (vocabulary) {
            "bundled" -> GestureEvaluation.dictionaryWords()
            "lexicon" -> GestureEvaluation.dictionaryWords() + GestureEvaluation.lexiconOnlyWords(score = 30)
            // "frequency" is the first LOCALTYPE_GESTURE_WORDS known words of the FrequencyWords full list
            // (LOCALTYPE_GESTURE_WORDLIST), with their own frequencies.
            "frequency" -> GestureEvaluation.frequencyWords(
                File(System.getenv("LOCALTYPE_GESTURE_WORDLIST") ?: error("set LOCALTYPE_GESTURE_WORDLIST")),
                System.getenv("LOCALTYPE_GESTURE_WORDS")?.trim()?.toIntOrNull() ?: 50_000,
            )
            else -> error("LOCALTYPE_GESTURE_VOCABULARY is bundled, lexicon or frequency")
        }
        // "prior" adds the corpus prior's bonus after the previous word, on the dictionary score's own scale.
        val prior = if (System.getenv("LOCALTYPE_GESTURE_CONTEXT")?.trim() == "prior") GestureEvaluation.PriorContext.bundled() else null
        val settings = GestureDecoder.Settings().let { defaults ->
            // "endRadius,endKeys,lengthSlack,lengthRatio", to measure what pruning costs.
            System.getenv("LOCALTYPE_GESTURE_PRUNING")?.trim()?.ifEmpty { null }?.split(',')?.let {
                defaults.copy(endRadius = it[0].toFloat(), endKeys = it[1].toInt(), lengthSlack = it[2].toFloat(), lengthRatio = it[3].toFloat())
            } ?: defaults
        }
        val index = GestureIndex(words)
        // 1 is the weight the strip's ranker gives the prior beside a dictionary score; LOCALTYPE_GESTURE_CONTEXT_WEIGHT scales it.
        val share = System.getenv("LOCALTYPE_GESTURE_CONTEXT_WEIGHT")?.trim()?.toFloatOrNull() ?: 1f
        // LOCALTYPE_GESTURE_MODEL names a file from `swipe.py export`: the trained decoder then reads the swipes, with
        // LOCALTYPE_GESTURE_FREQUENCY as what one point of dictionary score is worth to it (0.1 unless given).
        val modelFile = System.getenv("LOCALTYPE_GESTURE_MODEL")?.trim()?.ifEmpty { null }?.let(::File)
        val modelWeight = System.getenv("LOCALTYPE_GESTURE_FREQUENCY")?.trim()?.toFloatOrNull() ?: 0.1f
        val trained = modelFile?.let { SwipeDecoder(it.inputStream().use(SwipeModel::read), index, modelWeight) }
        val unit = if (trained != null) modelWeight else settings.frequencyWeight
        val context = prior?.let { p -> { previous: String?, start: Boolean, word: String -> unit * share * p.bonus(previous, start, word) } }
        val name = (if (modelFile != null) "${modelFile.nameWithoutExtension}-$modelWeight-" else "") + vocabulary + (if (vocabulary == "frequency") "-${words.size}" else "") + (if (prior != null) "-prior-$share" else "") +
            (if (settings != GestureDecoder.Settings()) "-pruning-${settings.endRadius}-${settings.endKeys}-${settings.lengthSlack}-${settings.lengthRatio}" else "") +
            (if (rows != Int.MAX_VALUE) "-first$rows" else "")
        val report = GestureEvaluation.score(GestureEvaluation.read(file, rows), index, name, settings, trained, context)
        val written = GestureEvaluation.write(report, "${file.nameWithoutExtension}-$name")
        println("${file.name}\n" + report.lines().joinToString("\n") + "\nwritten to $written")
        assertTrue(report.all.swipes > 0)
        assertEquals(report.all.swipes, report.known.swipes + report.unknown)
        assertEquals(report.known.swipes, report.byLength.sumOf { it.second.swipes })
        assertEquals(report.known.swipes, report.byFrequency.sumOf { it.second.swipes })
        assertTrue(report.survived <= report.known.swipes && report.known.top3 <= report.survived)
    }

    /**
     * A coarse search over the decoder's three weights on the first rows of a table, when
     * `LOCALTYPE_GESTURE_TUNE` is set. Tune on the dev split only; the test split is scored once.
     */
    @Test
    fun `the weights are searched when asked`() {
        val path = System.getenv("LOCALTYPE_GESTURES")?.trim().orEmpty()
        val grid = System.getenv("LOCALTYPE_GESTURE_TUNE")?.trim().orEmpty()
        assumeTrue("set LOCALTYPE_GESTURES and LOCALTYPE_GESTURE_TUNE", path.isNotEmpty() && grid.isNotEmpty())
        val rows = System.getenv("LOCALTYPE_GESTURE_ROWS")?.trim()?.toIntOrNull() ?: 8000
        val swipes = GestureEvaluation.read(File(path), rows).toList()
        val index = GestureIndex(GestureEvaluation.dictionaryWords())
        // "location;shape;frequency", each a comma-separated list.
        val (locations, shapes, frequencies) = grid.split(';').map { part -> part.split(',').map { it.trim().toFloat() } }
        val results = ArrayList<Pair<GestureDecoder.Settings, GestureEvaluation.Report>>()
        for (location in locations) for (shape in shapes) for (frequency in frequencies) {
            val settings = GestureDecoder.Settings(locationSigma = location, shapeSigma = shape, frequencyWeight = frequency)
            results += settings to GestureEvaluation.score(swipes.asSequence(), index, "bundled", settings)
        }
        val lines = results.sortedByDescending { it.second.known.first }.take(12).map { (settings, report) ->
            "location %.2f shape %.3f frequency %.3f: known first %.1f%% top3 %.1f%% | all first %.1f%% top3 %.1f%%".format(
                settings.locationSigma, settings.shapeSigma, settings.frequencyWeight,
                100.0 * report.known.first / report.known.swipes, 100.0 * report.known.top3 / report.known.swipes,
                100.0 * report.all.first / report.all.swipes, 100.0 * report.all.top3 / report.all.swipes)
        }
        println("searched ${results.size} settings on ${swipes.size} swipes\n" + lines.joinToString("\n"))
        assertTrue(results.isNotEmpty())
    }

    private companion object {
        // Set from the first run and held just under it, so a change that makes drawn swipes worse fails here.
        const val FLOOR_FIRST = 0.48
        const val FLOOR_TOP3 = 0.74
    }
}
