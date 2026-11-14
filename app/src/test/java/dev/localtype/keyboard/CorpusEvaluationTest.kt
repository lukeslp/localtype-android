package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The corpus scorer on hand-made lines: the tokenizer against `model/tests/test_tokenize.py`, the
 * sentence split, the per-source counts against `model/tests/test_corpus_counts.py`, hand-computable
 * metric arithmetic and the bootstrap. A real corpus split is scored only when `-Dlocaltype.corpus`
 * or `LOCALTYPE_CORPUS` is set. That run asserts structure, never a ranker's figure, so a ranker
 * change can't fail it; given `-Dlocaltype.cifgEval`, it also asserts the NUS and Taskmaster counts
 * no ranker affects against that cifg.py eval file.
 */
class CorpusEvaluationTest {
    private fun tokens(text: String) = CorpusText.tokenize(text).map { it.text }

    /** Always the same six words, filtered by the prefix: answers that can be scored by hand. */
    private class FixedRanker(
        override val name: String = "fixed",
        private val words: List<String> = listOf("the", "to", "and", "a", "i", "you"),
        override val vocabulary: Set<String>? = null,
    ) : CorpusRanker {
        override fun rank(context: String, prefix: String, limit: Int) = words.filter { it.startsWith(prefix) }.take(limit)
    }

    private fun column(vararg lines: String) = CorpusEvaluation.ColumnText(CorpusEvaluation.Column("test", "test", lines.toList()))

    @Test
    fun `the tokenizer matches the Python tokenizer's cases`() {
        val num = CorpusText.NUM
        val sym = CorpusText.SYM
        val cases = listOf(
            "Hello, world!" to listOf("hello", ",", "world", "!"),
            "Don’t you DARE" to listOf("don't", "you", "dare"),
            "e-mail rock'n'roll goin'" to listOf("e-mail", "rock'n'roll", "goin'"),
            "wait... what?!" to listOf("wait", "…", "what", "?", "!"),
            "no!!! way??" to listOf("no", "!", "way", "?"),
            "hmm…. ok" to listOf("hmm", "…", "ok"),
            "2nd at 12:30 on 9/11 for $1,000" to listOf(num, "nd", "at", num, "on", num, "for", "$", num),
            "50% off — really" to listOf(num, "%", "off", "-", "really"),
            "“quoted” and ‘this’" to listOf("\"", "quoted", "\"", "and", "'", "this'"),
            "coffee ☕ please" to listOf("coffee", "☕", "please"),
            "love ❤️ u" to listOf("love", "❤️", "u"),
            "dev 🧑‍💻 life" to listOf("dev", "🧑‍💻", "life"),
            "run 🏃‍♀️ now" to listOf("run", "🏃‍♀️", "now"),
            "go 🇺🇸 1️⃣ #tag @you" to listOf("go", "🇺🇸", "1️⃣", "#", "tag", "@", "you"),
            "café naïve über ünd" to listOf("café", "naïve", "über", "ünd"),
            "a\tb\n c" to listOf("a", "b", "c"),
            "¿Qué? ¡Sí!" to listOf("?", "qué", "?", "!", "sí", "!"),
            "weird © 5€ x" to listOf("weird", "©", num, sym, "x"),
            "Ⅻth x²" to listOf(sym, "th", "x", sym),
            "" to emptyList(),
            "   " to emptyList(),
            "..." to listOf("…"),
        )
        for ((text, expected) in cases) assertEquals(text, expected, tokens(text))
    }

    @Test
    fun `token spans cover a collapsed run`() {
        val run = CorpusText.tokenize("ok... so").first { it.text == "…" }
        assertEquals(2, run.start)
        assertEquals(5, run.end)
        val bangs = CorpusText.tokenize("no! ! way").first { it.text == "!" }
        assertEquals(5, bangs.end)
    }

    @Test
    fun `words are the device's words`() {
        for (token in CorpusText.tokenize("Hello there, it's a well-known e-mail from Zoë 2nd 12 …")) {
            if (token.kind != CorpusText.Kind.WORD) continue
            assertEquals(token.text, AdaptiveNgramModel.normalize(AdaptiveNgramModel.activePrefix(token.text)))
        }
    }

    @Test
    fun `each sentence is its own context`() {
        val sentences = CorpusText.sentences("Hi there. How are u?")
        assertEquals(listOf(listOf("hi", "there"), listOf("how", "are", "u")), sentences.map { s -> s.targets.map { it.word } })
        assertEquals(listOf("", "Hi "), sentences[0].targets.map { it.context })
        assertEquals(listOf("", "How ", "How are "), sentences[1].targets.map { it.context })
        assertTrue(sentences.all { it.phoneSeesStart })
    }

    @Test
    fun `a number's dot is not a sentence end and a run of dots is`() {
        val sentences = CorpusText.sentences("Meet at 12.30 ok... see u")
        assertEquals(2, sentences.size)
        assertEquals(listOf("meet", "at", "ok"), sentences[0].targets.map { it.word })
        assertEquals("Meet at 12.30 ", sentences[0].targets.last().context)
        assertEquals(listOf("", "see "), sentences[1].targets.map { it.context })
    }

    @Test
    fun `empty sentences vanish and a punctuation-only one counts without targets`() {
        val sentences = CorpusText.sentences("Done!! Really?! , . yes")
        assertEquals(listOf(listOf("done"), listOf("really"), emptyList(), listOf("yes")), sentences.map { s -> s.targets.map { it.word } })
        val text = column("Done!! Really?! , . yes")
        assertEquals(4, text.sentences.size)
        assertEquals(1, text.skipped[CorpusText.Kind.PUNCT])
    }

