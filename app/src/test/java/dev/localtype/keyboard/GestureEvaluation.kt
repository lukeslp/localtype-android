package dev.localtype.keyboard

import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Scores a [GestureDecoder] on swipes whose intended word is known: recorded ones prepared by
 * `model/gestures.py`, or drawn ones from [synthetic]. Out-of-vocabulary words count as misses in the
 * headline figures, because a person swiping one gets the wrong word whatever the decoder does
 * (docs/gesture-typing-2026-10.md).
 */
object GestureEvaluation {
    /**
     * One swipe: x in key widths, y in rows, a row [aspect] key widths tall, [ts] milliseconds from its first
     * point. [context] is the sentence before [word].
     */
    class Swipe(
        val id: String, val session: String, val word: String, val context: String,
        val aspect: Float, val flags: String, val xs: FloatArray, val ys: FloatArray,
        val ts: FloatArray = FloatArray(xs.size) { it * 16f },
    )

    class Tally(val name: String) {
        var swipes = 0
        var first = 0
        var top3 = 0
        fun add(isFirst: Boolean, inTop3: Boolean) {
            swipes++
            if (isFirst) first++
            if (inTop3) top3++
        }
        override fun toString() = if (swipes == 0) "%-24s      0".format(name)
            else "%-24s %6d   first %5.1f%%   top three %5.1f%%".format(name, swipes, 100.0 * first / swipes, 100.0 * top3 / swipes)
    }

    class Report(val vocabulary: String, val words: Int, val settings: GestureDecoder.Settings) {
        val all = Tally("all words")
        val known = Tally("in the vocabulary")
        /** The same two, leaving out swipes the source itself doubts (a failed attempt, a sentence it flags). */
        val unflagged = Tally("all words, unflagged")
        val unflaggedKnown = Tally("in vocabulary, unflagged")
        val byLength = listOf(1..2, 3..4, 5..6, 7..9, 10..99).map { it to Tally("${it.first}${if (it.last > 20) "+" else "-${it.last}"} letters") }
        val byFrequency = listOf(70..100, 55..69, 40..54, 0..39).map { it to Tally("score ${it.first}-${it.last}") }
        var notWords = 0
        var unknown = 0
        /** In the vocabulary, and its path was among those compared. */
        var survived = 0
        var unflaggedSurvived = 0
        /** The first choice draws the intended word's path, whether or not it is that spelling. */
        var samePath = 0
        var scored = 0L
        val nanos = ArrayList<Long>()

        fun percentile(p: Double): Double = if (nanos.isEmpty()) 0.0 else nanos.sorted()[((nanos.size - 1) * p).toInt()] / 1e6

        fun lines(): List<String> = buildList {
            add("vocabulary: $vocabulary ($words words)")
            add("settings: $settings")
            add(all.toString())
            add(known.toString())
            if (unflagged.swipes != all.swipes) {
                add(unflagged.toString())
                add(unflaggedKnown.toString())
            }
            add("%-24s %6d   (%.1f%% of all; counted as misses above)".format("not in the vocabulary", unknown, percent(unknown, all.swipes)))
            add("%-24s %6d   (%.1f%% of those in the vocabulary)".format("survived pruning", survived, percent(survived, known.swipes)))
            if (unflagged.swipes != all.swipes) {
                add("%-24s %6d   (%.1f%% of the unflagged in the vocabulary)".format("survived, unflagged", unflaggedSurvived, percent(unflaggedSurvived, unflaggedKnown.swipes)))
            }
            add("%-24s %6d   (%.1f%% of those in the vocabulary)".format("first choice, same path", samePath, percent(samePath, known.swipes)))
            add("not words (no letters), left out: $notWords")
            add("paths compared per swipe: %.0f".format(if (all.swipes == 0) 0.0 else scored.toDouble() / all.swipes))
            add("decode time, ms: p50 %.2f  p95 %.2f  (this JVM, one thread)".format(percentile(0.5), percentile(0.95)))
            add("in the vocabulary, by length:")
            byLength.forEach { add("  ${it.second}") }
            add("in the vocabulary, by frequency:")
            byFrequency.forEach { add("  ${it.second}") }
        }

