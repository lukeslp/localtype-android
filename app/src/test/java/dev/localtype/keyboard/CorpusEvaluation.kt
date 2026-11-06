package dev.localtype.keyboard

import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Something the corpus scorer can rate: given the current sentence so far and the start of the
 * next word, the words it would put in the strip, best first.
 *
 * This is the stateless cousin of the log replay's [Ranker]. A corpus line comes from many people,
 * so there is nothing to learn between answers, and every ranker sees the same sentence text.
 */
interface CorpusRanker {
    /** Column heading in the report: short and unique. */
    val name: String

    /** One line naming the ranker's fixed choices for the report header. */
    val description: String get() = ""

    /**
     * Every word this ranker can produce at all, for the in-vocabulary-only figures that cifg.py
     * reports beside the all-target ones. Null for a ranker without a closed list. A target outside
     * the set still counts as a miss in every headline figure.
     */
    val vocabulary: Set<String>? get() = null

    /**
     * Up to [limit] candidates for the word after [context] that starts with [prefix], best first,
     * as normalized words ([AdaptiveNgramModel.normalize]).
     *
     * [context] is the current sentence up to the word, exactly as the corpus line has it: case,
     * punctuation and inner spacing kept, leading whitespace dropped, empty at a sentence start. It
     * holds no sentence-ending token as [CorpusText.tokenize] reads it, though a dot inside a number
     * (12.30) can appear; tokenize the context with [CorpusText.tokenize] rather than scanning for
     * marks. [prefix] is the first p characters of the normalized target, empty for the next-word
     * slot. The scorer drops any candidate the keyboard could never show (a one-letter word other
     * than a and i, a word not starting with [prefix], a repeat) without refilling its slot, and
     * counts it in the report.
     *
     * The calls for one target come together, prefix 0 first and then 1, 2, 3 and on, all with the
     * same [context]. Targets come in order through each sentence, so within a sentence every context
     * extends the one before it. A ranker that runs a network over the context can keep its last
     * state and answer every prefix from it.
     */
    fun rank(context: String, prefix: String, limit: Int): List<String>
}

/**
 * The shipped ranker: an [AdaptiveNgramModel] asked exactly as refreshCandidates asks it, with the
 * text before the cursor, which here is the sentence so far plus the typed prefix. [build] runs
 * once, on first use. A variant of the shipped model, such as one carrying the corpus prior
 * ([withPrior]), is another instance with its own [build], so it is scored through the same predict path.
 */
class CorpusDeviceRanker(
    override val name: String,
    override val description: String = "",
    /** The one-letter words the model's language may offer ([LanguageProfile.oneLetterWords]); English's by default. */
    private val oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords,
    build: () -> AdaptiveNgramModel,
) : CorpusRanker {
    private val model by lazy(build)

    /** The dictionary plus every word the seed phrases, starters and prior can offer through context. */
    override val vocabulary: Set<String> by lazy {
        val tables = model.tables()
        val prior = model.prior?.allWords().orEmpty().filter { it != CorpusPrior.SENTENCE_START }
        (model.dictionary.allWords() + tables.unigrams.keys + tables.bigrams.map { it.second } + prior)
            .filterTo(HashSet()) { CorpusText.offerable(it, oneLetterWords) }
    }

    override fun rank(context: String, prefix: String, limit: Int): List<String> = model.predict(context + prefix, limit)

    companion object {
        /** words_en.txt plus words_en_modern.txt, as loadDictionary builds it with Suggest swear words off. */
        val shippedDictionary: WordDictionary get() = PredictionLogCorpus.dictionary(profanity = false)

        /**
         * A fresh install with Suggest swear words off (the default is on): the default seed phrases and
         * sentence starters, nothing learned, built as onCreate builds it.
         */
        fun cold(dictionary: WordDictionary = shippedDictionary) = CorpusDeviceRanker(
            name = "device-cold",
            description = "AdaptiveNgramModel as a fresh install builds it with Suggest swear words off (the default is on): " +
                "default seed phrases and sentence starters, nothing learned, words_en + words_en_modern",
        ) { AdaptiveNgramModel(dictionary = dictionary) }

        /** The same dictionary with no seed phrases and no sentence starters: what the bundled frequencies do alone. */
        fun frequencyOnly(dictionary: WordDictionary = shippedDictionary) = CorpusDeviceRanker(
            name = "frequency-only",
            description = "control: the same dictionary with no seed phrases and no sentence starters",
        ) { AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = dictionary, sentenceStarters = emptyList()) }

        /** The corpus prior the keyboard bundles, `src/main/assets/corpus_prior.bin`, read once. */
        val bundledPrior: CorpusPrior by lazy { File("src/main/assets/${CorpusPrior.ASSET}").inputStream().use { CorpusPrior.read(it) } }

        /** The column name for the prior at [weights]: its weight, plus the floor and fade when they aren't the defaults. */
        fun priorName(weights: AdaptiveNgramModel.PriorWeights): String {
            fun num(x: Float) = if (x == x.toInt().toFloat()) x.toInt().toString() else x.toString()
            val default = AdaptiveNgramModel.PriorWeights.DEFAULT
            return "prior-w${num(weights.weight)}" +
                (if (weights.floor != default.floor) "-floor${num(weights.floor)}" else "") +
                (if (weights.fade != default.fade) "-fade${num(weights.fade)}" else "")
        }

        /**
         * [cold] plus [prior] through AdaptiveNgramModel's prior parameter at [weights]: the model the
         * service would build with the prior passed in. Nothing is learned from a corpus line, so the
         * person's own count after every word is zero and the fade never acts.
         */
        fun withPrior(
            weights: AdaptiveNgramModel.PriorWeights = AdaptiveNgramModel.PriorWeights.DEFAULT,
            dictionary: WordDictionary = shippedDictionary,
            prior: () -> CorpusPrior = { bundledPrior },
        ) = CorpusDeviceRanker(
            name = priorName(weights),
            description = "device-cold plus the bundled corpus_prior.bin, weight ${weights.weight}, floor ${weights.floor}, " +
                "fade ${weights.fade} (inert here: nothing is learned)",
        ) { AdaptiveNgramModel(dictionary = dictionary, prior = prior(), priorWeights = weights) }
    }
}

/**
 * The CIFG next-word model through its Kotlin port, [CifgModel], asked with the sentence so far plus
 * the typed prefix, as the keyboard would ask it. The scorer asks every prefix of a target with the
 * same context, and within a sentence each context extends the last, so one [CifgModel.Session]
 * steps once per new token instead of re-reading the sentence. [load] runs once, on first use.
 */