    @Test
    fun `a literal ellipsis opens a sentence the phone does not see`() {
        val sentences = CorpusText.sentences("wait… what. ok")
        assertEquals(listOf(true, false, true), sentences.map { it.phoneSeesStart })
        assertEquals(1, column("wait… what. ok").unseenStarts)
        // On the phone the same text gives no sentence start, which is the difference being counted.
        assertFalse(AdaptiveNgramModel.isSentenceStart("wait… "))
    }

    @Test
    fun `hand-computed metrics for a fixed ranker`() {
        val text = column("the cat u")
        val s = CorpusEvaluation.score(FixedRanker(), text)
        // the: rank 1 at p=0, saves 3; cat: never offered; u: one letter, a miss with baseline 2.
        assertEquals(3, s.targets)
        assertEquals(1, text.unofferable)
        assertEquals(10, s.baseline)
        assertEquals(3, s.saved)
        assertEquals(0.3, s.savings, 1e-12)
        assertEquals(1.0 / 3, s.nextHit1, 1e-12)
        assertEquals(1.0 / 3, s.nextHit3, 1e-12)
        assertEquals(1.0 / 3, s.mrr, 1e-12)
        assertEquals(2, text.ladder[1])
        assertEquals(2, text.ladder[2])
        assertEquals(0, text.ladder[3])
        assertEquals(0.5, s.completion(1), 1e-12)
        assertEquals(0.5, s.completion(2), 1e-12)
        assertEquals(0.5, s.perInstanceCompletion, 1e-12)
        assertEquals(listOf(3), s.savedByLine.toList())
    }

    @Test
    fun `rank six counts for hit@6 and MRR only, and savings come from the shortest hit`() {
        val s = CorpusEvaluation.score(FixedRanker(), column("you"))
        assertEquals(0.0, s.nextHit3, 0.0)
        assertEquals(1.0, s.nextHit6, 0.0)
        assertEquals(1.0 / 6, s.mrr, 1e-12)
        // "y" alone narrows the fixed list to "you": saved 3 - 1 = 2 of a baseline of 4.
        assertEquals(2, s.saved)
        assertEquals(0.5, s.savings, 1e-12)
    }

    @Test
    fun `answers the strip could never show are dropped without refilling the slot`() {
        val noisy = object : CorpusRanker {
            override val name = "noisy"
            override fun rank(context: String, prefix: String, limit: Int) = listOf("u", "the", "the", "and")
        }
        val s = CorpusEvaluation.score(noisy, column("and"))
        // Of the first three ("u", "the", "the") two are dropped, so "and" in fourth place never counts.
        assertEquals(0, s.hit3)
        assertEquals(1, s.hit6)
        assertTrue(s.dropped > 0)
    }

    @Test
    fun `a word longer than the ladder is credited from 17 only when the ranker knows it`() {
        val word = "pneumonoultramicroscopicsilicovolcanoconiosis"
        val text = column(word)
        assertEquals(word.length - 17, CorpusEvaluation.score(FixedRanker(), text).saved)
        assertEquals(0, CorpusEvaluation.score(FixedRanker(vocabulary = setOf("the")), text).saved)
        assertEquals(word.length - 17, CorpusEvaluation.score(FixedRanker(vocabulary = setOf(word)), text).saved)
        assertEquals(CorpusEvaluation.MAX_PREFIX, (1..CorpusEvaluation.MAX_PREFIX).count { text.ladder[it] == 1 })
    }

    @Test
    fun `in-vocabulary figures leave out the words a ranker cannot produce`() {
        val s = CorpusEvaluation.score(FixedRanker(vocabulary = setOf("the", "to", "and", "a", "i", "you")), column("the cat"))
        assertEquals(1, s.inVocab)
        assertEquals(1, s.inVocabHit3)
        assertEquals(3, s.inVocabSaved)
        assertEquals(4, s.inVocabBaseline)
    }

    @Test
    fun `the paired bootstrap is exact when one ranker wins every line by the same margin`() {
        val baseline = IntArray(50) { 10 }
        val same = CorpusEvaluation.pairedBootstrap(IntArray(50) { 2 }, IntArray(50) { 2 }, baseline)
        assertEquals(0.0, same.difference, 0.0)
        assertEquals(0.0, same.low, 0.0)
        assertEquals(0.0, same.high, 0.0)
        assertFalse(same.excludesZero)
        val better = CorpusEvaluation.pairedBootstrap(IntArray(50) { 2 }, IntArray(50) { 3 }, baseline)
        assertEquals(0.1, better.difference, 1e-12)
        assertEquals(0.1, better.low, 1e-12)
        assertEquals(0.1, better.high, 1e-12)
        assertTrue(better.excludesZero)
    }

    @Test
    fun `the paired bootstrap is seeded and brackets its point estimate`() {
        val baseline = IntArray(200) { 5 + it % 7 }
        val a = IntArray(200) { it % 3 }
        val b = IntArray(200) { (it * 7) % 4 }
        val first = CorpusEvaluation.pairedBootstrap(a, b, baseline)
        val again = CorpusEvaluation.pairedBootstrap(a, b, baseline)
        assertEquals(first.low, again.low, 0.0)
        assertEquals(first.high, again.high, 0.0)
        assertTrue(first.low <= first.difference && first.difference <= first.high)
        assertTrue(first.low < first.high)
        val flipped = CorpusEvaluation.pairedBootstrap(b, a, baseline)
        assertEquals(-first.difference, flipped.difference, 1e-12)
        assertNotEquals(first.low, CorpusEvaluation.pairedBootstrap(a, b, baseline, seed = 1).low, 0.0)
    }