        private fun percent(part: Int, whole: Int) = if (whole == 0) 0.0 else 100.0 * part / whole
    }

    /** The bundled dictionary's words with their scores: what the strip can offer today. */
    fun dictionaryWords(): List<Pair<String, Int>> = TypoCorpus.dictionary.let { d -> d.allWords().map { it to d.score(it) } }

    /** The known-word list's entries that the dictionary lacks, all at one [score]: that list has no frequencies. */
    fun lexiconOnlyWords(score: Int): List<Pair<String, Int>> {
        val dictionary = TypoCorpus.dictionary
        return File("src/main/assets/words_known.txt").readLines().asSequence()
            .map { AdaptiveNgramModel.normalize(it.substringBefore('\t').trim()) }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it !in dictionary }
            .distinct().map { it to score }.toList()
    }

    /**
     * A longer list with real frequencies: the first [limit] entries of the FrequencyWords full list (the list
     * `words_en.txt` is the top 20,000 of) that the keyboard already knows as words, scored the way
     * [WordDictionary] scores, with the bundled dictionary's own entries on top. Swear words and the
     * excluded words stay out, as they do from the strip.
     */
    fun frequencyWords(file: File, limit: Int): List<Pair<String, Int>> {
        val dictionary = TypoCorpus.dictionary
        val lexicon = TypoCorpus.lexicon
        val swears = File("src/main/assets/words_en_profanity.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.toSet()
        val words = LinkedHashMap<String, Int>()
        var top = 0.0
        file.bufferedReader().use { reader ->
            for (line in reader.lineSequence()) {
                if (words.size >= limit) break
                val space = line.lastIndexOf(' ')
                val count = line.substring(space + 1).toLongOrNull() ?: continue
                if (space <= 0) continue
                if (top == 0.0) top = kotlin.math.ln(count + 1.0)
                val word = AdaptiveNgramModel.normalize(line.substring(0, space))
                if (word in words || word in swears || !AdaptiveNgramModel.WORD_REGEX.matches(word)) continue
                if (word !in dictionary && word !in lexicon) continue
                if (SuggestionWordRules.isExcluded(word)) continue
                words[word] = Math.round(kotlin.math.ln(count + 1.0) / top * WordDictionary.MAX_SCORE).toInt()
            }
        }
        for ((word, score) in dictionaryWords()) words[word] = score
        return words.map { it.key to it.value }
    }

    /**
     * The corpus prior's bonus for a word after the previous one, exactly as the strip's ranker adds it to a
     * word's dictionary score (`AdaptiveNgramModel.predictScored`), for someone who has typed nothing yet.
     */
    class PriorContext(private val prior: CorpusPrior) {
        private val bonuses = HashMap<String, Map<String, Int>>()

        fun bonus(previous: String?, sentenceStart: Boolean, word: String): Int {
            val context = previous ?: if (sentenceStart) AdaptiveNgramModel.START else return 0
            return bonuses.getOrPut(context) {
                prior.followers(context).associate { follower ->
                    follower.word to AdaptiveNgramModel.priorBonus(follower.logProbability, 0, AdaptiveNgramModel.PriorWeights.DEFAULT)
                }.filterValues { it > 0 }
            }[word] ?: 0
        }

        companion object {
            fun bundled() = PriorContext(File("src/main/assets/corpus_prior.bin").inputStream().use { CorpusPrior.read(it) })
        }
    }

    fun score(
        swipes: Sequence<Swipe>, index: GestureIndex, vocabulary: String,
        settings: GestureDecoder.Settings = GestureDecoder.Settings(),
        trained: SwipeDecoder? = null,
        context: ((previous: String?, sentenceStart: Boolean, word: String) -> Float)? = null,
    ): Report {
        val decoder = GestureDecoder(index, settings)
        val report = Report(vocabulary, index.wordCount, settings)
        val scores = HashMap<String, Int>()
        for (group in index.groups) group.words.forEachIndexed { i, word -> scores[word] = group.scores[i] }
        var seen = 0
        for (swipe in swipes) {
            val target = target(swipe.word)
            val path = GestureIndex.path(target)
            if (path.isEmpty()) {
                report.notWords++
                continue
            }
            val inVocabulary = index.groupOf(target) >= 0
            // "?" is a row that does not say where its word sat: no context, which is not a sentence start.
            val bonus: ((String) -> Float)? = context?.takeIf { swipe.context != UNKNOWN_CONTEXT }?.let { rule ->
                val previous = AdaptiveNgramModel.lastCompletedWord(swipe.context)
                val start = AdaptiveNgramModel.isSentenceStart(swipe.context);
                { word: String -> rule(previous, start, word) }
            }
            val began = System.nanoTime()
            // The trained decoder scores every path in the vocabulary, so nothing is pruned and every known word survives.
            val reading = if (trained != null) GestureDecoder.Reading(
                trained.decode(swipe.xs, swipe.ys, swipe.ts, swipe.aspect, limit = 3, context = bonus), trained.nodes, inVocabulary,
            ) else decoder.decode(swipe.xs, swipe.ys, swipe.xs.size, swipe.aspect, limit = 3, context = bonus, probe = target)
            val took = System.nanoTime() - began
            // The first swipes pay for the JIT; a keyboard has long since warmed up.
            if (seen++ >= WARM_UP) report.nanos += took
            report.scored += reading.scored
            val words = reading.candidates.map { it.word }
            val first = words.firstOrNull() == target
            val top3 = target in words
            report.all.add(first, top3)
            val flagged = swipe.flags != "-"
            if (!flagged) report.unflagged.add(first, top3)
            if (inVocabulary && !flagged) report.unflaggedKnown.add(first, top3)
            if (!inVocabulary) {
                report.unknown++
                continue
            }
            report.known.add(first, top3)
            if (reading.probed) report.survived++
            if (reading.probed && !flagged) report.unflaggedSurvived++
            if (words.firstOrNull()?.let(GestureIndex::path) == path) report.samePath++
            val letters = GestureIndex.fold(target).length
            report.byLength.firstOrNull { letters in it.first }?.second?.add(first, top3)
            val frequency = scores[target] ?: 0
            report.byFrequency.firstOrNull { frequency in it.first }?.second?.add(first, top3)
        }
        return report
    }

    /**
     * The word a person was asked to swipe. The prompt shows it as the sentence has it, so "years." and
     * "However," arrive with their punctuation, which nobody swipes: it comes off both ends. Apostrophes
     * and hyphens inside the word stay.
     */
    fun target(shown: String): String = AdaptiveNgramModel.normalize(shown.trim { !it.isLetterOrDigit() })

    /** Reads a table written by `model/gestures.py`, one swipe at a time. Lines that do not parse are skipped. */
    fun read(file: File, limit: Int = Int.MAX_VALUE): Sequence<Swipe> = sequence {
        var taken = 0
        file.bufferedReader().use { reader ->
            for (line in reader.lineSequence()) {
                if (taken >= limit) break
                val swipe = parse(line) ?: continue
                taken++
                yield(swipe)
            }
        }
    }

    fun parse(line: String): Swipe? {
        val fields = line.split('\t')
        if (fields.size != 7) return null
        val aspect = fields[4].toFloatOrNull() ?: return null
        val points = fields[6].split(';').filter { it.isNotEmpty() }
        if (points.isEmpty() || aspect <= 0f) return null
        val xs = FloatArray(points.size)
        val ys = FloatArray(points.size)
        val ts = FloatArray(points.size)
        for ((i, point) in points.withIndex()) {
            val first = point.indexOf(',')
            val second = point.indexOf(',', first + 1)
            if (first <= 0 || second <= first) return null
            xs[i] = point.substring(0, first).toFloatOrNull() ?: return null
            ys[i] = point.substring(first + 1, second).toFloatOrNull() ?: return null
            ts[i] = point.substring(second + 1).toFloatOrNull() ?: return null
        }
        return Swipe(fields[0], fields[1], fields[2], fields[3], aspect, fields[5], xs, ys, ts)
    }

    /**
     * Swipes drawn by rule, for a floor that runs without the recordings: the finger aims near each key
     * of the word, not at its centre, cuts the corners, and wobbles. [spread] is how far from a key's
     * centre it aims, in key widths. They say nothing about how a person swipes.
     */
    fun synthetic(words: List<String>, spread: Float, seed: Int, aspect: Float = 1.35f): List<Swipe> {
        val random = Random(seed)
        val layout = GestureLayout.QWERTY
        return words.map { word ->
            val keys = GestureIndex.path(word)
            val offsetX = gaussian(random) * spread * 0.5f
            val offsetY = gaussian(random) * spread * 0.5f
            var xs = FloatArray(keys.length) { layout.x[keys[it] - 'a'] + offsetX + gaussian(random) * spread }
            var ys = FloatArray(keys.length) { (layout.row[keys[it] - 'a']) * aspect + offsetY + gaussian(random) * spread }
            repeat(2) {
                val cut = cutCorners(xs, ys)
                xs = cut.first
                ys = cut.second
            }
            val px = ArrayList<Float>()
            val py = ArrayList<Float>()
            for (i in xs.indices) {
                if (i > 0) {
                    val steps = maxOf(1, (kotlin.math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) / 0.2f).toInt())
                    for (s in 1 until steps) {
                        px += xs[i - 1] + (xs[i] - xs[i - 1]) * s / steps + gaussian(random) * 0.02f
                        py += ys[i - 1] + (ys[i] - ys[i - 1]) * s / steps + gaussian(random) * 0.02f
                    }
                }
                px += xs[i]
                py += ys[i]
            }
            Swipe("synthetic", "synthetic", word, "", aspect, "-", px.toFloatArray(), FloatArray(py.size) { py[it] / aspect })
        }
    }

    /** One round of Chaikin's corner cutting, keeping the two ends where they are. */
    private fun cutCorners(xs: FloatArray, ys: FloatArray): Pair<FloatArray, FloatArray> {
        if (xs.size < 3) return xs to ys
        val outX = ArrayList<Float>()
        val outY = ArrayList<Float>()
        outX += xs.first(); outY += ys.first()
        for (i in 0 until xs.size - 1) {
            outX += xs[i] * 0.75f + xs[i + 1] * 0.25f; outY += ys[i] * 0.75f + ys[i + 1] * 0.25f
            outX += xs[i] * 0.25f + xs[i + 1] * 0.75f; outY += ys[i] * 0.25f + ys[i + 1] * 0.75f
        }
        outX += xs.last(); outY += ys.last()
        return outX.toFloatArray() to outY.toFloatArray()
    }

    private fun gaussian(random: Random): Float {
        // Box-Muller, as TypoCorpus draws its taps.
        val u = random.nextDouble().coerceAtLeast(1e-9)
        val v = random.nextDouble()
        return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)).toFloat()
    }

    /** One file per configuration: [name] must say everything that was not a default, so no run replaces another's. */
    fun write(report: Report, name: String): File {
        val file = File("build/reports/gesture-eval/${name.lowercase(Locale.ROOT)}.txt")
        file.parentFile.mkdirs()
        file.writeText(report.lines().joinToString("\n", postfix = "\n"))
        return file
    }

    const val UNKNOWN_CONTEXT = "?"
    private const val WARM_UP = 500
}