class CorpusCifgRanker(
    override val name: String,
    override val description: String = "",
    load: () -> CifgModel,
) : CorpusRanker {
    private val model by lazy(load)
    private val session by lazy { model.newSession() }

    /** The vocabulary's offerable words: everything the model can put in the strip. */
    override val vocabulary: Set<String> by lazy {
        (0 until model.vocabSize).map(model::token).filterTo(HashSet()) { CifgModel.isOfferable(it) }
    }

    override fun rank(context: String, prefix: String, limit: Int): List<String> = session.predict(context + prefix, limit).map { it.word }

    companion object {
        /** smoke-10min-v2's int8 export, under `LOCALTYPE_MODEL_DATA` or the Galactus default. */
        fun smokeV2File(): File = File(
            System.getenv("LOCALTYPE_MODEL_DATA") ?: (System.getProperty("user.home") + "/.local/share/localtype-model"),
            "runs/smoke-10min-v2/export/cifg_int8.bin",
        )

        /** cifg-v3-3ep-r2's int8 export: the same shape retrained for three epochs on corpus-v3 and vocab-v3. */
        fun v3File(): File = File(
            System.getenv("LOCALTYPE_MODEL_DATA") ?: (System.getProperty("user.home") + "/.local/share/localtype-model"),
            "runs/cifg-v3-3ep-r2/export/cifg_int8.bin",
        )

        /** cifg-v3-3ep-r2 through [CifgModel], or null when its export isn't there. Dev only, like every candidate. */
        fun v3(file: File = v3File()): CorpusCifgRanker? = file.takeIf { it.isFile }?.let { f ->
            CorpusCifgRanker(
                name = "cifg-v3",
                description = "cifg-v3-3ep-r2 (2.0M-parameter CIFG, three epochs on corpus-v3 with vocab-v3, best human-macro checkpoint) through the Kotlin int8 port, ${f.path}",
            ) { f.inputStream().use { CifgModel.load(it) } }
        }

        /**
         * Any further runs named in `-Dlocaltype.cifgRuns` (comma-separated run names under `runs/`), each through its
         * own `export/cifg_int8.bin`; a name whose export isn't there yet is skipped. Dev only, like every candidate.
         */
        fun named(names: String? = System.getProperty("localtype.cifgRuns")): List<CorpusCifgRanker> =
            names.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).map { name ->
                val f = File(
                    System.getenv("LOCALTYPE_MODEL_DATA") ?: (System.getProperty("user.home") + "/.local/share/localtype-model"),
                    "runs/$name/export/cifg_int8.bin",
                )
                // A run named on the command line must be scored or fail; dropping it silently hid a missing export once.
                require(f.isFile) { "No ${f.path}: export it with python -m model.export --name $name --bin-only" }
                CorpusCifgRanker(name = name, description = "$name through the Kotlin int8 port, ${f.path}") {
                    f.inputStream().use { CifgModel.load(it) }
                }
            }

        /** smoke-10min-v2 through [CifgModel], or null when the model-data volume isn't mounted. */
        fun smokeV2(file: File = smokeV2File()): CorpusCifgRanker? = file.takeIf { it.isFile }?.let { f ->
            CorpusCifgRanker(
                name = "cifg-smoke-v2",
                description = "smoke-10min-v2 (2.0M-parameter CIFG, v2 corpus and 16k vocabulary) through the Kotlin int8 port, ${f.path}",
            ) { f.inputStream().use { CifgModel.load(it) } }
        }
    }
}

/**
 * The text side of the scorer: a tokenizer that mirrors `model/tokenize.py`, the offerable-word rule
 * and the sentence split, so that the targets and their contexts here are the ones
 * `cifg.keyboard_metrics` scores in sentence scope.
 */
object CorpusText {
    enum class Kind(val label: String) { WORD("words"), PUNCT("punctuation"), NUMBER("numbers"), EMOJI("emoji"), SYMBOL("symbols") }

    /** One token: a normalized word, a canonical punctuation mark, an emoji as written, `<num>` or `<sym>`, over line[start, end). */
    class Token(val kind: Kind, val text: String, val start: Int, end: Int) {
        /** Grows when a repeated mark collapses into this one, so a sentence cut lands after the whole run. */
        var end: Int = end
            internal set

        override fun toString() = text
    }

    /** The 31 marks that get their own token in `model/tokenize.py`; any other character is `<sym>`. */
    val PUNCT: Set<String> = setOf(
        ".", ",", "?", "!", "…", "'", "\"", "-", ":", ";", "(", ")", "/", "&", "$", "%", "*", "+", "=", "@", "#", "~", "_",
        "[", "]", "<", ">", "^", "\\", "|", "°",
    )

    /** Look-alikes folded onto [PUNCT], as `CANON` in `model/tokenize.py`. */
    private val CANON: Map<String, String> = mapOf(
        "’" to "'", "‘" to "'", "‚" to "'", "`" to "'", "´" to "'",
        "“" to "\"", "”" to "\"", "„" to "\"", "«" to "\"", "»" to "\"",
        "–" to "-", "—" to "-", "‐" to "-", "‑" to "-", "−" to "-", "﹣" to "-", "－" to "-",
        "。" to ".", "．" to ".", "，" to ",", "、" to ",", "：" to ":", "；" to ";",
        "？" to "?", "！" to "!", "（" to "(", "）" to ")", "¿" to "?", "¡" to "!",
        "‥" to "…",
    )

    /** The marks that end a sentence: the harness's Context Scope rule plus the ellipsis, as in cifg.SENTENCE_END. */
    val SENTENCE_END: Set<String> = setOf(".", "!", "?", "…")

    const val NUM = "<num>"
    const val SYM = "<sym>"

    private const val EMOJI_BASE = "(?:[\\x{1F1E6}-\\x{1F1FF}]{2}|[0-9#*]\\x{FE0F}?\\x{20E3}" +
        "|[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2300}-\\x{23FF}\\x{2B00}-\\x{2BFF}\\x{3030}\\x{303D}\\x{3297}\\x{3299}" +
        "\\x{00A9}\\x{00AE}\\x{2122}\\x{2194}-\\x{2199}\\x{21A9}\\x{21AA}\\x{24C2}\\x{25AA}-\\x{25FE}\\x{2934}\\x{2935}])"
    private const val EMOJI_MODS = "[\\x{FE0E}\\x{FE0F}\\x{1F3FB}-\\x{1F3FF}\\x{E0020}-\\x{E007F}]*"

    // Python's \s on str: ASCII whitespace, the information separators and the Unicode spaces.
    private const val SPACE = "[\\s\\x{1C}-\\x{1F}\\x{85}\\x{A0}\\x{1680}\\x{2000}-\\x{200A}\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}]"
    private val SPACE_ONLY = Regex("$SPACE*")

    /** True when [tokenize] gives [line] at least one token, which is train.sample_lines' rule for an eligible line. */
    fun hasToken(line: String): Boolean = !SPACE_ONLY.matches(line)

