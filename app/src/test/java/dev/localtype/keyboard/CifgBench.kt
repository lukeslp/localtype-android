package dev.localtype.keyboard

import java.io.File
import java.util.Locale
import java.util.Random

/**
 * Times [CifgModel] the way a keyboard would call it, on whatever VM runs it: the JVM on the Mac, or
 * ART on a phone through `scripts/bench-cifg-device.sh`. Not a test, and nothing here uses JUnit or
 * Android, so it dexes with the model and the Kotlin standard library alone.
 *
 *     CifgBench <cifg_int8.bin> <lines.txt> [contexts=600] [seed=20261003] [passes=3] [long=30]
 *
 * Each context is a corpus line cut at the start of a random word: once right there (the next word,
 * no prefix) and once one to three letters into the word (a typed prefix). Every figure is wall time
 * from [System.nanoTime] around one [CifgModel.Session.predict] call for six candidates:
 *
 * - **full**: from a reset state, so encoding, a cell step for every context token and the ranking.
 *   This is the stateless path.
 * - **next word**: the session already holds the context up to the previous word, as it would while
 *   someone types a sentence, so usually one or two steps plus the ranking over every word.
 * - **keystroke**: the session holds this exact context and only the prefix grew, so no step, and only
 *   the words in the prefix's range are scored.
 *
 * Dev sentences never reach the 63-token context window (the longest has 60 tokens), so `long` more
 * are made from 128 consecutive corpus words with no punctuation between them: one token per word.
 * A session walks each one word by word, untimed up to word 63, and then times words 64 to 127:
 *
 * - **long, state kept**: the window as the model uses it ([CifgTokenizer.windowStart]). Most words
 *   take one step; the words that move the window, at 64 and 96, re-read it (33 steps).
 * - **long, sliding window**: the same walk with a window that moves one token per word (hop 1), so
 *   every word re-reads 64 tokens. That was the rule before the hop, kept here for comparison.
 * - **long, full window from reset**: 64 steps at 63, 95 and 127 words, the stateless worst case.
 *
 * Every pass prints its own figures with its mean cell steps. Pass 1 starts on a cold VM, in the
 * interpreter, and is labelled cold; the later passes are labelled warm, and when there are two or
 * more of them a summary at the end pools the warm passes only. Heap figures are `Runtime` used
 * memory after an explicit collection, so they are approximate on any VM.
 */
object CifgBench {
    private class Case(val previous: String, val nextWord: String, val typed: String)

    private const val LONG_WORDS = 128
    private const val TIMED_FROM = CifgTokenizer.CONTEXT_TOKENS
    private val FULL_WINDOW_AT = intArrayOf(63, 95, 127)

    /** Wall times of one mode, with the cell steps they ran. */
    private class Series(val label: String) {
        var nanos = LongArray(256)
        var size = 0
        var steps = 0L

        fun add(time: Long, cellSteps: Int) {
            if (size == nanos.size) nanos = nanos.copyOf(size * 2)
            nanos[size++] = time
            steps += cellSteps
        }

        fun addAll(other: Series) {
            for (i in 0 until other.size) add(other.nanos[i], 0)
            steps += other.steps
        }
    }

    private val MODES = listOf(
        "full, next word (no prefix)",
        "full, typed prefix",
        "next word, state kept",
        "keystroke, state kept",
        "long, state kept, words 64-127",
        "  of which the window moved",
        "long, sliding window, words 64-127",
        "long, full window from reset",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "usage: CifgBench <cifg_int8.bin> <lines.txt> [contexts] [seed] [passes] [long]" }
        val modelFile = File(args[0])
        val wanted = args.getOrNull(2)?.toInt() ?: 600
        val seed = args.getOrNull(3)?.toLong() ?: 20261003L
        val passes = args.getOrNull(4)?.toInt() ?: 3
        val longCount = args.getOrNull(5)?.toInt() ?: 30
        val runtime = Runtime.getRuntime()