    @Test
    fun `the cold device ranker answers as a fresh install with swear words off`() {
        val cold = CorpusDeviceRanker.cold()
        assertEquals("i", cold.rank("", "", 3).first())
        assertEquals("you", cold.rank("Thank ", "", 3).first())
        assertTrue(cold.rank("Thank ", "s", 3).all { it.startsWith("s") })
        // A comma resets context on the phone, so with nothing learned it ranks by frequency alone.
        assertEquals(CorpusDeviceRanker.frequencyOnly().rank("Hello, ", "", 6), cold.rank("Hello, ", "", 6))
        assertTrue("the" in cold.vocabulary)
        assertTrue(cold.vocabulary.none { !CorpusText.offerable(it) })
    }

    @Test
    fun `the prior ranker is the cold ranker plus the bundled prior`() {
        val cold = CorpusDeviceRanker.cold()
        val prior = CorpusDeviceRanker.withPrior()
        assertEquals("prior-w20", prior.name)
        assertEquals("christmas", prior.rank("Merry ", "", 3).first())
        assertNotEquals("christmas", cold.rank("Merry ", "", 3).first())
        assertTrue(prior.rank("Merry ", "c", 3).all { it.startsWith("c") })
        // Its vocabulary adds the prior's words (all in the dictionary already) but never the sentence-start marker.
        assertTrue(prior.vocabulary.containsAll(cold.vocabulary))
        assertFalse(CorpusPrior.SENTENCE_START in prior.vocabulary)
        // At weight 0 no follower earns a bonus, so it ranks exactly as the cold ranker does.
        val off = CorpusDeviceRanker.withPrior(AdaptiveNgramModel.PriorWeights(weight = 0f))
        for (context in listOf("", "Merry ", "I am on my ", "Thank ", "Hello, ", "see you s")) {
            assertEquals(context, cold.rank(context, "", 6), off.rank(context, "", 6))
        }
        assertEquals("prior-w2.5-floor-8-fade4", CorpusDeviceRanker.priorName(AdaptiveNgramModel.PriorWeights(2.5f, -8f, 4f)))
    }

    @Test
    fun `dev rates six prior settings at most, and test only the preregistered pair`() {
        assertTrue(CorpusEvaluation.PRIOR_SETTINGS.size <= 6)
        val dev = CorpusEvaluation.rankers("dev").map { it.name }
        assertEquals(listOf("device-cold", "frequency-only") + CorpusEvaluation.PRIOR_SETTINGS.map(CorpusDeviceRanker::priorName), dev.take(8))
        assertEquals(dev.size, dev.toSet().size)
        val selected = CorpusEvaluation.SELECTED_PRIOR
        if (selected == null) {
            assertTrue(runCatching { CorpusEvaluation.rankers("test") }.isFailure)
        } else if (CorpusDeviceRanker.bundledPrior.sha256 == CorpusEvaluation.GATED_PRIOR_SHA256) {
            assertTrue(selected in CorpusEvaluation.PRIOR_SETTINGS)
            assertEquals(listOf("device-cold", CorpusDeviceRanker.priorName(selected)), CorpusEvaluation.rankers("test").map { it.name })
        } else {
            // A rebuilt asset: the spent test split refuses it.
            assertTrue(runCatching { CorpusEvaluation.rankers("test") }.isFailure)
        }
    }

    @Test
    fun `the spent test split scores only the asset and setting it was spent on`() {
        val gated = CorpusEvaluation.GATED_PRIOR_SHA256
        assertNull(CorpusEvaluation.testSplitRefusal(gated, CorpusEvaluation.GATED_PRIOR))
        assertEquals(CorpusEvaluation.GATED_PRIOR, CorpusEvaluation.SELECTED_PRIOR)
        assertNotNull(CorpusEvaluation.testSplitRefusal(gated, null))
        val rebuilt = CorpusEvaluation.testSplitRefusal("0".repeat(64), CorpusEvaluation.GATED_PRIOR)
        assertTrue(rebuilt.orEmpty(), rebuilt.orEmpty().contains("new preregistered gate"))
        assertNotNull(CorpusEvaluation.testSplitRefusal(null, CorpusEvaluation.GATED_PRIOR))
        assertNotNull(CorpusEvaluation.testSplitRefusal(gated, AdaptiveNgramModel.PriorWeights(weight = 40f)))
    }

    @Test
    fun `the cifg ranker answers through the Kotlin port and keeps no state between answers`() {
        val file = CorpusCifgRanker.smokeV2File()
        assumeTrue("model-data volume not mounted: $file", file.isFile)
        val ranker = CorpusCifgRanker.smokeV2(file)!!
        val sentence = listOf("", "I ", "I am ", "I am on ", "I am on my ")
        val answers = sentence.map { ranker.rank(it, "", 6) }
        assertTrue(answers.all { it.size == 6 && it.all(CorpusText::offerable) })
        assertTrue(ranker.rank("I am on my ", "w", 3).all { it.startsWith("w") })
        // The session follows the sentence, but the answers are the ones a fresh model gives.
        val fresh = CorpusCifgRanker.smokeV2(file)!!
        assertEquals(answers.last(), fresh.rank("I am on my ", "", 6))
        assertEquals(answers.first(), fresh.rank("", "", 6))
        assertEquals(16_029, ranker.vocabulary.size)
        assertTrue("the" in ranker.vocabulary && "u" !in ranker.vocabulary)
    }