    // The device's word rule (AdaptiveNgramModel.WORD_REGEX) first, then emoji, numbers (\d is Unicode in Python), space, any one character.
    // Python matches [^\W\d_], which also takes Nl and No characters, then splits them back out; \p{L} gives the same tokens directly.
    private val MASTER = Regex(
        "([\\p{L}][\\p{L}'’-]*)" +
            "|($EMOJI_BASE$EMOJI_MODS(?:\\x{200D}$EMOJI_BASE$EMOJI_MODS)*)" +
            "|(\\p{Nd}+(?:[.,:/]\\p{Nd}+)*)" +
            "|($SPACE+)" +
            "|(.)",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** `tokenize` from `model/tokenize.py`, with each token's place in [line]. */
    fun tokenize(line: String): List<Token> {
        val out = ArrayList<Token>()
        for (m in MASTER.findAll(line)) {
            val start = m.range.first
            val end = m.range.last + 1
            when {
                m.groups[1] != null -> out += Token(Kind.WORD, AdaptiveNgramModel.normalize(m.value), start, end)
                m.groups[2] != null -> out += Token(Kind.EMOJI, m.value, start, end)
                m.groups[3] != null -> out += Token(Kind.NUMBER, NUM, start, end)
                m.groups[4] != null -> Unit
                else -> {
                    val c = CANON[m.value] ?: m.value
                    if (c in PUNCT) emitPunct(out, c, start, end) else out += Token(Kind.SYMBOL, SYM, start, end)
                }
            }
        }
        return out
    }

    /** `_emit`: a run of one mark is one token (whitespace between them included), and two or more dots are one …. */
    private fun emitPunct(out: MutableList<Token>, mark: String, start: Int, end: Int) {
        val prev = out.lastOrNull()?.takeIf { it.kind == Kind.PUNCT }
        when {
            mark == "." && prev?.text == "." -> out[out.size - 1] = Token(Kind.PUNCT, "…", prev.start, end)
            mark == "." && prev?.text == "…" -> prev.end = end
            mark != "." && prev?.text == mark -> prev.end = end
            else -> out += Token(Kind.PUNCT, mark, start, end)
        }
    }

    /** The rule in AdaptiveNgramModel.predict: no one-letter word except a and i ever reaches the strip. */
    fun offerable(word: String): Boolean = offerable(word, LanguageProfile.ENGLISH.oneLetterWords)

    /** The same rule for a language with other one-letter words ([LanguageProfile.oneLetterWords]: y, o, a, e, u in Spanish). */
    fun offerable(word: String, oneLetterWords: Set<String>): Boolean = word.length >= 2 || word in oneLetterWords

    /** A word to score: its normalized form, its length in code points as Python counts it, and the sentence before it. */
    class Target(val word: String, val context: String, val line: Int, oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords) {
        val length: Int = word.codePointCount(0, word.length)
        val offerable: Boolean = offerable(word, oneLetterWords)

        /** The first [p] code points of [word]. */
        fun prefix(p: Int): String = word.substring(0, word.offsetByCodePoints(0, p))
    }

    /**
     * One sentence of a line: the tokens between two sentence ends (`scope_items` in cifg.py, which
     * gives the end mark itself to neither side), and whether the mark that opened it is one the
     * phone also treats as a sentence end. isSentenceStart knows only . ! ? and newline, so after
     * a literal … the phone sees no sentence start where this scope (and cifg's) does.
     */
    class Sentence(val tokens: List<Token>, val targets: List<Target>, val phoneSeesStart: Boolean)

    /** The sentences of [line] that hold at least one token, with a target for every word in them. */
    fun sentences(line: String, lineIndex: Int = 0, oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords): List<Sentence> {
        val out = ArrayList<Sentence>()
        var from = 0
        var phoneSeesStart = true
        var current = ArrayList<Token>()
        fun close() {
            if (current.isEmpty()) return
            val targets = current.filter { it.kind == Kind.WORD }
                .map { Target(it.text, line.substring(from, it.start).trimStart(), lineIndex, oneLetterWords) }
            out += Sentence(current, targets, phoneSeesStart)
            current = ArrayList()
        }
        for (token in tokenize(line)) {
            if (token.kind == Kind.PUNCT && token.text in SENTENCE_END) {
                close()
                from = token.end
                phoneSeesStart = line[token.end - 1] in ".!?"
            } else {
                current += token
            }
        }
        close()
        return out
    }
}

/**
 * Rates [CorpusRanker]s on a corpus split the way the keyboard is used, with the arithmetic of
 * `cifg.keyboard_metrics` in sentence scope (model/README.md, "Keyboard-shaped metrics"):
 *
 * - every word of a sentence is a target, with the sentence so far as context;
 * - next word: hit@1, hit@3, hit@6 and MRR within six, at prefix 0;
 * - completion hit@3 at each prefix length from 1 to 16 that is shorter than the word, reported at
 *   1, 2, 3, 4 and 5+;
 * - potential savings: for each word the smallest prefix length at which it is in the top three
 *   (a word longer than 17 still missing at 16 is credited from 17 if the ranker knows it), saving
 *   the rest of its length, over a baseline of its length plus one;
 * - a one-letter target other than a and i is a miss everywhere and stays in every denominator;
 *   punctuation, number, emoji and symbol tokens are skipped and counted.
 *
 * Columns are the human-written sources (every NUS SMS and every Taskmaster line of the split) and a
 * seeded 5,000-line pooled sample. The pooled sample is drawn with Kotlin's generator, so it isn't
 * the same 5,000 lines as cifg.py's numpy sample; the human columns are the same lines.
 *
 * [sourceCounts] gives, per source of the split, the counts no ranker affects, in the line format
 * `python -m model.corpus_counts` prints, so parity with `model/tokenize.py` is a plain diff, and
 * [cifgMismatches] checks the NUS and Taskmaster ones against a cifg.py eval file.
 */
object CorpusEvaluation {
    const val MAX_PREFIX = 16
    const val POOLED_LINES = 5_000
    const val RESAMPLES = 2_000

    /** cifg.py's evaluation seed (`--seed` + 1) for the pooled sample; the bootstrap uses the next one. */
    const val SEED = 20_260_926L

    val SPLITS = listOf("dev", "test")

    /** Where the split comes from: `-Dlocaltype.corpus` or `LOCALTYPE_CORPUS`, `-Dlocaltype.corpusSplit`, `-Dlocaltype.corpusLines`. */
    class Settings(val dir: File, val split: String = "dev", val maxLines: Int? = null) {
        init {
            require(split in SPLITS) { "localtype.corpusSplit must be one of $SPLITS, not $split" }
            require(maxLines == null || maxLines > 0) { "localtype.corpusLines must be positive" }
        }

        companion object {
            /** Null when no corpus is configured, so the corpus test skips. */
            fun fromSystem(): Settings? {
                val dir = (System.getProperty("localtype.corpus") ?: System.getenv("LOCALTYPE_CORPUS"))
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val split = System.getProperty("localtype.corpusSplit")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: "dev"
                val lines = System.getProperty("localtype.corpusLines")?.trim()?.takeIf { it.isNotEmpty() }?.toInt()
                return Settings(File(dir), split, lines)
            }
        }
    }

    /** One column of the report: a named set of corpus lines. */
    class Column(val name: String, val label: String, val lines: List<String>)