        println("vm ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}, ${System.getProperty("os.arch")}, ${runtime.availableProcessors()} cores, max heap ${mib(runtime.maxMemory())}")
        val heapBefore = usedAfterGc()
        val loadStart = System.nanoTime()
        val model = modelFile.inputStream().use { CifgModel.load(it) }
        val loadNanos = System.nanoTime() - loadStart
        val heapModel = usedAfterGc() - heapBefore
        println("model ${modelFile.name}: ${modelFile.length()} bytes, vocabulary ${model.vocabSize}, embedding ${model.embedDim}, hidden ${model.hidden}, ${model.offerableCount} offerable words")
        println("load ${ms(loadNanos)} ms (read, checksum, vocabulary maps); heap held by the model ${mib(heapModel)}")

        val session = model.newSession()
        val sliding = model.newSession()
        println("heap held by the model and two sessions ${mib(usedAfterGc() - heapBefore)}")
        val random = Random(seed)
        val lines = File(args[1]).readLines()
        val cases = cases(lines, wanted, random)
        require(cases.size >= wanted) { "only ${cases.size} usable lines for $wanted contexts" }
        val longs = longSentences(lines, longCount, random)

        var start = System.nanoTime()
        val first = session.predict(cases[0].nextWord, 6)
        val firstNanos = System.nanoTime() - start
        println("first call ${ms(firstNanos)} ms: '${cases[0].nextWord.takeLast(40)}' -> ${first.joinToString(" ") { it.word }}")
        println("${cases.size} contexts; ${longs.size} long sentences of $LONG_WORDS words, timed from word $TIMED_FROM")