    @Test
    fun `the preregistered rule picks the best mean and the gate needs both columns`() {
        val (w5, w10) = CorpusEvaluation.PRIOR_SETTINGS.filter { it.weight == 5f || it.weight == 10f }
        val lines = listOf("the cat", "you there", "to and", "thanks a lot")
        val sources = listOf("nus", "nus", "taskmaster", "taskmaster")
        fun result(vararg rankers: CorpusRanker) = CorpusEvaluation.evaluate(CorpusEvaluation.columns(lines, sources), rankers.toList())
        val good = listOf("the", "you", "to", "and", "thanks", "there")
        // Never a next word in the top three, and later at every prefix than the good list, on every line.
        val weak = listOf("zebra", "zoo", "zap", "the", "to", "and")
        // A better mean wins whatever the weight.
        val picked = CorpusEvaluation.selectPrior(result(
            FixedRanker(CorpusDeviceRanker.priorName(w5), weak),
            FixedRanker(CorpusDeviceRanker.priorName(w10), good),
        ))!!
        assertEquals(w10, picked.weights)
        assertTrue(picked.mean > 0)
        // A tie goes to the lower weight.
        val tie = CorpusEvaluation.selectPrior(result(FixedRanker(CorpusDeviceRanker.priorName(w10), good), FixedRanker(CorpusDeviceRanker.priorName(w5), good)))!!
        assertEquals(w5, tie.weights)
        assertEquals(null, CorpusEvaluation.selectPrior(result(FixedRanker("device-cold"))))

        // Better on every line by a margin: savings and hit@3 both pass on both columns.
        val gate = CorpusEvaluation.priorGate(result(FixedRanker("device-cold", weak), FixedRanker("candidate", good)), "candidate")!!
        assertEquals(listOf("nus", "taskmaster"), gate.columns.map { it.column })
        assertTrue(gate.columns.all { it.savingsPass && it.hit3Pass })
        assertTrue(gate.passed)
        // The same ranker against itself: an interval of exactly zero doesn't pass.
        val same = CorpusEvaluation.priorGate(result(FixedRanker("device-cold", good), FixedRanker("candidate", good)), "candidate")!!
        assertFalse(same.passed)
        assertTrue(same.columns.all { !it.savingsPass && it.hit3Pass })
        assertEquals(null, CorpusEvaluation.priorGate(result(FixedRanker("device-cold")), "candidate"))
    }

    @Test
    fun `columns take every line of a human source and a seeded pooled sample`() {
        val lines = List(20) { "line $it" } + ""
        val sources = List(20) { listOf("nus", "taskmaster", "soda", "soda")[it % 4] } + "soda"
        val columns = CorpusEvaluation.columns(lines, sources, pooledLines = 6)
        assertEquals(listOf("nus", "taskmaster", "pooled"), columns.map { it.name })
        assertEquals(5, columns[0].lines.size)
        assertEquals(5, columns[1].lines.size)
        val pooled = columns[2].lines
        assertEquals(6, pooled.size)
        assertEquals(pooled, pooled.sortedBy { it.removePrefix("line ").toInt() })
        assertEquals(pooled, CorpusEvaluation.columns(lines, sources, pooledLines = 6)[2].lines)
        assertTrue("" !in pooled)
        val capped = CorpusEvaluation.columns(lines, sources, maxLines = 2, pooledLines = 6)
        assertEquals(listOf(2, 2, 2), capped.map { it.lines.size })
        // A cap draws fewer pooled lines rather than keeping the first of the sorted sample, and the
        // partial shuffle makes the same first picks, so a smaller draw is a subset of a larger one.
        val eligible = lines.indices.filter { lines[it].isNotEmpty() }
        assertEquals(CorpusEvaluation.sample(eligible, 2, CorpusEvaluation.SEED).map { lines[it] }, capped[2].lines)
        for (k in 0..6) assertTrue(pooled.containsAll(CorpusEvaluation.columns(lines, sources, maxLines = k + 1, pooledLines = 6)[2].lines))
        assertTrue(runCatching { CorpusEvaluation.columns(lines, sources.drop(1)) }.isFailure)
    }