    /**
     * The corpus prior settings rated on dev, preregistered in docs/next-steps-2026-09-26.md before
     * any of them was scored: six weights at the default floor and fade. The fade can't act here,
     * because nothing is learned from a corpus line.
     */
    val PRIOR_SETTINGS: List<AdaptiveNgramModel.PriorWeights> =
        listOf(2.5f, 5f, 10f, 20f, 40f, 80f).map { AdaptiveNgramModel.PriorWeights(weight = it) }

    /**
     * The setting [selectPrior] chose on dev, and the only one the test split scores. The test split
     * refuses to run while this is null. Weight 20 had the best dev mean on 2026-10-04 (NUS .3155,
     * Taskmaster .4554), recorded in docs/next-steps-2026-09-26.md before the test split was read.
     */
    val SELECTED_PRIOR: AdaptiveNgramModel.PriorWeights? = AdaptiveNgramModel.PriorWeights(weight = 20f)

    /**
     * The asset the test split was spent on: scored once on 2026-10-04 with this `corpus_prior.bin`
     * at [GATED_PRIOR] (model/README.md, "The corpus prior's gate"). A rebuilt asset or another
     * setting needs a new preregistered gate, not a rerun of test.
     */
    const val GATED_PRIOR_SHA256 = "5ea7038a8c99e8fdeb6813bcb57db019933261c8e3e8298b7aff62048c6f4825"
    val GATED_PRIOR = AdaptiveNgramModel.PriorWeights(weight = 20f)

    /**
     * Why the test split won't score the prior with SHA-256 [sha256] at [selected], or null when
     * that's the pair it was spent on, so a rerun reproduces the recorded verdict and nothing new.
     */
    fun testSplitRefusal(sha256: String?, selected: AdaptiveNgramModel.PriorWeights?): String? = when {
        selected == null -> "the test split scores only the prior setting chosen on dev: record it in SELECTED_PRIOR first"
        sha256 != GATED_PRIOR_SHA256 || selected != GATED_PRIOR ->
            "the test split was spent on 2026-10-04 on the prior with SHA-256 $GATED_PRIOR_SHA256 at weight ${GATED_PRIOR.weight}; " +
                "this is $sha256 at weight ${selected.weight}, floor ${selected.floor}, fade ${selected.fade}. " +
                "A rebuilt prior or another setting needs a new preregistered gate, not a rerun of test"
        else -> null
    }

    /**
     * The rankers the corpus test scores on [split]. Dev holds the comparison and the tuning: the
     * shipped ranker, its frequency-only control, the shipped ranker with the prior at each of
     * [PRIOR_SETTINGS], and the smoke-10min-v2 and cifg-v3-3ep-r2 CIFGs when their exports are on the model-data volume. Test holds
     * only the preregistered comparison: the shipped ranker, and the same with the prior at
     * [SELECTED_PRIOR]. It was spent on 2026-10-04, so it refuses any other asset or setting
     * ([testSplitRefusal]). Further candidates go on dev, each scored on the same targets and paired with the rest.
     */
    fun rankers(split: String = "dev"): List<CorpusRanker> = when (split) {
        "test" -> {
            testSplitRefusal(CorpusDeviceRanker.bundledPrior.sha256, SELECTED_PRIOR)?.let { error(it) }
            listOf(CorpusDeviceRanker.cold(), CorpusDeviceRanker.withPrior(checkNotNull(SELECTED_PRIOR)))
        }
        else -> listOf(CorpusDeviceRanker.cold(), CorpusDeviceRanker.frequencyOnly()) +
            PRIOR_SETTINGS.map { CorpusDeviceRanker.withPrior(it) } +
            listOfNotNull(CorpusCifgRanker.smokeV2(), CorpusCifgRanker.v3()) + CorpusCifgRanker.named()
    }

    /** `<split>.txt` and its line-parallel `<split>.sources.txt`. */
    private fun read(settings: Settings): Pair<List<String>, List<String>> {
        val text = File(settings.dir, "${settings.split}.txt")
        val sources = File(settings.dir, "${settings.split}.sources.txt")
        require(text.isFile) { "no ${text.path}" }
        require(sources.isFile) { "no ${sources.path}" }
        return text.readLines() to sources.readLines()
    }

    /** Reads the split and builds the nus, taskmaster and pooled columns. */
    fun columns(settings: Settings): List<Column> = read(settings).let { (lines, sources) -> columns(lines, sources, settings.maxLines) }

    /**
     * Every NUS SMS line, every Taskmaster line and a seeded [pooledLines]-line sample of the whole split.
     * With [maxLines], each human column keeps its first [maxLines] lines and the pooled column draws
     * [maxLines] instead: the partial shuffle makes the same first picks, so that is a random subset of
     * the full sample rather than its first lines, which in a split grouped by source would be the human ones.
     */
    fun columns(lines: List<String>, sources: List<String>, maxLines: Int? = null, pooledLines: Int = POOLED_LINES, seed: Long = SEED): List<Column> {
        require(lines.size == sources.size) { "${lines.size} corpus lines but ${sources.size} source labels" }
        // As train.sample_lines: only lines with a token are eligible, and each source column takes all of its own.
        val eligible = lines.indices.filter { CorpusText.hasToken(lines[it]) }
        fun of(ids: List<Int>) = ids.let { if (maxLines != null) it.take(maxLines) else it }.map { lines[it] }
        return listOf(
            Column("nus", "NUS SMS", of(eligible.filter { sources[it] == "nus" })),
            Column("taskmaster", "Taskmaster-1", of(eligible.filter { sources[it] == "taskmaster" })),
            Column("pooled", "pooled sample", sample(eligible, maxLines?.let { minOf(it, pooledLines) } ?: pooledLines, seed).map { lines[it] }),
        )
    }

    /** [n] of [ids] chosen by a seeded partial shuffle, in file order; all of them when there are no more than [n]. */
    fun sample(ids: List<Int>, n: Int, seed: Long): List<Int> {
        if (n >= ids.size) return ids
        val pool = ids.toIntArray()
        val random = Random(seed)
        for (i in 0 until n) {
            val j = i + random.nextInt(pool.size - i)
            pool[i] = pool[j].also { pool[j] = pool[i] }
        }
        return pool.copyOf(n).sorted()
    }

    /** What a column holds regardless of ranker: its sentences, targets and the per-line baseline. */
    class ColumnText(val column: Column, val oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords) {
        val sentences: List<CorpusText.Sentence>
        val targets: List<CorpusText.Target>

        /** Non-word tokens inside the sentences, by kind: skipped as targets, as cifg.py skips them. */
        val skipped: Map<CorpusText.Kind, Int>

        /** Characters typed without any help, per line: a word's length plus one for the space. */
        val baseline: IntArray = IntArray(column.lines.size)

        /** Word targets per line, one-letter ones included: the denominator of the next-word hit rates. */
        val targetsByLine: IntArray = IntArray(column.lines.size)