        val warm = MODES.map(::Series)
        val cpus = sortedMapOf<Int, Int>()
        val clocks = ArrayList<Long>()
        fun sample() {
            currentCpu()?.let { cpu ->
                cpus[cpu] = (cpus[cpu] ?: 0) + 1
                clock(cpu, "scaling_cur_freq")?.let(clocks::add)
            }
        }
        for (pass in 1..passes) {
            val s = MODES.map(::Series)
            cpus.clear()
            clocks.clear()
            for (case in cases) {
                sample()
                session.reset()
                start = System.nanoTime()
                session.predict(case.nextWord, 6)
                s[0].add(System.nanoTime() - start, session.lastSteps)

                session.reset()
                start = System.nanoTime()
                session.predict(case.typed, 6)
                s[1].add(System.nanoTime() - start, session.lastSteps)

                session.reset()
                session.predict(case.previous, 6)
                start = System.nanoTime()
                session.predict(case.nextWord, 6)
                s[2].add(System.nanoTime() - start, session.lastSteps)

                start = System.nanoTime()
                session.predict(case.typed, 6)
                s[3].add(System.nanoTime() - start, session.lastSteps)
            }
            for (words in longs) {
                sample()
                session.reset()
                sliding.reset()
                val text = StringBuilder()
                for (n in 1 until LONG_WORDS) {
                    text.append(words[n - 1]).append(' ')
                    val before = text.toString()
                    if (n < TIMED_FROM) {
                        session.advance(model.encode(before).ids)
                        sliding.advance(encode(model, before, hop = 1).ids)
                        continue
                    }
                    start = System.nanoTime()
                    session.predict(before, 6)
                    val time = System.nanoTime() - start
                    s[4].add(time, session.lastSteps)
                    if (session.lastSteps > 1) s[5].add(time, session.lastSteps)

                    start = System.nanoTime()
                    val encoded = encode(model, before, hop = 1)
                    sliding.advance(encoded.ids)
                    sliding.topWords(encoded.prefix, 6)
                    s[6].add(System.nanoTime() - start, sliding.lastSteps)
                }
                for (n in FULL_WINDOW_AT) {
                    val before = words.subList(0, n).joinToString(" ") + " "
                    session.reset()
                    start = System.nanoTime()
                    session.predict(before, 6)
                    s[7].add(System.nanoTime() - start, session.lastSteps)
                }
            }
            println(if (pass == 1) "pass 1 (cold), ms p50 / p95 / max:" else "pass $pass (warm), ms p50 / p95 / max:")
            s.forEach(::report)
            if (cpus.isNotEmpty()) {
                println("  cpu between contexts (its top clock): " + cpus.entries.joinToString(", ") { (cpu, n) -> "cpu$cpu (${ghz(clock(cpu, "cpuinfo_max_freq"))}) x$n" })
            }
            if (clocks.isNotEmpty()) {
                clocks.sort()
                println("  that cpu's current clock between contexts, p5/p50/p95: ${ghz(clocks[clocks.size / 20])} / ${ghz(clocks[clocks.size / 2])} / ${ghz(clocks[clocks.size * 19 / 20])}")
            }
            if (pass > 1) warm.zip(s).forEach { (pooled, one) -> pooled.addAll(one) }
        }
        if (passes > 2) {
            println("warm passes 2-$passes pooled, ms p50 / p95 / max:")
            warm.forEach(::report)
        }
    }

    /** [CifgModel.encode] with another window hop, for the sliding-window comparison. */
    private fun encode(model: CifgModel, beforeCursor: String, hop: Int): CifgModel.Encoded {
        val context = CifgTokenizer.context(beforeCursor, hop = hop)
        val ids = IntArray(context.tokens.size + 1)
        ids[0] = model.bosId
        context.tokens.forEachIndexed { i, token -> ids[i + 1] = model.idOf(token) }
        return CifgModel.Encoded(ids, context.prefix)
    }

    /** Lines with at least two words, each cut at a random word after the first. */
    private fun cases(lines: List<String>, wanted: Int, random: Random): List<Case> {
        val usable = lines.mapNotNull { line ->
            val words = AdaptiveNgramModel.WORD_REGEX.findAll(line).toList()
            if (words.size < 2) null else line to words
        }
        val picked = usable.shuffled(random).take(wanted)
        return picked.map { (line, words) ->
            val k = 1 + random.nextInt(words.size - 1)
            val word = words[k]
            val typed = 1 + random.nextInt(minOf(3, word.value.length))
            Case(
                previous = line.substring(0, words[k - 1].range.first),
                nextWord = line.substring(0, word.range.first),
                typed = line.substring(0, word.range.first + typed),
            )
        }
    }

    /** [count] runs of [LONG_WORDS] consecutive words from random places in [lines], punctuation dropped. */
    private fun longSentences(lines: List<String>, count: Int, random: Random): List<List<String>> {
        if (count <= 0) return emptyList()
        val words = lines.flatMap { line -> AdaptiveNgramModel.WORD_REGEX.findAll(line).map { it.value }.toList() }
        require(words.size > 2 * LONG_WORDS) { "only ${words.size} words for long sentences" }
        return List(count) {
            val from = random.nextInt(words.size - LONG_WORDS)
            words.subList(from, from + LONG_WORDS)
        }
    }

    private fun report(series: Series) {
        if (series.size == 0) return
        val sorted = series.nanos.copyOf(series.size).also { it.sort() }
        fun rank(p: Double) = sorted[maxOf(0, kotlin.math.ceil(p * sorted.size).toInt() - 1)]
        val steps = "%.2f".format(Locale.ROOT, series.steps.toDouble() / series.size)
        println("  ${series.label.padEnd(36)} ${ms(rank(0.5))} / ${ms(rank(0.95))} / ${ms(sorted.last())}  (${series.size} calls, $steps steps each)")
    }

    /**
     * The CPU this thread last ran on (field 39 of /proc/thread-self/stat), read between timed calls,
     * so a figure can be tied to a core cluster. Null where there is no procfs.
     */
    private fun currentCpu(): Int? = runCatching {
        val stat = File("/proc/thread-self/stat").readText()
        stat.substring(stat.lastIndexOf(')') + 2).split(' ')[36].toInt()
    }.getOrNull()

    /** A cpufreq value in kHz, where sysfs lets an unprivileged process read it. */
    private fun clock(cpu: Int, file: String): Long? =
        runCatching { File("/sys/devices/system/cpu/cpu$cpu/cpufreq/$file").readText().trim().toLong() }.getOrNull()

    private fun ghz(khz: Long?): String = khz?.let { "%.2f GHz".format(Locale.ROOT, it / 1e6) } ?: "?"

    private fun usedAfterGc(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun ms(nanos: Long) = "%.3f".format(Locale.ROOT, nanos / 1e6)

    private fun mib(bytes: Long) = "%.2f MiB".format(Locale.ROOT, bytes / 1048576.0)
}