    @Test
    fun `columns can be named by source label and the usual three stay as they were`() {
        val lines = List(12) { "line $it" } + ""
        val sources = List(12) { listOf("mobiletext", "nus", "taskmaster", "soda")[it % 4] } + "mobiletext"
        val chosen = CorpusEvaluation.columns(lines, sources, names = listOf("mobiletext", "nus", "soda"))
        assertEquals(listOf("mobiletext", "nus", "soda"), chosen.map { it.name })
        // A source with no display name goes by its label.
        assertEquals(listOf("phone-written", "NUS SMS", "soda"), chosen.map { it.label })
        assertEquals(listOf("line 0", "line 4", "line 8"), chosen[0].lines)
        val usual = CorpusEvaluation.columns(lines, sources, pooledLines = 5)
        assertEquals(listOf("nus", "taskmaster", "pooled"), usual.map { it.name })
        assertEquals(listOf("NUS SMS", "Taskmaster-1", "pooled sample"), usual.map { it.label })
        // A source column holds the same lines whatever else is asked for, and the pooled sample is the same draw.
        assertEquals(usual[0].lines, chosen[1].lines)
        assertEquals(usual[2].lines, CorpusEvaluation.columns(lines, sources, pooledLines = 5, names = listOf("pooled", "mobiletext"))[0].lines)
        assertEquals(listOf(2, 2), CorpusEvaluation.columns(lines, sources, maxLines = 2, names = listOf("mobiletext", "taskmaster")).map { it.lines.size })
        assertTrue(CorpusEvaluation.columns(lines, sources, names = listOf("nowhere")).single().lines.isEmpty())

        val dir = Files.createTempDirectory("corpus-columns").toFile()
        File(dir, "dev.txt").writeText("hello there\nsee you soon\n")
        File(dir, "dev.sources.txt").writeText("mobiletext\nnus\n")
        val named = CorpusEvaluation.Settings(dir, columns = listOf("mobiletext", "nus"))
        assertEquals(listOf(listOf("hello there"), listOf("see you soon")), CorpusEvaluation.columns(named).map { it.lines })
        // A run that names a label no line carries fails; with nothing named an absent source is an empty column, as before.
        val missing = runCatching { CorpusEvaluation.columns(CorpusEvaluation.Settings(dir, columns = listOf("mobiletext", "taskmaster"))) }
        assertTrue(missing.exceptionOrNull()?.message.orEmpty(), "labelled taskmaster" in missing.exceptionOrNull()?.message.orEmpty())
        assertEquals(listOf(1, 0, 2), CorpusEvaluation.columns(CorpusEvaluation.Settings(dir)).map { it.lines.size })
        dir.deleteRecursively()
    }

    @Test
    fun `column, ranker and miss-bin choices are for dev only and change nothing when absent`() {
        val dir = File("unused")
        val refused = listOf<() -> Any>(
            { CorpusEvaluation.Settings(dir, "test", columns = listOf("mobiletext")) },
            { CorpusEvaluation.Settings(dir, "test", rankers = listOf("device-cold")) },
            { CorpusEvaluation.Settings(dir, "test", missBins = listOf("device-cold")) },
            { CorpusEvaluation.Settings(dir, columns = emptyList()) },
            { CorpusEvaluation.Settings(dir, columns = listOf("nus", "nus")) },
            { CorpusEvaluation.Settings(dir, rankers = listOf("device-cold", "device-cold")) },
            { CorpusEvaluation.Settings(dir, missBins = listOf("device-cold", "device-cold")) },
        )
        for ((i, settings) in refused.withIndex()) assertTrue("case $i", runCatching(settings).isFailure)
        val plain = CorpusEvaluation.Settings(dir)
        assertNull(plain.columns)
        assertNull(plain.rankers)
        assertTrue(plain.missBins.isEmpty())
        // With nothing chosen each split scores its own list, and the test split's refusal is the one that was there.
        assertEquals(CorpusEvaluation.rankers("dev").map { it.name }, CorpusEvaluation.rankers(plain).map { it.name })
        assertEquals(
            runCatching { CorpusEvaluation.rankers("test").map { it.name } }.getOrNull(),
            runCatching { CorpusEvaluation.rankers(CorpusEvaluation.Settings(dir, "test")).map { it.name } }.getOrNull(),
        )
        // The shipped file is scored only when named, and the named rankers come in the order given.
        assertFalse(CorpusCifgRanker.SHIPPED in CorpusEvaluation.rankers("dev").map { it.name })
        val chosen = CorpusEvaluation.Settings(dir, rankers = listOf("chime-shipped", "prior-w20", "device-cold"))
        assertEquals(listOf("chime-shipped", "prior-w20", "device-cold"), CorpusEvaluation.rankers(chosen).map { it.name })
        val unknown = runCatching { CorpusEvaluation.rankers(CorpusEvaluation.Settings(dir, rankers = listOf("no-such-ranker"))) }
        assertTrue(unknown.exceptionOrNull()?.message.orEmpty(), "no ranker named no-such-ranker" in unknown.exceptionOrNull()?.message.orEmpty())
    }

    @Test
    fun `the shipped ranker is the app's own model file`() {
        val ranker = CorpusCifgRanker.shipped()
        assertEquals("chime-shipped", ranker.name)
        assertTrue(ranker.description, NeuralAsset.SHA256 in ranker.description)
        val model = File("src/main/assets/${NeuralAsset.FILE_NAME}").inputStream().use { NeuralAsset.load(it) }
        for (context in listOf("", "I am on my ", "Thank ")) {
            assertEquals(context, model.newSession().predict(context, 12).map { it.word }, ranker.rank(context, "", 12))
        }
        assertEquals(model.offerableCount, ranker.vocabulary.size)
        // Another file under that name is refused before it is parsed.
        val other = CorpusCifgRanker.shipped(File("src/test/fixtures/cifg_tiny.bin"))
        assertTrue(runCatching { other.rank("", "", 3) }.isFailure)
    }