        init {
            val all = ArrayList<CorpusText.Sentence>()
            column.lines.forEachIndexed { i, line -> all += CorpusText.sentences(line, i, oneLetterWords) }
            sentences = all
            targets = all.flatMap { it.targets }
            targets.forEach {
                baseline[it.line] += it.length + 1
                targetsByLine[it.line]++
            }
            val tokens = all.flatMap { it.tokens }
            skipped = CorpusText.Kind.entries.filter { it != CorpusText.Kind.WORD }.associateWith { kind -> tokens.count { it.kind == kind } }
        }

        val unofferable: Int get() = targets.count { !it.offerable }

        /** Ladder positions per prefix length: every offerable target with p < length, p up to [MAX_PREFIX]. */
        val ladder: IntArray by lazy {
            IntArray(MAX_PREFIX + 1).also { n -> targets.filter { it.offerable }.forEach { t -> for (p in 1 until minOf(t.length, MAX_PREFIX + 1)) n[p]++ } }
        }

        /** Sentences opened by a mark the phone doesn't treat as a sentence end (a literal …, ‥, 。, ¿ or ¡), holding a target. */
        val unseenStarts: Int get() = sentences.count { !it.phoneSeesStart && it.targets.isNotEmpty() }
    }

    /**
     * What one source of a split holds regardless of ranker, summed over [ColumnText]s. [line] prints it
     * exactly as `python -m model.corpus_counts` does, field for field, so the two outputs diff clean
     * when this tokenizer and `model/tokenize.py` agree. A one-letter word counts under one_letter
     * whatever the vocabulary; cifg.py's eval JSON counts one outside its vocabulary as OOV instead.
     */
    class SourceCounts {
        var lines = 0
        var sentences = 0
        var targets = 0
        var oneLetter = 0
        var baseline = 0L
        val skipped = IntArray(CorpusText.Kind.entries.size)
        val ladder = IntArray(MAX_PREFIX + 1)

        val skippedTotal: Int get() = skipped.sum()
        val ladder5Plus: Int get() = (5..MAX_PREFIX).sumOf { ladder[it] }

        fun add(text: ColumnText) {
            lines += text.column.lines.size
            sentences += text.sentences.size
            targets += text.targets.size
            oneLetter += text.unofferable
            baseline += text.baseline.sum()
            for ((kind, n) in text.skipped) skipped[kind.ordinal] += n
            for (p in 1..MAX_PREFIX) ladder[p] += text.ladder[p]
        }

        fun line(source: String): String {
            fun kind(k: CorpusText.Kind) = skipped[k.ordinal]
            return "$source lines=$lines sentences=$sentences targets=$targets one_letter=$oneLetter skipped=$skippedTotal " +
                "punct=${kind(CorpusText.Kind.PUNCT)} num=${kind(CorpusText.Kind.NUMBER)} emoji=${kind(CorpusText.Kind.EMOJI)} sym=${kind(CorpusText.Kind.SYMBOL)} " +
                "p1=${ladder[1]} p2=${ladder[2]} p3=${ladder[3]} p4=${ladder[4]} p5+=$ladder5Plus baseline=$baseline"
        }
    }

    /** [SourceCounts] for every line of the split, ignoring [Settings.maxLines]. */
    fun sourceCounts(settings: Settings): Map<String, SourceCounts> = read(settings).let { (lines, sources) -> sourceCounts(lines, sources) }

    /**
     * Per source, in the order the sources first appear, over every line with a token. Lines go
     * through [ColumnText] [chunk] at a time, so a 77,000-line source never holds all its targets at once.
     */
    fun sourceCounts(lines: List<String>, sources: List<String>, chunk: Int = 5_000): Map<String, SourceCounts> {
        require(lines.size == sources.size) { "${lines.size} corpus lines but ${sources.size} source labels" }
        val ids = LinkedHashMap<String, MutableList<Int>>()
        for (i in lines.indices) if (CorpusText.hasToken(lines[i])) ids.getOrPut(sources[i].trim()) { ArrayList() } += i
        return ids.mapValuesTo(LinkedHashMap()) { (source, of) ->
            SourceCounts().also { counts -> of.chunked(chunk).forEach { part -> counts.add(ColumnText(Column(source, source, part.map { lines[it] }))) } }
        }
    }

    /** Writes [SourceCounts.line] for every source to `build/reports/corpus-eval/<split>.counts.txt`. */
    fun writeCounts(counts: Map<String, SourceCounts>, split: String, dir: File = File("build/reports/corpus-eval")): File {
        dir.mkdirs()
        return File(dir, "$split.counts.txt").also { file -> file.writeText(counts.entries.joinToString("") { (source, c) -> c.line(source) + "\n" }) }
    }

    /**
     * The NUS and Taskmaster counts that both scorers share, checked against the sentence scope of a
     * cifg.py eval file (`<run>/eval.<split>.best.json`): lines, sentences (cifg's items), word targets,
     * skipped tokens and ladder positions must be equal, and the one-letter count here may exceed
     * cifg's unofferable count only by one-letter words it calls OOV. Null when the file is for another
     * split; otherwise every mismatch, empty when they agree.
     */
    fun cifgMismatches(counts: Map<String, SourceCounts>, eval: File, split: String): List<String>? {
        require(eval.isFile) { "no ${eval.path}" }
        val json = MiniJson.parse(eval.readText()) as? Map<*, *> ?: error("${eval.path} is not a JSON object")
        if (json["split"] != split) return null
        val out = ArrayList<String>()
        for (source in listOf("nus", "taskmaster")) {
            val c = counts[source]
            val m = ((json["keyboard"] as? Map<*, *>)?.get("sentence") as? Map<*, *>)?.get(source) as? Map<*, *>
            if (c == null || m == null) {
                out += "$source: ${if (c == null) "no lines in the split" else "no keyboard.sentence.$source in ${eval.name}"}"
                continue
            }
            fun num(vararg path: String): Int = path.fold(m as Any?) { node, key -> (node as? Map<*, *>)?.get(key) }
                .let { (it as? Double)?.toInt() ?: error("${eval.name}: no number at keyboard.sentence.$source.${path.joinToString(".")}") }
            fun check(what: String, here: Int, there: Int) {
                if (here != there) out += "$source $what: $here here, $there in ${eval.name}"
            }
            check("lines", c.lines, num("lines"))
            check("sentences (items)", c.sentences, num("items"))
            check("word targets", c.targets, num("targets"))
            check("skipped tokens", c.skippedTotal, num("non_word_targets_skipped"))
            for (p in 1..4) check("ladder p=$p", c.ladder[p], num("completion", "$p", "n"))
            check("ladder p=5+", c.ladder5Plus, num("completion", "5+", "n"))
            val unofferable = num("unofferable_one_letter_targets")
            if (c.oneLetter < unofferable || c.oneLetter - unofferable > num("oov_targets")) {
                out += "$source one-letter targets: ${c.oneLetter} here, $unofferable unofferable plus at most ${num("oov_targets")} OOV in ${eval.name}"
            }
        }
        return out
    }

