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
        assertEquals(listOf("nus", "taskmaster", "pooled"), result.results.map { it.text.column.name })
        for (column in result.results) {
            assertTrue("no targets in ${column.text.column.name}", column.text.targets.isNotEmpty())
            for (s in column.scores) assertTrue("${s.ranker.name} savings ${s.savings}", s.savings in 0.0..1.0)
        }
        assertTrue(File(settings.dir, "${settings.split}.txt").isFile)
    }
}