    @Test
    fun `miss bins sort every word target by where the ranker left it`() {
        val words = listOf("the", "to", "and", "of", "in", "it", "is", "on", "at", "as", "be", "by", "we", "up")
        val ranker = FixedRanker("fixed", words, vocabulary = words.toSet())
        // the, to: in the first three. of: rank 4. by: rank 12. up: rank 14, a word it knows outside the twelve.
        // cat: not a word it has. u, twice: one letter, never offered.
        val text = column("the of by up cat u", "to u")
        val plain = CorpusEvaluation.score(ranker, text)
        assertNull(plain.missBins)
        val s = CorpusEvaluation.score(ranker, text, missBins = true)
        val bins = s.missBins!!
        assertEquals(12, bins.limit)
        assertEquals(listOf(2, 2, 1, 1, 2), listOf(bins.hits, bins.near, bins.beyond, bins.outOfVocabulary, bins.neverOffered))
        assertEquals(3, bins.cannotOffer)
        assertEquals(6, bins.misses)
        // Nothing leaves the denominator: it is the column's word targets, and the hits are the score's hit@3.
        assertEquals(text.targets.size, bins.targets)
        assertEquals(s.hit3, bins.hits)
        assertEquals(s.nextHit3, bins.hits.toDouble() / bins.targets, 1e-12)
        // The extra answer changes no figure of the score.
        fun figures(x: CorpusEvaluation.Score) =
            listOf(x.hit1, x.hit3, x.hit6, x.saved, x.dropped, x.inVocab, x.inVocabHit3, x.inVocabSaved) + x.completionHits.toList() + x.savedByLine.toList()
        assertEquals(figures(plain), figures(s))
        assertEquals(plain.mrr, s.mrr, 0.0)

        // A candidate the strip could never show leaves its slot empty in the twelve too, and isn't counted as dropped twice.
        val noisy = object : CorpusRanker {
            override val name = "noisy"
            override val vocabulary = setOf("the", "and")
            override fun rank(context: String, prefix: String, limit: Int) = listOf("u", "the", "the", "and")
        }
        val fourth = CorpusEvaluation.score(noisy, column("and"), missBins = true)
        assertEquals(listOf(0, 1, 0, 0, 0), fourth.missBins!!.let { listOf(it.hits, it.near, it.beyond, it.outOfVocabulary, it.neverOffered) })
        assertEquals(CorpusEvaluation.score(noisy, column("and")).dropped, fourth.dropped)
        // Without a vocabulary a miss can't be told from a word the ranker doesn't have.
        assertTrue(runCatching { CorpusEvaluation.score(FixedRanker(), text, missBins = true) }.isFailure)
        assertTrue(runCatching { CorpusEvaluation.evaluate(listOf(text.column), listOf(ranker), missBins = listOf("absent")) }.isFailure)
    }

    @Test
    fun `miss bins on the tiny model are what asking it directly gives`() {
        fun tiny() = File("src/test/fixtures/cifg_tiny.bin").inputStream().use { CifgModel.load(it) }
        val ranker = CorpusCifgRanker("tiny", "the committed tiny model") { tiny() }
        // Thirteen of its words can be offered, so one of them is outside every twelve. zebra and later are
        // outside its vocabulary, and u is in it but is one letter.
        val lines = listOf("Hello we don't help you", "thanks that it u", "hey zebra to the a i", "I help. You don't! hello later")
        val columns = CorpusEvaluation.columns(lines, List(lines.size) { "mobiletext" }, names = listOf("mobiletext"))
        val result = CorpusEvaluation.evaluate(columns, listOf(ranker, FixedRanker()), missBins = listOf("tiny"))
        val column = result.column("mobiletext")!!
        val s = column.score("tiny")!!
        val bins = s.missBins!!
        assertNull(column.score("fixed")!!.missBins)
        assertEquals(13, ranker.vocabulary.size)

        val model = tiny()
        val expected = CorpusEvaluation.MissBins()
        for (t in column.text.targets) {
            if (!t.offerable) {
                expected.neverOffered++
                continue
            }
            // A new session each time: where the target sits doesn't depend on what the ranker was asked before.
            expected.add(model.newSession().predict(t.context, 12).map { it.word }.indexOf(t.word), t.word in ranker.vocabulary)
        }
        fun parts(b: CorpusEvaluation.MissBins) = listOf(b.hits, b.near, b.beyond, b.outOfVocabulary, b.neverOffered)
        assertEquals(parts(expected), parts(bins))
        assertEquals(column.text.targets.size, bins.targets)
        assertEquals(21, bins.targets)
        assertEquals(1, bins.neverOffered)
        assertEquals(2, bins.outOfVocabulary)
        assertEquals(18, bins.hits + bins.near + bins.beyond)
        assertEquals(s.hit3, bins.hits)
        // The tiny model's scores without the bins are the same scores.
        val plain = CorpusEvaluation.evaluate(columns, listOf(CorpusCifgRanker("tiny", "") { tiny() })).column("mobiletext")!!.score("tiny")!!
        assertEquals(listOf(plain.hit1, plain.hit3, plain.hit6, plain.saved, plain.dropped), listOf(s.hit1, s.hit3, s.hit6, s.saved, s.dropped))

        val report = result.report()
        assertTrue(report, "phone-written (mobiletext): 4 lines" in report)
        assertTrue(report, "caveat: the phone-written lines" in report)
        assertTrue(report, "next-word answers of tiny by where the target sat in its 12 best" in report)
        assertTrue(report, "(outside its vocabulary 2; one-letter words the strip never offers 1)" in report)
        assertFalse(report, "next-word answers of fixed" in report)
    }