    /** Just enough JSON to read a cifg.py eval file, including Python's NaN and Infinity. Numbers come back as Double. */
    private class MiniJson private constructor(private val s: String) {
        private var i = 0

        private fun fail(what: String): Nothing = error("JSON: $what at offset $i")

        private fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun expect(c: Char) {
            skipSpace()
            if (i >= s.length || s[i] != c) fail("expected '$c'")
            i++
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return value
        }

        private fun value(): Any? {
            skipSpace()
            if (i >= s.length) fail("unexpected end")
            return when (s[i]) {
                '{' -> members('}').associate { (name, v) -> name!! to v }
                '[' -> members(']').map { it.second }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                'N' -> literal("NaN", Double.NaN)
                'I' -> literal("Infinity", Double.POSITIVE_INFINITY)
                else -> if (s.startsWith("-Infinity", i)) literal("-Infinity", Double.NEGATIVE_INFINITY) else number()
            }
        }

        /** An object's members (named) or an array's elements (unnamed), up to [close]. */
        private fun members(close: Char): List<Pair<String?, Any?>> {
            i++
            val out = ArrayList<Pair<String?, Any?>>()
            skipSpace()
            if (i < s.length && s[i] == close) {
                i++
                return out
            }
            while (true) {
                val name = if (close == '}') {
                    skipSpace()
                    string().also { expect(':') }
                } else null
                out += name to value()
                skipSpace()
                if (i < s.length && s[i] == ',') i++ else break
            }
            expect(close)
            return out
        }

        private fun string(): String {
            if (i >= s.length || s[i] != '"') fail("expected a string")
            i++
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                when (val c = s[i++]) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = s[i++]) {
                        'u' -> out.append(s.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail("expected a value")
        }

        companion object {
            fun parse(text: String): Any? = MiniJson(text).run {
                value().also {
                    skipSpace()
                    if (i != s.length) fail("trailing text")
                }
            }
        }
    }

    /** One ranker on one column. */
    class Score(val ranker: CorpusRanker, val text: ColumnText) {
        var hit1 = 0
        var hit3 = 0
        var hit6 = 0
        var reciprocal = 0.0
        val completionHits = IntArray(MAX_PREFIX + 1)
        var saved = 0
        val savedByLine = IntArray(text.column.lines.size)
        val hit3ByLine = IntArray(text.column.lines.size)
        var instanceCompletion = 0.0
        var instances = 0
        var dropped = 0

        /** The in-vocabulary-only tallies, kept when the ranker has a [CorpusRanker.vocabulary]. */
        var inVocab = 0
        var inVocabHit1 = 0
        var inVocabHit3 = 0
        var inVocabSaved = 0
        var inVocabBaseline = 0

        val targets: Int get() = text.targets.size
        val baseline: Int get() = text.baseline.sum()

        fun rate(hits: Number, n: Number): Double = if (n.toDouble() == 0.0) Double.NaN else hits.toDouble() / n.toDouble()
        val nextHit1 get() = rate(hit1, targets)
        val nextHit3 get() = rate(hit3, targets)
        val nextHit6 get() = rate(hit6, targets)
        val mrr get() = rate(reciprocal, targets)
        fun completion(p: Int) = rate(completionHits[p], text.ladder[p])
        val completion5Plus get() = rate((5..MAX_PREFIX).sumOf { completionHits[it] }, (5..MAX_PREFIX).sumOf { text.ladder[it] })
        val completionPooled get() = rate(completionHits.sum(), text.ladder.sum())
        val perInstanceCompletion get() = rate(instanceCompletion, instances)
        val savings get() = rate(saved, baseline)
    }

    /** Scores [ranker] on every target of [text]. */
    fun score(ranker: CorpusRanker, text: ColumnText): Score {
        val s = Score(ranker, text)
        val vocabulary = ranker.vocabulary
        // A dropped candidate leaves its slot empty, so every other answer keeps the rank the ranker gave it.
        fun ask(context: String, prefix: String, limit: Int): List<String?> {
            val seen = HashSet<String>()
            return ranker.rank(context, prefix, limit).take(limit).map { word ->
                if (CorpusText.offerable(word, text.oneLetterWords) && word.startsWith(prefix) && seen.add(word)) word else null.also { s.dropped++ }
            }
        }
        for (t in text.targets) {
            // One-letter words the strip never shows: a miss at every length, already in the baseline.
            if (!t.offerable) continue
            val known = vocabulary?.contains(t.word)
            var best = -1
            val rank = ask(t.context, "", 6).indexOf(t.word)
            if (rank >= 0) {
                s.hit6++
                s.reciprocal += 1.0 / (rank + 1)
                if (rank < 3) {
                    s.hit3++
                    s.hit3ByLine[t.line]++
                    best = 0
                }
                if (rank == 0) s.hit1++
            }
            var ladderHits = 0
            var ladderN = 0
            for (p in 1 until minOf(t.length, MAX_PREFIX + 1)) {
                ladderN++
                if (t.word in ask(t.context, t.prefix(p), 3)) {
                    s.completionHits[p]++
                    ladderHits++
                    if (best < 0) best = p
                }
            }
            // cifg credits a known word longer than the searched ladder from MAX_PREFIX + 1 on.
            if (best < 0 && t.length > MAX_PREFIX + 1 && known != false) best = MAX_PREFIX + 1
            if (ladderN > 0) {
                s.instanceCompletion += ladderHits.toDouble() / ladderN
                s.instances++
            }
            val saved = if (best < 0) 0 else maxOf(0, t.length - best)
            s.saved += saved
            s.savedByLine[t.line] += saved
            if (known == true) {
                s.inVocab++
                if (rank == 0) s.inVocabHit1++
                if (rank in 0..2) s.inVocabHit3++
                s.inVocabSaved += saved
                s.inVocabBaseline += t.length + 1
            }
        }
        return s
    }

    /** A paired bootstrap of B's rate minus A's (potential savings, or next-word hit@3), in rate units (0.01 is one point). */
    class Interval(val a: String, val b: String, val difference: Double, val low: Double, val high: Double, val resamples: Int, val lines: Int) {
        val excludesZero: Boolean get() = low > 0.0 || high < 0.0
    }