    @Test
    fun `a run of chosen columns and rankers says so, and the usual run reads as before`() {
        val dir = Files.createTempDirectory("corpus-chosen").toFile()
        File(dir, "dev.txt").writeText(listOf("Thank you so much!", "I am on my way. See you soon", "ok lor... wat u doing?", "Can we talk later", "Merry Christmas").joinToString("\n") + "\n")
        File(dir, "dev.sources.txt").writeText(listOf("nus", "mobiletext", "nus", "taskmaster", "mobiletext").joinToString("\n") + "\n")
        val settings = CorpusEvaluation.Settings(
            dir, columns = listOf("mobiletext", "nus", "taskmaster"),
            rankers = listOf("device-cold", "prior-w20", "chime-shipped"), missBins = listOf("chime-shipped"),
        )
        val result = CorpusEvaluation.evaluate(settings)
        assertEquals(listOf("mobiletext", "nus", "taskmaster"), result.results.map { it.text.column.name })
        assertEquals(listOf("device-cold", "prior-w20", "chime-shipped"), result.results.first().scores.map { it.ranker.name })
        val report = result.report()
        assertTrue(report, "columns: every line of the split labelled mobiletext (phone-written), nus (NUS SMS), taskmaster (Taskmaster-1)\n" in report)
        assertTrue(report, "ranker chime-shipped: the bundled cifg_int8.bin (SHA-256 ${NeuralAsset.SHA256})" in report)
        assertTrue(report, "chime-shipped minus prior-w20: savings" in report)
        assertEquals(3, Regex("next-word answers of chime-shipped").findAll(report).count())
        assertFalse(report, "pooled sample" in report)
        // The choice among the prior's settings and its gate belong to the whole dev list.
        assertFalse(report, "preregistered" in report)
        for (column in result.results) {
            val bins = column.score("chime-shipped")!!.missBins!!
            assertEquals(column.text.targets.size, bins.targets)
            assertEquals(column.score("chime-shipped")!!.hit3, bins.hits)
        }

        // The same files with nothing chosen: the columns and the wording the report always had, and no bins.
        val plain = CorpusEvaluation.Settings(dir)
        val usual = CorpusEvaluation.evaluate(CorpusEvaluation.columns(plain), listOf(CorpusDeviceRanker.cold(), CorpusDeviceRanker.withPrior()), plain)
        assertEquals(listOf("nus", "taskmaster", "pooled"), usual.results.map { it.text.column.name })
        val before = usual.report()
        assertTrue(before, "columns: every NUS SMS and Taskmaster-1 line of the split; pooled = 5 lines sampled with seed ${CorpusEvaluation.SEED} (not cifg.py's numpy sample)\n" in before)
        assertTrue(before, "preregistered prior gate, prior-w20 against device-cold" in before)
        assertFalse(before, "next-word answers of" in before)
        assertFalse(before, "phone-written" in before)
        // A column's figures and intervals are its own: NUS scores the same beside the phone-written column as without it.
        val a = result.column("nus")!!
        val b = usual.column("nus")!!
        for (name in listOf("device-cold", "prior-w20")) {
            assertEquals(b.score(name)!!.let { listOf(it.hit1, it.hit3, it.hit6, it.saved, it.baseline) }, a.score(name)!!.let { listOf(it.hit1, it.hit3, it.hit6, it.saved, it.baseline) })
        }
        val (savingsA, hit3A) = a.interval("device-cold", "prior-w20")!!
        val (savingsB, hit3B) = b.interval("device-cold", "prior-w20")!!
        assertEquals(listOf(savingsB.difference, savingsB.low, savingsB.high), listOf(savingsA.difference, savingsA.low, savingsA.high))
        assertEquals(listOf(hit3B!!.difference, hit3B.low, hit3B.high), listOf(hit3A!!.difference, hit3A.low, hit3A.high))
        dir.deleteRecursively()
    }

    @Test
    fun `per-source counts print as model corpus_counts prints them`() {
        // The same lines and lines of output as model/tests/test_corpus_counts.py. 𝑥 is one code point
        // but two UTF-16 units, so, as String.length in AdaptiveNgramModel.predict sees it, not one-letter.
        val lines = listOf(
            "Hi u. How are you?? ok 🙂 at 12.30",
            "Done!! Really?! , . yes",
            "   ",
            "wait… what. k lol ☕ 5€",
            "pneumonoultramicroscopicsilicovolcanoconiosis 𝑥",
        )
        val sources = listOf("nus", "taskmaster", "soda", "nus", "soda")
        val expected = listOf(
            "nus lines=2 sentences=6 targets=11 one_letter=2 skipped=5 punct=0 num=2 emoji=2 sym=1 p1=9 p2=6 p3=2 p4=0 p5+=0 baseline=39",
            "taskmaster lines=1 sentences=4 targets=3 one_letter=0 skipped=1 punct=1 num=0 emoji=0 sym=0 p1=3 p2=3 p3=2 p4=1 p5+=1 baseline=16",
            "soda lines=1 sentences=1 targets=2 one_letter=0 skipped=0 punct=0 num=0 emoji=0 sym=0 p1=1 p2=1 p3=1 p4=1 p5+=12 baseline=48",
        )
        // Summing over chunks of one line gives what one chunk gives.
        for (chunk in listOf(1, 5_000)) {
            assertEquals(expected, CorpusEvaluation.sourceCounts(lines, sources, chunk).map { (source, c) -> c.line(source) })
        }
        assertFalse(CorpusText.hasToken(" \t\u00A0\u2003\u001F"))
        assertTrue(CorpusText.hasToken(" \u00AD"))
        val dir = Files.createTempDirectory("corpus-counts").toFile()
        val file = CorpusEvaluation.writeCounts(CorpusEvaluation.sourceCounts(lines, sources), "dev", dir)
        assertEquals(expected.joinToString("") { it + "\n" }, file.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `the cifg check compares the counts no ranker affects`() {
        val lines = listOf("Hi u. How are you?? ok 🙂 at 12.30", "wait… what. k lol ☕ 5€", "Done!! Really?! , . yes")
        val counts = CorpusEvaluation.sourceCounts(lines, listOf("nus", "nus", "taskmaster"))
        fun column(lines: Int, items: Int, targets: Int, unofferable: Int, oov: Int, skipped: Int, ladder: List<Int>) =
            """{"lines": $lines, "items": $items, "targets": $targets, "unofferable_one_letter_targets": $unofferable, "oov_targets": $oov,
               "non_word_targets_skipped": $skipped, "next": {"hit1": NaN, "mrr6": 0.5e-1},
               "completion": {${ladder.withIndex().joinToString { (i, n) -> "\"${if (i == 4) "5+" else "${i + 1}"}\": {\"hit3\": 0.25, \"n\": $n}" }}}}"""
        fun eval(split: String, nus: String) = Files.createTempFile("eval", ".json").toFile().apply {
            writeText("""{"run": "x\"y\u00e9", "split": "$split", "params": 2025114, "flags": [true, false, null, -1.5E3, []],
                "keyboard": {"line": {}, "sentence": {"nus": $nus, "taskmaster": ${column(1, 4, 3, 0, 0, 1, listOf(3, 3, 2, 1, 1))}}}}""")
            deleteOnExit()
        }
        val good = column(2, 6, 11, 1, 1, 5, listOf(9, 6, 2, 0, 0))
        assertEquals(emptyList<String>(), CorpusEvaluation.cifgMismatches(counts, eval("dev", good), "dev"))
        assertEquals(null, CorpusEvaluation.cifgMismatches(counts, eval("test", good), "dev"))
        // One more sentence, a ladder count off by one and a one-letter gap that OOV can't explain.
        val bad = CorpusEvaluation.cifgMismatches(counts, eval("dev", column(2, 7, 11, 0, 1, 5, listOf(9, 6, 2, 0, 1))), "dev")!!
        assertEquals(3, bad.size)
        assertTrue(bad.toString(), bad.any { "sentences" in it } && bad.any { "p=5+" in it } && bad.any { "one-letter" in it })
    }

    @Test
    fun `a small corpus produces a report with every ranker and every pair`() {
        val lines = listOf("Thank you so much!", "I am on my way. See you soon", "ok lor... wat u doing?", "Can we talk later")
        val sources = listOf("nus", "nus", "taskmaster", "soda")
        val result = CorpusEvaluation.evaluate(
            CorpusEvaluation.columns(lines, sources),
            listOf(CorpusDeviceRanker.cold(), CorpusDeviceRanker.frequencyOnly(), FixedRanker()),
        )
        val report = result.report()
        assertTrue(report, "device-cold" in report && "frequency-only" in report && "fixed" in report)
        assertEquals(3, result.results.first().intervals.size)
        assertEquals(3, result.results.first().hit3Intervals.size)
        assertTrue(report, "frequency-only minus device-cold: savings" in report && "; next hit@3" in report)
        val dir = Files.createTempDirectory("corpus-eval").toFile()
        val file = result.writeReport(dir)
        assertEquals(report, file.readText())
        dir.deleteRecursively()
    }

    @Test
    fun `a corpus split scores when a path is given`() {
        val settings = CorpusEvaluation.Settings.fromSystem()
        assumeTrue("set -Dlocaltype.corpus=<model-data>/corpus or LOCALTYPE_CORPUS to score a corpus split", settings != null)
        val result = CorpusEvaluation.evaluate(settings!!)
        val file = result.writeReport()
        println(result.report())
        println("report: ${file.path}; seconds per ranker: " + result.seconds.entries.joinToString { "${it.key} %.1f".format(it.value) })
        // Every line of the split, whatever the cap: diff this file against `python -m model.corpus_counts <split>`.
        val counts = CorpusEvaluation.sourceCounts(settings)
        val countsFile = CorpusEvaluation.writeCounts(counts, settings.split)
        counts.forEach { (source, c) -> println(c.line(source)) }
        println("counts: ${countsFile.path}")
        val cifgEval = System.getProperty("localtype.cifgEval")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
        if (cifgEval != null) {
            val mismatches = CorpusEvaluation.cifgMismatches(counts, cifgEval, settings.split)
            if (mismatches == null) {
                println("cifg check skipped: ${cifgEval.path} is not a ${settings.split} eval")
            } else {
                assertTrue("counts differ from ${cifgEval.path}:\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
                println("cifg check: NUS and Taskmaster counts match ${cifgEval.path}")
            }
        }
        assertTrue(file.path.replace('\\', '/').endsWith("build/reports/corpus-eval/${settings.split}.txt"))
        assertEquals(settings.columns ?: listOf("nus", "taskmaster", "pooled"), result.results.map { it.text.column.name })
        assertEquals(listOf("nus", "taskmaster", "pooled"), CorpusEvaluation.DEFAULT_COLUMNS)
        for (column in result.results) {
            assertTrue("no targets in ${column.text.column.name}", column.text.targets.isNotEmpty())
            for (s in column.scores) assertTrue("${s.ranker.name} savings ${s.savings}", s.savings in 0.0..1.0)
        }
        assertTrue(File(settings.dir, "${settings.split}.txt").isFile)
    }
}