    /**
     * Resamples lines with replacement [resamples] times and recomputes both rates on each resample,
     * so the two rankers are always compared on the same lines. A rate is the sum of [savedA] (or
     * [savedB]) over the sum of [baseline]: characters saved over baseline characters for potential
     * savings, or hits over word targets for next-word hit@3. The interval is the 2.5th and 97.5th
     * percentiles by nearest rank. A line, not a word, is the unit: the words of a message share its
     * sender and its register.
     */
    fun pairedBootstrap(
        savedA: IntArray,
        savedB: IntArray,
        baseline: IntArray,
        resamples: Int = RESAMPLES,
        seed: Long = SEED + 1,
        names: Pair<String, String> = "A" to "B",
    ): Interval {
        require(savedA.size == baseline.size && savedB.size == baseline.size)
        val n = baseline.size
        val point = (savedB.sum() - savedA.sum()).toDouble() / baseline.sum().coerceAtLeast(1)
        if (n == 0) return Interval(names.first, names.second, Double.NaN, Double.NaN, Double.NaN, resamples, 0)
        val random = Random(seed)
        val diffs = DoubleArray(resamples) {
            var a = 0L
            var b = 0L
            var base = 0L
            repeat(n) {
                val i = random.nextInt(n)
                a += savedA[i]
                b += savedB[i]
                base += baseline[i]
            }
            if (base == 0L) 0.0 else (b - a).toDouble() / base
        }
        diffs.sort()
        val low = diffs[(0.025 * resamples).toInt().coerceIn(0, resamples - 1)]
        val high = diffs[(kotlin.math.ceil(0.975 * resamples).toInt() - 1).coerceIn(0, resamples - 1)]
        return Interval(names.first, names.second, point, low, high, resamples, n)
    }

    /** Every ranker on one column, and every pair of them bootstrapped on potential savings and on next-word hit@3. */
    class ColumnResult(val text: ColumnText, val scores: List<Score>, val intervals: List<Interval>, val hit3Intervals: List<Interval> = emptyList()) {
        fun score(ranker: String): Score? = scores.firstOrNull { it.ranker.name == ranker }

        /** The savings and hit@3 intervals of [b] minus [a], when both rankers were scored. */
        fun interval(a: String, b: String): Pair<Interval, Interval?>? {
            val i = intervals.indexOfFirst { it.a == a && it.b == b }.takeIf { it >= 0 } ?: return null
            return intervals[i] to hit3Intervals.getOrNull(i)
        }
    }

    class Result(val settings: Settings?, val results: List<ColumnResult>, val seconds: Map<String, Double>) {
        fun report(): String = CorpusEvaluation.report(this)

        fun column(name: String): ColumnResult? = results.firstOrNull { it.text.column.name == name }

        /** Writes the report to `build/reports/corpus-eval/<split>.txt`, relative to the test's working directory (app/). */
        fun writeReport(dir: File = File("build/reports/corpus-eval")): File {
            dir.mkdirs()
            return File(dir, "${settings?.split ?: "corpus"}.txt").also { it.writeText(report()) }
        }
    }

    fun evaluate(settings: Settings, rankers: List<CorpusRanker> = rankers(settings.split)): Result = evaluate(columns(settings), rankers, settings)

    fun evaluate(
        columns: List<Column>, rankers: List<CorpusRanker>, settings: Settings? = null,
        oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords,
    ): Result {
        require(rankers.map { it.name }.toSet().size == rankers.size) { "ranker names must be unique" }
        val seconds = LinkedHashMap<String, Double>()
        val results = columns.map { column ->
            val text = ColumnText(column, oneLetterWords)
            val scores = rankers.map { ranker ->
                val started = System.nanoTime()
                score(ranker, text).also { seconds.merge(ranker.name, (System.nanoTime() - started) / 1e9, Double::plus) }
            }
            val pairs = scores.indices.flatMap { i -> (i + 1 until scores.size).map { j -> scores[i] to scores[j] } }
            val intervals = pairs.map { (a, b) -> pairedBootstrap(a.savedByLine, b.savedByLine, text.baseline, names = a.ranker.name to b.ranker.name) }
            val hit3Intervals = pairs.map { (a, b) -> pairedBootstrap(a.hit3ByLine, b.hit3ByLine, text.targetsByLine, names = a.ranker.name to b.ranker.name) }
            ColumnResult(text, scores, intervals, hit3Intervals)
        }
        return Result(settings, results, seconds)
    }

    /** One prior setting's dev figures, for the preregistered choice. */
    class PriorChoice(val ranker: String, val weights: AdaptiveNgramModel.PriorWeights, val nus: Double, val taskmaster: Double) {
        val mean: Double get() = (nus + taskmaster) / 2
    }

    /**
     * The rule preregistered in docs/next-steps-2026-09-26.md for choosing the prior's setting on dev:
     * of the [PRIOR_SETTINGS] rankers in [result], the one with the highest mean of NUS and Taskmaster
     * potential savings, a tie within 0.0001 going to the lower weight. Null when [result] holds none
     * of them or lacks either column.
     */
    fun selectPrior(result: Result, settings: List<AdaptiveNgramModel.PriorWeights> = PRIOR_SETTINGS): PriorChoice? {
        val nus = result.column("nus") ?: return null
        val taskmaster = result.column("taskmaster") ?: return null
        val choices = settings.mapNotNull { weights ->
            val name = CorpusDeviceRanker.priorName(weights)
            val a = nus.score(name) ?: return@mapNotNull null
            val b = taskmaster.score(name) ?: return@mapNotNull null
            PriorChoice(name, weights, a.savings, b.savings)
        }
        if (choices.isEmpty()) return null
        val best = choices.maxOf { it.mean }
        return choices.filter { it.mean >= best - 1e-4 }.minBy { it.weights.weight }
    }

    /** The preregistered gate on one column: [savings] is the candidate minus the baseline. */
    class GateColumn(val column: String, val savings: Interval, val hit3Baseline: Score, val hit3Candidate: Score) {
        val savingsPass: Boolean get() = savings.low > 0.0
        val hit3Pass: Boolean get() = hit3Candidate.hit3 >= hit3Baseline.hit3
        val passed: Boolean get() = savingsPass && hit3Pass
    }

    class Gate(val baseline: String, val candidate: String, val columns: List<GateColumn>) {
        val passed: Boolean get() = columns.size == GATE_COLUMNS.size && columns.all { it.passed }
    }

    val GATE_COLUMNS = listOf("nus", "taskmaster")

    /**
     * The gate preregistered in docs/next-steps-2026-09-26.md, binding on the test split only: on NUS
     * and on Taskmaster, [candidate]'s potential savings beat [baseline]'s with the paired 95% interval
     * above zero, and its next-word hit@3 isn't lower (the targets are the same, so the hit counts
     * compare directly). Null when [result] lacks either ranker.
     */
    fun priorGate(result: Result, candidate: String, baseline: String = "device-cold"): Gate? {
        val columns = GATE_COLUMNS.map { name ->
            val column = result.column(name) ?: return null
            val savings = column.interval(baseline, candidate)?.first ?: return null
            GateColumn(name, savings, column.score(baseline) ?: return null, column.score(candidate) ?: return null)
        }
        return Gate(baseline, candidate, columns)
    }

    private fun pct(x: Double) = if (x.isNaN()) "-" else String.format(Locale.ROOT, "%.2f%%", 100 * x)
    private fun count(n: Int) = String.format(Locale.ROOT, "%,d", n)
    private fun points(x: Double) = String.format(Locale.ROOT, "%+.2f", 100 * x)

    private val ROWS: List<Pair<String, (Score) -> String>> = listOf(
        "next hit@1" to { s -> pct(s.nextHit1) },
        "next hit@3" to { s -> pct(s.nextHit3) },
        "next hit@6" to { s -> pct(s.nextHit6) },
        "next MRR@6" to { s -> if (s.mrr.isNaN()) "-" else String.format(Locale.ROOT, "%.4f", s.mrr) },
        "completion hit@3 p=1" to { s -> pct(s.completion(1)) },
        "completion hit@3 p=2" to { s -> pct(s.completion(2)) },
        "completion hit@3 p=3" to { s -> pct(s.completion(3)) },
        "completion hit@3 p=4" to { s -> pct(s.completion(4)) },
        "completion hit@3 p=5+" to { s -> pct(s.completion5Plus) },
        "pooled completion hit@3" to { s -> pct(s.completionPooled) },
        "per-instance completion hit@3" to { s -> pct(s.perInstanceCompletion) },
        "potential savings rate" to { s -> pct(s.savings) },
        "targets in its vocabulary" to { s -> if (s.ranker.vocabulary == null) "-" else pct(s.rate(s.inVocab, s.targets)) },
        "in-vocab only: next hit@3" to { s -> if (s.ranker.vocabulary == null) "-" else pct(s.rate(s.inVocabHit3, s.inVocab)) },
        "in-vocab only: potential savings" to { s -> if (s.ranker.vocabulary == null) "-" else pct(s.rate(s.inVocabSaved, s.inVocabBaseline)) },
        "candidates dropped" to { s -> count(s.dropped) },
    )

    fun report(result: Result): String = buildString {
        val settings = result.settings
        appendLine("LocalType corpus evaluation" + (settings?.let { ", ${it.split} split of ${it.dir.path}" } ?: ""))
        appendLine("scope: sentence (context = the current sentence so far, split at . ! ? …), as cifg.keyboard_metrics scope=sentence")
        val pooled = result.results.firstOrNull { it.text.column.name == "pooled" }?.text?.column?.lines?.size ?: 0
        val cap = settings?.maxLines
        appendLine(
            if (cap == null) {
                "columns: every NUS SMS and Taskmaster-1 line of the split; pooled = ${count(pooled)} lines sampled with seed $SEED (not cifg.py's numpy sample)"
            } else {
                "columns: the first ${count(cap)} NUS SMS and Taskmaster-1 lines of the split; pooled = a seeded ${count(pooled)}-line subsample of the " +
                    "${count(POOLED_LINES)}-line sample (seed $SEED, not cifg.py's numpy sample)"
            },
        )
        appendLine("candidates: limit 6 at prefix 0, limit 3 at prefix 1..$MAX_PREFIX; one-letter words other than a and i are never offered")
        val rankers = result.results.firstOrNull()?.scores?.map { it.ranker }.orEmpty()
        for (r in rankers) appendLine("ranker ${r.name}: ${r.description}")
        appendLine("caveat: the corpus keeps no NUS sender id, so one sender's messages can sit on both sides of a split; NUS senders and Taskmaster")
        appendLine("  dialogues are also resampled line by line, so intervals are narrower than a sender- or dialogue-level resample")
        for (column in result.results) {
            val text = column.text
            appendLine()
            val skipped = text.skipped.entries.joinToString { "${it.key.label} ${count(it.value)}" }
            appendLine("${text.column.label} (${text.column.name}): ${count(text.column.lines.size)} lines, ${count(text.sentences.size)} sentences, " +
                "${count(text.targets.size)} word targets (${count(text.unofferable)} one-letter words never offered), " +
                "${count(text.skipped.values.sum())} non-word tokens skipped ($skipped)")
            appendLine("  ladder positions p=1..4: " + (1..4).joinToString(" / ") { count(text.ladder[it]) } +
                ", p=5+: ${count((5..MAX_PREFIX).sumOf { text.ladder[it] })}; baseline ${count(text.baseline.sum())} characters" +
                "; sentences opened by a mark the phone doesn't end a sentence at (… ‥ 。 ¿ ¡): ${count(text.unseenStarts)}")
            val names = column.scores.map { it.ranker.name }
            val rows = ROWS.map { (label, fn) -> listOf(label) + column.scores.map(fn) }
            val widths = (0..names.size).map { i -> (rows.map { it[i] } + (if (i == 0) "" else names[i - 1])).maxOf { it.length } }
            appendLine("  " + (listOf("") + names).mapIndexed { i, c -> if (i == 0) c.padEnd(widths[0]) else c.padStart(widths[i]) }.joinToString("  "))
            for (row in rows) appendLine("  " + row.mapIndexed { i, c -> if (i == 0) c.padEnd(widths[0]) else c.padStart(widths[i]) }.joinToString("  "))
            if (column.intervals.isNotEmpty()) {
                val first = column.intervals.first()
                appendLine("  paired bootstrap, ${count(first.resamples)} resamples of the ${count(first.lines)} lines (seed ${SEED + 1}), " +
                    "95% intervals in points, of potential savings and of next-word hit@3:")
                fun show(i: Interval) = "${points(i.difference)} [${points(i.low)}, ${points(i.high)}], ${if (i.excludesZero) "excludes zero" else "includes zero"}"
                column.intervals.forEachIndexed { k, i ->
                    val hit3 = column.hit3Intervals.getOrNull(k)?.let { "; next hit@3 ${show(it)}" }.orEmpty()
                    appendLine("    ${i.b} minus ${i.a}: savings ${show(i)}$hit3")
                }
            }
        }
        selectPrior(result)?.takeIf { settings?.split != "test" }?.let { choice ->
            appendLine()
            appendLine("prior setting by the preregistered dev rule (highest mean of NUS and Taskmaster potential savings, ties to the lower weight):")
            for (weights in PRIOR_SETTINGS) {
                val name = CorpusDeviceRanker.priorName(weights)
                val nus = result.column("nus")?.score(name) ?: continue
                val taskmaster = result.column("taskmaster")?.score(name) ?: continue
                val mean = (nus.savings + taskmaster.savings) / 2
                appendLine("  $name: NUS ${pct(nus.savings)}, Taskmaster ${pct(taskmaster.savings)}, mean ${pct(mean)}" + if (name == choice.ranker) "  <- chosen" else "")
            }
        }
        SELECTED_PRIOR?.let { CorpusDeviceRanker.priorName(it) }?.let { priorGate(result, it) }?.let { gate ->
            appendLine()
            appendLine("preregistered prior gate, ${gate.candidate} against ${gate.baseline} (binding on the test split only; this is the ${settings?.split ?: "hand-made"} split):")
            for (c in gate.columns) {
                appendLine("  ${c.column}: savings ${points(c.savings.difference)} [${points(c.savings.low)}, ${points(c.savings.high)}] " +
                    "${if (c.savingsPass) "pass" else "FAIL"}; next hit@3 ${pct(c.hit3Candidate.nextHit3)} against ${pct(c.hit3Baseline.nextHit3)} " +
                    if (c.hit3Pass) "pass" else "FAIL")
            }
            appendLine("  verdict: ${if (gate.passed) "PASS" else "FAIL"}")
        }
    }
}
