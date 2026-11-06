package dev.localtype.keyboard

import java.io.File
import java.io.StringReader
import java.util.Locale

/** Where the replay harness finds a log, which field chains it scores, and the word lists the log was written against. */
object PredictionLogCorpus {
    /** The synthetic checked-in log written by [PredictionLogFixture.main]: the harness's input when no real log is given. */
    val fixture = File("src/test/fixtures/prediction_log_sample.tsv")

    /** A real exported log from `-Dlocaltype.log=` or `LOCALTYPE_LOG`; null means only the fixture runs. */
    fun path(): String? = (System.getProperty("localtype.log") ?: System.getenv("LOCALTYPE_LOG"))?.trim()?.takeIf { it.isNotEmpty() }

    /** Which field chains are scored: all of them, or one parity so a model tuned on the other can be scored here. */
    enum class Parity { ALL, ODD, EVEN }

    fun evalFields(): Parity = when (System.getProperty("localtype.eval")?.trim()?.lowercase(Locale.ROOT)) {
        "odd" -> Parity.ODD
        "even" -> Parity.EVEN
        else -> Parity.ALL
    }

    fun load(lines: List<String>): ParsedLog = ParsedLog.parse(lines)

    fun load(file: File): ParsedLog = load(file.readLines())

    private val withProfanity: WordDictionary by lazy {
        val lines = listOf("words_en.txt", "words_en_modern.txt", "words_en_profanity.txt")
            .flatMap { File("src/main/assets/$it").readLines() }
        WordDictionary.parse(lines.asSequence())
    }

    /** The word list the keyboard predicts from, with or without the swear list; without it, the corrector tests' own instance. */
    fun dictionary(profanity: Boolean): WordDictionary = if (profanity) withProfanity else TypoCorpus.dictionary

    /** The corpus prior this checkout bundles, `src/main/assets/corpus_prior.bin`, read once. */
    val bundledPrior: CorpusPrior by lazy { File("src/main/assets/${CorpusPrior.ASSET}").inputStream().use { CorpusPrior.read(it) } }

    /** Loading verifies the bundled neural bytes before accepting the digest; it is never used for replay inference. */
    private val bundledNeural: CifgModel by lazy { File("src/main/assets/${NeuralAsset.FILE_NAME}").inputStream().use(NeuralAsset::load) }

    /**
     * How the keyboard that wrote a file built its ranker, from the file's header: no prior when
     * `#prior` is absent (a build before the prior) or `none`, the bundled asset when `#prior` is its
     * SHA-256. Any other digest, or weights that don't parse, is refused, because the replay can only
     * agree with the device when it ranks with the same file. [seedPhrases] and [sentenceStarters]
     * are the device build's constants. The header carries only their digest (`#seeds`), and with a
     * prior a mismatch is refused too, because the prior's fade leaves seeded counts out.
     */
    fun build(
        header: Map<String, String>,
        seedPhrases: List<String> = AdaptiveNgramModel.DEFAULT_SEED_PHRASES,
        sentenceStarters: List<String> = AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS,
    ): ReplayBuild {
        val weights = requireNotNull(PredictionLogCodec.priorWeights(header)) { "the log's #priorWeights=${header["priorWeights"]} doesn't parse" }
        val digest = PredictionLogCodec.priorDigest(header)
        val prior = when (digest) {
            null -> null
            bundledPrior.sha256 -> bundledPrior
            else -> throw IllegalArgumentException(
                "the log was ranked with corpus prior $digest, but this checkout bundles ${bundledPrior.sha256}; " +
                    "replay it from the commit whose app/src/main/assets/${CorpusPrior.ASSET} has that SHA-256",
            )
        }
        if (prior != null) {
            val seeds = PredictionLogCodec.seedsDigest(seedPhrases, sentenceStarters)
            require(header["seeds"] == seeds) {
                "the log was ranked with seed phrases and starters ${header["seeds"] ?: "it doesn't name (no #seeds)"}, but this replay " +
                    "seeds with $seeds; replay it from the commit whose AdaptiveNgramModel seed phrases and starters have that digest"
            }
        }
        val neural = PredictionLogCodec.neuralDigest(header)
        require(neural == null || neural == NeuralAsset.SHA256) {
            "the log names neural asset $neural, but this checkout bundles ${NeuralAsset.SHA256}; replay it from the commit with that asset"
        }
        if (neural != null) bundledNeural
        val vocabulary = if ("vocabulary" !in header) {
            require(PredictionLogCodec.VOCABULARY_KEYS.none { it in header }) { "incomplete vocabulary header" }
            CurrentVocabulary.EMPTY
        } else {
            require(header["vocabularyPolicy"] == CurrentVocabulary.POLICY) { "unsupported vocabulary policy" }
            val boost = requireNotNull(header["newsBoost"]?.toIntOrNull()) { "invalid vocabulary news bonus" }
            val bundled = if (header["vocabulary"] == "none") CurrentVocabulary.EMPTY
                else CurrentVocabulary.parse(File("src/main/assets/${CurrentVocabulary.ASSET}").readText())
            require(header["vocabulary"] == (bundled.sha256 ?: "none")) { "vocabulary digest mismatch; replay with the original asset" }
            require(bundled.sha256 != null || boost == 0) { "news bonus without vocabulary" }
            bundled.withBoost(boost)
        }
        return ReplayBuild(prior, weights, seedPhrases, sentenceStarters, neural, vocabulary)
    }

    /** A file represents one opt-in mode; do not silently score neural slots as n-gram offers. */
    fun validateNeural(log: ParsedLog) {
        val digest = PredictionLogCodec.neuralDigest(log.header)
        require(log.fields.all { it.neuralSha256 == digest }) { "the log mixes neural field modes or its F rows disagree with #neural" }
        require(log.instances.none { i -> (i.offers.any { 'N' in it.src3 || 'P' in it.src3 } || i.tap?.src in listOf('N', 'P')) && i.field?.neuralSha256 == null }) {
            "the log has neural blend slots without a neural field digest"
        }
    }
}

/**
 * What the device built its ranker from beyond a snapshot's tables: the corpus prior and its weights,
 * which the file's header names, and the seed phrases and sentence starters, which are constants of
 * the build. The replay needs the seeds only for the prior's fade, which counts the person's own
 * pairs after a word and not the seeded ones; without a prior they change nothing.
 */
class ReplayBuild(
    val prior: CorpusPrior?,
    val priorWeights: AdaptiveNgramModel.PriorWeights = AdaptiveNgramModel.PriorWeights.DEFAULT,
    val seedPhrases: List<String> = AdaptiveNgramModel.DEFAULT_SEED_PHRASES,
    val sentenceStarters: List<String> = AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS,
    val neuralSha256: String? = null,
    val vocabulary: CurrentVocabulary = CurrentVocabulary.EMPTY,
) {
    /** The device's model as the snapshot left it: [AdaptiveNgramModel.fromTables] with this build's prior and seeds. */
    fun model(tables: AdaptiveNgramModel.Tables, dictionary: WordDictionary): AdaptiveNgramModel =
        AdaptiveNgramModel.fromTables(tables, dictionary, prior, seedPhrases, sentenceStarters, priorWeights).also { it.vocabulary = vocabulary }

    /** One line for the report. */
    val description: String
        get() = if (prior == null) "no corpus prior" else
            "corpus prior ${prior.sha256 ?: "built in memory"}, weight ${priorWeights.weight}, floor ${priorWeights.floor}, fade ${priorWeights.fade}"

    companion object {
        /** No prior: how every build before the prior ranked. */
        val NONE = ReplayBuild(null)
    }
}

/** Why a written instance is left out of the scored set; one reason per instance, in this order of precedence. */
enum class Exclusion(val label: String) {
    ABANDONED_N("abandoned A/N"),
    ABANDONED_F("abandoned A/F"),
    EMOJI_ONLY("emoji-only"),
    REVISIT("revisit"),
    INCONSISTENT("inconsistent"),
    CORRECTOR_ONLY("corrector-only"),
    DICT0("dict0"),
    SHOWN0_ONLY("shown0-only"),
    R_MARKED("R-marked"),
}

/** One prefix length of an instance's ladder and the ranked answer there. */
class LadderPoint(val p: Int, val prefix: String, val ranked: List<Pair<String, Int>>, val offer: LogRecord.Offer?) {
    /** 0-based rank of [word], or -1 when it is not in the list. */
    fun rank(word: String): Int = ranked.indexOfFirst { it.first == word }
}

/**
 * A word instance as written: its W row, its offers in arrival order, and the T, Y or A row that
 * closed it. [ordinal] is its position among the file's instances and is what every map is keyed
 * on: wids restart at 1 whenever the keyboard's process does, so a file can repeat them.
 */
class Instance(
    val ordinal: Int,
    val word: LogRecord.Word,
    val offers: List<LogRecord.Offer>,
    val outcome: LogRecord,
    val field: LogRecord.Field?,
    val chain: Int,
    /** A complete snapshot precedes this instance with no partial or torn one since. */
    val replayValid: Boolean,
    /** A hidden mutation (an X row with no word) precedes this instance since the last snapshot. */
    val shadow: Boolean,
) {
    val wid: Int get() = word.wid
    val tap: LogRecord.Tap? = outcome as? LogRecord.Tap
    val typed: LogRecord.Typed? = outcome as? LogRecord.Typed
    val abandon: LogRecord.Abandon? = outcome as? LogRecord.Abandon

    /** The word as committed: the tapped slot or the typed word after autocorrect; null when abandoned. */
    val committed: String? = tap?.word ?: typed?.committed

    /** The scoring target, normalized like the ranker's own candidates. */
    val target: String = committed?.let { AdaptiveNgramModel.normalize(it) }.orEmpty()

    /** Named by an R row: a revisit opened at this instance's key, the mis-tap signature. */
    var rMarked: Boolean = false
        internal set

    var repairApplied: String? = null
        internal set
    var repairUndone: Boolean = false
        internal set

    var exclusion: Exclusion? = null
        internal set

    /** In the scored set I. */
    val scored: Boolean get() = exclusion == null

    /** The state a replay ranker has here is not the device's: an X-shadow or no valid snapshot. */
    val unreplayable: Boolean get() = shadow || !replayValid
    val autocorrected: Boolean get() = typed?.corrected == true
    val baseline: Int get() = target.length + 1

    /** A branch-N offer the strip actually showed with a loaded dictionary: the only kind any metric counts. */
    fun qualifies(offer: LogRecord.Offer): Boolean = offer.branch == 'N' && offer.shown && offer.dict > 0

    private fun prefixOfTarget(offer: LogRecord.Offer): Boolean = target.startsWith(AdaptiveNgramModel.normalize(offer.prefix))

    /** The (instance, p) pairs the device was scored on: one qualifying offer per p below len(w) whose prefix leads to w. */
    val loggedLadder: List<LadderPoint> by lazy { ladder(includeCorrector = false) }

    /** The logged ladder with branch-C rows admitted where no branch-N row exists at that p (a secondary view). */
    val loggedLadderWithCorrector: List<LadderPoint> by lazy { ladder(includeCorrector = true) }

    private fun ladder(includeCorrector: Boolean): List<LadderPoint> {
        val byP = LinkedHashMap<Int, LadderPoint>()
        for (o in offers) {
            if (!o.shown || o.dict <= 0) continue
            if (o.branch != 'N' && !(includeCorrector && o.branch == 'C')) continue
            if (o.p >= target.length || !prefixOfTarget(o)) continue
            if (o.p !in byP) byP[o.p] = LadderPoint(o.p, o.prefix, o.ranked, o)
        }
        return byP.values.sortedBy { it.p }
    }

    /** Qualifying offers whose prefix does not lead to w (a typo path, or the word before a backspace): never a miss. */
    val typoOffers: Int get() = offers.count { qualifies(it) && !prefixOfTarget(it) }

    /** Prefix lengths where the only shown offer leading to w came from the corrector. */
    val correctorOnlyPairs: Int
        get() {
            val logged = loggedLadder.map { it.p }.toSet()
            return offers.filter { it.branch == 'C' && it.shown && it.dict > 0 && it.p < target.length && prefixOfTarget(it) }
                .map { it.p }.toSet().count { it !in logged }
        }

    /** Every prefix length of the committed word: the logged prefix where one exists, else the word's own letters. */
    fun fullLadderPrefixes(): List<Pair<Int, String>> {
        val logged = loggedLadder.associate { it.p to it.prefix }
        val raw = committed.orEmpty()
        return (0 until target.length).map { p -> p to (logged[p] ?: raw.take(p)) }
    }

    /** Whether the ranker learned from this instance on the device: a tap that called accept, or a learned typed word. */
    val mutates: Boolean get() = (tap != null && tap.src != 'E') || typed?.learned == true

    /** The previous word the device passed to accept, when it equals the verified one (ps=1); null means unigram-only. */
    val observedPrevious: String?
        get() {
            val ps = tap?.ps ?: typed?.ps ?: return null
            return if (ps) word.prev else null
        }
}

/** A typing log read into memory: the file order a replay walks, and the word instances it scores. */
class ParsedLog private constructor(
    val header: Map<String, String>,
    val entries: List<Entry>,
    val chains: List<FieldChain>,
    /** W blocks that ended without a T, Y or A row. */
    val truncated: Int,
    /** SNAP blocks without an END. */
    val tornSnapshots: Int,
    /** Rows that decoded but sat outside the block they belong to. */
    val stray: Int,
    val stats: PredictionLogCodec.DecodeStats,
    /** SHA-256 of the whole file, one line per LF. */
    val fileHash: String,
) {
    sealed class Entry {
        /** [tables] is null for a partial block or a `same` with nothing to resolve to; replay is invalid until a complete one. */
        class Snapshot(val record: LogRecord.Snapshot, val tables: AdaptiveNgramModel.Tables?) : Entry()
        class Field(val record: LogRecord.Field, val chain: Int) : Entry()
        class Word(val instance: Instance) : Entry()
        class Repair(val record: LogRecord.Repair, val instance: Instance) : Entry()
        class Mutation(val record: LogRecord.Mutation) : Entry()
        class Revisit(val record: LogRecord.Revisit) : Entry()
        class Dropped(val record: LogRecord.Dropped) : Entry()
        class FieldEnd(val record: LogRecord.FieldEnd) : Entry()
    }

    /**
     * Consecutive F rows linked by restart=1: the clustering and fingerprint unit. The fingerprint is
     * the blockHash of the chain's F..EF rows only; table blocks and anything else between fields are
     * left out, so a finished chain hashes the same in every later export of the file.
     */
    class FieldChain(val index: Int, val fields: List<LogRecord.Field>, val fingerprint: String) {
        val number: Int get() = index + 1
        val seqs: List<Int> get() = fields.map { it.seq }
    }

    class EfTotals(
        val fields: Int, val dup: Int, val stale: Int, val single: Int, val unverifiedShort: Int,
        val unverifiedForeign: Int, val unknown: Int, val revisit: Int, val x: Int,
    )

    val snapshots: List<Entry.Snapshot> = entries.filterIsInstance<Entry.Snapshot>()
    val fields: List<LogRecord.Field> = entries.filterIsInstance<Entry.Field>().map { it.record }
    val instances: List<Instance> = entries.filterIsInstance<Entry.Word>().map { it.instance }
    val mutations: List<LogRecord.Mutation> = entries.filterIsInstance<Entry.Mutation>().map { it.record }
    val repairs: List<Entry.Repair> = entries.filterIsInstance<Entry.Repair>()
    val repairOutcomes: Map<Char, Int> get() = "OPJU".associateWith { outcome -> repairs.count { it.record.outcome == outcome } }
    /** Verified applications retained after Undo, independently of prediction savings. */
    val repairBenefit: Int get() = repairs.count { it.record.outcome == 'P' && !it.instance.repairUndone &&
        AdaptiveNgramModel.normalize(it.record.original) != AdaptiveNgramModel.normalize(it.record.replacement) }
    val repairLearning: Int get() = repairs.count { it.record.learned }
    val revisitRows: Int = entries.count { it is Entry.Revisit }
    val droppedBlocks: Int = entries.filterIsInstance<Entry.Dropped>().sumOf { it.record.blocks }
    val hiddenMutations: Int get() = mutations.count { it.word.isEmpty() }
    val efTotals: EfTotals = entries.filterIsInstance<Entry.FieldEnd>().map { it.record }.let { ends ->
        EfTotals(
            ends.size, ends.sumOf { it.dup }, ends.sumOf { it.stale }, ends.sumOf { it.single }, ends.sumOf { it.unverifiedShort },
            ends.sumOf { it.unverifiedForeign }, ends.sumOf { it.unknown }, ends.sumOf { it.revisit }, ends.sumOf { it.x },
        )
    }

    /** Snapshot counts by reason, with `torn` beside them. */
    fun snapshotReasons(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (s in snapshots) counts[s.record.reason] = (counts[s.record.reason] ?: 0) + 1
        if (tornSnapshots > 0) counts["torn"] = tornSnapshots
        return counts
    }

    companion object {
        fun parse(lines: List<String>): ParsedLog {
            val stats = PredictionLogCodec.DecodeStats()
            val text = lines.joinToString("\n")
            val (header, _) = PredictionLogCodec.read(StringReader(text), stats)
            // A broken FIX could have learned or undone a repair. Invalidate replay and prior
            // correction benefit rather than silently treating its absence as a clean interval.
            val safeRecords = StringReader(text).buffered().use { reader ->
                reader.lineSequence().dropWhile { it.startsWith('#') }.mapNotNull { line ->
                    PredictionLogCodec.decode(line, stats)
                        ?: if (line.substringBefore('\t') == "FIX") LogRecord.Mutation(0, "", "") else null
                }.toList()
            }
            val repairRows = LinkedHashMap<Int, Instance>()
            val entries = ArrayList<Entry>()
            val instances = ArrayList<Instance>()
            val chainFields = ArrayList<MutableList<LogRecord.Field>>()
            val chainRows = ArrayList<MutableList<String>>()
            var truncated = 0
            var torn = 0
            var stray = 0
            var snapHead: LogRecord.Snapshot? = null
            var snapRows: MutableList<LogRecord>? = null
            var lastComplete: AdaptiveNgramModel.Tables? = null
            var replayValid = false
            var shadow = false
            var field: LogRecord.Field? = null
            var chain = -1
            var inField = false
            var wordHead: LogRecord.Word? = null
            var wordOffers = ArrayList<LogRecord.Offer>()

            fun dropTorn() {
                if (snapHead == null) return
                torn++
                replayValid = false
                snapHead = null
                snapRows = null
            }

            fun dropTruncated() {
                if (wordHead == null) return
                truncated++
                wordHead = null
                wordOffers = ArrayList()
            }

            for (r in safeRecords) {
                if (r is LogRecord.Field) {
                    dropTorn()
                    dropTruncated()
                    if (!r.restart || chain < 0) {
                        repairRows.clear()
                        chain = chainFields.size
                        chainFields.add(ArrayList())
                        chainRows.add(ArrayList())
                    }
                    chainFields[chain] += r
                    field = r
                    inField = true
                    entries += Entry.Field(r, chain)
                }
                // A table block is never part of a field: it is written before the F it belongs to,
                // and when a process died mid-field with no EF, the next process's snapshot closes it.
                if (r is LogRecord.Snapshot || r is LogRecord.Dropped) inField = false
                if (inField) chainRows[chain] += PredictionLogCodec.encode(r)
                if (r is LogRecord.FieldEnd) inField = false
                when (r) {
                    is LogRecord.Field -> Unit
                    is LogRecord.Snapshot -> {
                        repairRows.clear()
                        dropTorn()
                        dropTruncated()
                        snapHead = r
                        snapRows = ArrayList()
                    }
                    is LogRecord.Unigram, is LogRecord.Bigram -> snapRows?.add(r) ?: stray++
                    LogRecord.SnapshotEnd -> {
                        val head = snapHead
                        if (head == null) {
                            stray++
                        } else {
                            val tables = when {
                                head.partial -> null
                                head.same -> lastComplete
                                else -> tablesOf(snapRows.orEmpty())
                            }
                            if (tables != null) lastComplete = tables
                            replayValid = tables != null
                            shadow = false
                            entries += Entry.Snapshot(head, tables)
                            snapHead = null
                            snapRows = null
                        }
                    }
                    is LogRecord.Dropped -> {
                        dropTorn()
                        dropTruncated()
                        entries += Entry.Dropped(r)
                    }
                    is LogRecord.Word -> {
                        dropTorn()
                        dropTruncated()
                        wordHead = r
                        wordOffers = ArrayList()
                    }
                    is LogRecord.Offer -> if (wordHead?.wid == r.wid) wordOffers += r else stray++
                    is LogRecord.Tap, is LogRecord.Typed, is LogRecord.Abandon -> {
                        val head = wordHead
                        val wid = when (r) {
                            is LogRecord.Tap -> r.wid
                            is LogRecord.Typed -> r.wid
                            is LogRecord.Abandon -> r.wid
                            else -> -1
                        }
                        if (head == null || head.wid != wid) {
                            stray++
                        } else {
                            entries += Entry.Word(Instance(instances.size, head, wordOffers, r, field, chain, replayValid, shadow))
                            instances += (entries.last() as Entry.Word).instance
                            val repairOriginal = (r as? LogRecord.Typed)?.typed ?: (r as? LogRecord.Tap)?.takeIf { it.src != 'E' }?.word
                            if (repairOriginal != null && PredictionLogCodec.repairWord(repairOriginal)) {
                                repairRows[wid] = instances.last()
                                if (repairRows.size > PredictionLogSession.HISTORY_WORDS) repairRows.remove(repairRows.keys.first())
                            }
                            wordHead = null
                            wordOffers = ArrayList()
                        }
                    }
                    is LogRecord.Repair -> {
                        dropTorn()
                        dropTruncated()
                        val i = repairRows[r.wid]
                        val original = i?.typed?.typed ?: i?.tap?.word
                        val valid = i != null && original != null && AdaptiveNgramModel.normalize(original) == AdaptiveNgramModel.normalize(r.original) &&
                            (r.prev.isEmpty() || r.prev == AdaptiveNgramModel.START || r.prev == i.word.prev) && when (r.outcome) {
                                'P' -> i.repairApplied == null && !i.repairUndone
                                'U' -> i.repairApplied == AdaptiveNgramModel.normalize(r.replacement) && !i.repairUndone
                                else -> i.repairApplied == null && !i.repairUndone
                            }
                        if (valid) {
                            if (r.outcome == 'P') i.repairApplied = AdaptiveNgramModel.normalize(r.replacement)
                            if (r.outcome == 'U') i.repairUndone = true
                            entries += Entry.Repair(r, i)
                        } else {
                            stray++
                            if (r.learned || r.outcome == 'U') {
                                shadow = true
                                repairRows.values.forEach { it.repairUndone = true }
                                entries += Entry.Mutation(LogRecord.Mutation(field?.seq ?: 0, "", ""))
                            }
                        }
                    }
                    is LogRecord.Revisit -> {
                        dropTorn()
                        dropTruncated()
                        // The wid names the instance closed just before it, in this process.
                        instances.lastOrNull { it.wid == r.wid }?.rMarked = true
                        entries += Entry.Revisit(r)
                    }
                    is LogRecord.Mutation -> {
                        dropTorn()
                        dropTruncated()
                        if (r.word.isEmpty()) {
                            shadow = true
                            if (r.fieldSeq == 0) repairRows.values.forEach { it.repairUndone = true }
                        }
                        entries += Entry.Mutation(r)
                    }
                    is LogRecord.FieldEnd -> {
                        dropTorn()
                        dropTruncated()
                        entries += Entry.FieldEnd(r)
                    }
                }
            }
            dropTorn()
            dropTruncated()

            val chains = chainFields.indices.map { FieldChain(it, chainFields[it], PredictionLogCodec.blockHash(chainRows[it])) }
            for (instance in instances) instance.exclusion = exclusionOf(instance)
            return ParsedLog(header, entries, chains, truncated, torn, stray, stats, PredictionLogCodec.blockHash(lines))
        }

        private fun tablesOf(rows: List<LogRecord>): AdaptiveNgramModel.Tables {
            val unigrams = LinkedHashMap<String, Int>()
            val bigrams = ArrayList<Triple<String, String, Int>>()
            for (row in rows) when (row) {
                is LogRecord.Unigram -> unigrams[row.word] = row.count
                is LogRecord.Bigram -> bigrams += Triple(row.prev, row.next, row.count)
                else -> Unit
            }
            return AdaptiveNgramModel.Tables(unigrams, bigrams)
        }

        private fun exclusionOf(i: Instance): Exclusion? = when {
            i.abandon != null -> if (i.abandon.why == 'F') Exclusion.ABANDONED_F else Exclusion.ABANDONED_N
            i.tap?.src == 'E' -> Exclusion.EMOJI_ONLY
            i.word.revisit -> Exclusion.REVISIT
            i.target.isEmpty() -> Exclusion.INCONSISTENT
            i.typed != null && !i.typed.corrected && AdaptiveNgramModel.normalize(i.typed.typed) != i.target -> Exclusion.INCONSISTENT
            i.offers.none { it.branch == 'N' } -> Exclusion.CORRECTOR_ONLY
            i.offers.none { it.branch == 'N' && it.dict > 0 } -> Exclusion.DICT0
            i.offers.none { i.qualifies(it) } -> Exclusion.SHOWN0_ONLY
            i.rMarked -> Exclusion.R_MARKED
            else -> null
        }
    }
}

/**
 * Something that ranks next words and completions from a sentence and a prefix. The harness
 * scores every ranker on the same instances and walks the file in order, so a ranker that learns
 * sees each word exactly once, after it was scored, as the device did.
 */
interface Ranker {
    val name: String

    /** True when [observe] changes later answers; a candidate's frozen column declares false and is what ships without on-device training. */
    val adaptive: Boolean

    /**
     * True for a control that learns only from the file, so its frozen column would equal the
     * reference by construction; its pre-registered primary is the adaptive-regime paired potential
     * against the adaptive bigram, and the report says so. False for anything with a frozen column.
     */
    val onlineOnly: Boolean get() = false

    /** Field-chain fingerprints this ranker was trained or tuned on, for the training manifest; the report prints the file's list. */
    val trainedOn: Set<String> get() = emptySet()

    /** One line naming the ranker's fixed choices (weights, smoothing) for the report header; empty for the shipped ranker. */
    val description: String get() = ""

    /**
     * Start from a snapshot of the device's tables and the word list that field used. [build] is how the
     * file's keyboard built its ranker (its corpus prior and seeds); a replay of the shipped ranker must use it.
     */
    fun reset(tables: AdaptiveNgramModel.Tables, dictionary: WordDictionary, build: ReplayBuild)

    /** The word list changed at a field start (the swear-word setting) without a new snapshot. */
    fun dictionary(dictionary: WordDictionary) = Unit

    fun predict(sentence: String, prefix: String, limit: Int): List<Pair<String, Int>>

    /** The device learned [word] after [previous] (null at a sentence start). */
    fun observe(previous: String?, word: String)

    /** The device learned [word] with an unknown previous word. */
    fun observeWord(word: String)
}

/** The shipped ranker as the log recorded it: every answer comes from a logged offer, never from a model. */
class DeviceRanker : Ranker {
    override val name = "device"
    override val adaptive = true
    override fun reset(tables: AdaptiveNgramModel.Tables, dictionary: WordDictionary, build: ReplayBuild) = Unit
    override fun predict(sentence: String, prefix: String, limit: Int): List<Pair<String, Int>> =
        error("the device ranker answers from logged offers only")
    override fun observe(previous: String?, word: String) = Unit
    override fun observeWord(word: String) = Unit
}

/**
 * The shipped ranker rebuilt from a snapshot, with the corpus prior the file's header names, and fed
 * every mutation the file shows; its logged-ladder column must match the device.
 */
class BigramReplayRanker(override val name: String = "bigram-replay") : Ranker {
    private var model: AdaptiveNgramModel? = null
    override val adaptive = true

    override fun reset(tables: AdaptiveNgramModel.Tables, dictionary: WordDictionary, build: ReplayBuild) {
        model = build.model(tables, dictionary)
    }

    override fun dictionary(dictionary: WordDictionary) {
        model?.dictionary = dictionary
    }

    override fun predict(sentence: String, prefix: String, limit: Int): List<Pair<String, Int>> =
        model?.predictScored(sentence + prefix, limit) ?: emptyList()

    override fun observe(previous: String?, word: String) {
        model?.accept(previous, word, persist = false)
    }

    override fun observeWord(word: String) {
        model?.countWord(word)
    }
}

/**
 * The context-length control: the shipped bigram's scoring (with the file's corpus prior), unchanged, plus one term from the two
 * previous words of the stored sentence tail. It starts from the same snapshot as
 * [BigramReplayRanker] (the snapshot carries no trigram counts) and learns trigram counts
 * prequentially from the committed words the file shows, in [observe], where the bigram learns
 * its own; nothing is trained anywhere else, so the paired comparison asks one question: does a
 * second word of context help on this data.
 *
 * Scoring: score(w) = the bigram's score(w) + [TRIGRAM_WEIGHT] × c(w₋₂, w₋₁, w). The term is an
 * additive interpolation in the shipped score space (dictionary + unigram × 6 + bigram × 40), on
 * raw prequential counts with no smoothing; with no evidence for the context it is zero, so the
 * answer backs off to the bigram's, word for word and score for score. The context comes from the
 * harness's own [AdaptiveNgramModel.lastCompletedWord] and [AdaptiveNgramModel.isSentenceStart],
 * applied twice: w₋₁ is what the bigram uses, and w₋₂ is the same rule on the text before w₋₁,
 * so punctuation or a digit cuts the context as it cuts the bigram's. START stands in for w₋₂
 * only when the text before w₋₁ ends in a sentence end; an empty text before w₋₁ is the stored
 * tail's edge, not a sentence start. The tail begins at the first verified word of the sentence,
 * so what came before it may be the field's start or a pasted or pre-filled word the device saw
 * and the file does not show, and the harness cannot tell which; there the context is unknown and
 * the answer is the bigram's. Field-start and sentence-start trigrams are therefore not scored.
 *
 * This is an online-only control: nothing carries over without on-device training, so its frozen
 * column would equal the reference by construction and the pre-registered primary for it is the
 * adaptive-regime paired potential against the adaptive bigram ([Ranker.onlineOnly]).
 *
 * Learning: [observe] chains consecutive observations. When this observation's previous word is
 * the last observation's word, the last observation's previous word is w₋₂ and the trigram is
 * counted; a null previous is recorded as START, as the device records it, and an unknown
 * context ([observeWord]) or a gap breaks the chain. A reset replaces the bigram tables with the
 * snapshot and clears the chain but keeps the trigram counts learned so far, since the snapshot
 * has none to replace them with; one instance serves one evaluation.
 */
class TrigramReplayRanker(override val name: String = "trigram-replay") : Ranker {
    private var model: AdaptiveNgramModel? = null
    private val trigrams = HashMap<Pair<String, String>, MutableMap<String, Int>>()

    /** The last observation as (previous, word); null when the chain is broken. */
    private var last: Pair<String, String>? = null

    override val adaptive = true
    override val onlineOnly = true
    override val description =
        "trigram term = $TRIGRAM_WEIGHT × c(w-2, w-1, w) added to the bigram's score (additive interpolation, weight $TRIGRAM_WEIGHT " +
            "= half the shipped bigram context weight of 40; raw prequential counts, no smoothing); no evidence for the context backs off to the bigram's score exactly; " +
            "w-2 is never START at the stored tail's edge, so field-start and sentence-start trigrams are not scored"

    override fun reset(tables: AdaptiveNgramModel.Tables, dictionary: WordDictionary, build: ReplayBuild) {
        model = build.model(tables, dictionary)
        last = null
    }

    override fun dictionary(dictionary: WordDictionary) {
        model?.dictionary = dictionary
    }

    override fun predict(sentence: String, prefix: String, limit: Int): List<Pair<String, Int>> {
        val model = model ?: return emptyList()
        val beforeCursor = sentence + prefix
        // The whole pool, scored as the bigram scores it; a trigram continuation is always in it, since observe counted the pair too.
        val pool = model.predictScored(beforeCursor, Int.MAX_VALUE)
        val counts = context(beforeCursor)?.let { trigrams[it] }
        if (counts.isNullOrEmpty()) return pool.take(limit)
        return pool.map { (word, score) -> word to score + TRIGRAM_WEIGHT * (counts[word] ?: 0) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limit)
    }

    override fun observe(previous: String?, word: String) {
        model?.accept(previous, word, persist = false)
        val next = normalizeWord(word)
        if (next == null) {
            last = null
            return
        }
        val prev = previous?.let(::normalizeWord) ?: AdaptiveNgramModel.START
        val before = last?.takeIf { it.second == prev }?.first
        if (before != null) {
            val counts = trigrams.getOrPut(before to prev) { HashMap() }
            counts[next] = (counts[next] ?: 0) + 1
        }
        last = prev to next
    }

    override fun observeWord(word: String) {
        model?.countWord(word)
        last = null
    }

    /** How many trigram observations the context of this prefix has: zero means [predict] answers exactly as the bigram does. */
    internal fun evidence(sentence: String, prefix: String): Int =
        context(sentence + prefix)?.let { trigrams[it]?.values?.sum() } ?: 0

    /** (w₋₂, w₋₁) for the word being typed, or null when either is cut by punctuation or a digit, or w₋₂ lies before the stored tail. */
    private fun context(beforeCursor: String): Pair<String, String>? {
        val previous = AdaptiveNgramModel.lastCompletedWord(beforeCursor) ?: return null
        val tail = beforeCursor.dropLast(AdaptiveNgramModel.activePrefix(beforeCursor).length).trimEnd(' ', '\t')
        val head = tail.substring(0, AdaptiveNgramModel.WORD_REGEX.findAll(tail).last().range.first)
        // An empty head is the tail's edge: the word before it is unknown, never START.
        val before = AdaptiveNgramModel.lastCompletedWord(head)
            ?: AdaptiveNgramModel.START.takeIf { head.trimEnd(' ', '\t').isNotEmpty() && AdaptiveNgramModel.isSentenceStart(head) }
            ?: return null
        return before to previous
    }

    /** What [AdaptiveNgramModel.accept] learns from a word: its first word run, normalized, or nothing. */
    private fun normalizeWord(word: String): String? = AdaptiveNgramModel.WORD_REGEX.find(word)?.value?.let { AdaptiveNgramModel.normalize(it) }

    companion object {
        /** Score per observed trigram: half the shipped bigram context weight (40), fixed before any log was scored. */
        const val TRIGRAM_WEIGHT = 20
    }
}

/** Hit rates and potential savings over one set of instances on one ladder. */
class Metrics private constructor(
    val instances: Int,
    val nextN: Int,
    private val nextHits: IntArray,
    private val nextMrrSum: Double,
    val cells: Map<String, Cell>,
    /** Per instance: the mean hit@3 indicator over its scorable completion positions (p >= 1). */
    val instanceCompletion: Map<Int, Double>,
    /** Per instance with a p=0 pair: whether w was in the top 3. */
    val instanceNextHit3: Map<Int, Boolean>,
    /** Per instance: potential characters saved, max(0, len(w) - p*) or 0 when never in the top 3. */
    val instancePotential: Map<Int, Int>,
    val potentialSaved: Int,
    val baseline: Int,
) {
    class Cell {
        var pairs = 0
        var hit1 = 0
        var hit3 = 0
        var hit6 = 0
        fun rate(hits: Int) = if (pairs == 0) Double.NaN else hits.toDouble() / pairs
    }

    val nextHit1: Double get() = rate(nextHits[0], nextN)
    val nextHit3: Double get() = rate(nextHits[1], nextN)
    val nextHit6: Double get() = rate(nextHits[2], nextN)
    val nextMrr: Double get() = if (nextN == 0) Double.NaN else nextMrrSum / nextN
    val pooled: Cell get() = cells.getValue(POOLED)
    val potentialRate: Double get() = if (baseline == 0) Double.NaN else potentialSaved.toDouble() / baseline
    val meanInstanceCompletion: Double get() = if (instanceCompletion.isEmpty()) Double.NaN else instanceCompletion.values.average()

    companion object {
        const val POOLED = "pooled"
        val BUCKETS = listOf("1", "2", "3", "4", "5+")

        private fun rate(hits: Int, n: Int) = if (n == 0) Double.NaN else hits.toDouble() / n

        private fun bucket(p: Int) = if (p >= 5) "5+" else p.toString()

        fun compute(instances: List<Instance>, ladder: (Instance) -> List<LadderPoint>): Metrics {
            var nextN = 0
            val nextHits = IntArray(3)
            var nextMrr = 0.0
            val cells = LinkedHashMap<String, Cell>()
            (BUCKETS + POOLED).forEach { cells[it] = Cell() }
            val completion = LinkedHashMap<Int, Double>()
            val nextHit3 = LinkedHashMap<Int, Boolean>()
            val potential = LinkedHashMap<Int, Int>()
            var saved = 0
            var baseline = 0
            for (instance in instances) {
                val w = instance.target
                var best = -1
                var pairs = 0
                var hits = 0
                for (point in ladder(instance)) {
                    // Pairs at p == len(w) are never scored: predict may return the prefix itself.
                    if (point.p >= w.length) continue
                    val rank = point.rank(w)
                    val hit3 = rank in 0..2
                    if (point.p == 0) {
                        nextN++
                        if (rank == 0) nextHits[0]++
                        if (hit3) nextHits[1]++
                        if (rank in 0..5) nextHits[2]++
                        if (rank in 0..5) nextMrr += 1.0 / (rank + 1)
                        nextHit3[instance.ordinal] = hit3
                    } else {
                        for (cell in listOf(cells.getValue(bucket(point.p)), cells.getValue(POOLED))) {
                            cell.pairs++
                            if (rank == 0) cell.hit1++
                            if (hit3) cell.hit3++
                            if (rank in 0..5) cell.hit6++
                        }
                        pairs++
                        if (hit3) hits++
                    }
                    if (hit3 && (best < 0 || point.p < best)) best = point.p
                }
                val savedHere = if (best < 0) 0 else maxOf(0, w.length - best)
                potential[instance.ordinal] = savedHere
                saved += savedHere
                baseline += instance.baseline
                if (pairs > 0) completion[instance.ordinal] = hits.toDouble() / pairs
            }
            return Metrics(instances.size, nextN, nextHits, nextMrr, cells, completion, nextHit3, potential, saved, baseline)
        }
    }
}

/** What only the device's own log can say: what was actually tapped, and what the strip showed. */
class DeviceMetrics(instances: List<Instance>) {
    val instances = instances.size
    val realizedSaved: Int
    val modelSaved: Int
    /** Actual neural strip taps, separate from the logged n-gram reference's realized savings. */
    val neuralSaved: Int
    /** A protected personal follower absent from O.ranked, supplied only by the neural blend. */
    val personalSaved: Int
    val correctorTaps: Int
    val literalTaps: Int
    val xTaps: Int
    val rankerTaps: Int
    val modelTaps: Int
    val neuralTaps: Int
    val personalTaps: Int
    val pTaps: List<Int>
    val autocorrected: Int
    val stripPairs: Int
    val stripHits: Int
    val baseline: Int = instances.sumOf { it.baseline }

    init {
        var realized = 0
        var model = 0
        var neural = 0
        var personal = 0
        var corrector = 0
        var literal = 0
        var x = 0
        var rankerTapped = 0
        var modelTapped = 0
        var neuralTapped = 0
        var personalTapped = 0
        val taps = ArrayList<Int>()
        var corrected = 0
        var pairs = 0
        var hits = 0
        for (i in instances) {
            val tap = i.tap
            when (tap?.src) {
                'R' -> { realized += maxOf(0, i.target.length - tap.p); rankerTapped++; taps += tap.p }
                'M' -> { model += maxOf(0, i.target.length - tap.p); modelTapped++; taps += tap.p }
                'N' -> { neural += maxOf(0, i.target.length - tap.p); neuralTapped++; taps += tap.p }
                'P' -> { personal += maxOf(0, i.target.length - tap.p); personalTapped++; taps += tap.p }
                'C' -> corrector++
                'L' -> literal++
                'X' -> x++
                else -> Unit
            }
            if (i.autocorrected && !i.repairUndone) corrected++
            // A tap on a strip the log never saw says nothing about the strip that was logged.
            if (tap?.src != 'X') {
                for (point in i.loggedLadder) {
                    val offer = point.offer ?: continue
                    pairs++
                    val shown = offer.slots.indices.any { s ->
                        offer.src3.getOrNull(s)?.let { it == 'R' || it == 'M' || it == 'N' || it == 'P' } == true && AdaptiveNgramModel.normalize(offer.slots[s]) == i.target
                    }
                    if (shown) hits++
                }
            }
        }
        realizedSaved = realized
        modelSaved = model
        neuralSaved = neural
        personalSaved = personal
        correctorTaps = corrector
        literalTaps = literal
        xTaps = x
        rankerTaps = rankerTapped
        modelTaps = modelTapped
        neuralTaps = neuralTapped
        personalTaps = personalTapped
        pTaps = taps
        autocorrected = corrected
        stripPairs = pairs
        stripHits = hits
    }

    val realizedRate: Double get() = if (baseline == 0) Double.NaN else realizedSaved.toDouble() / baseline
    val tapRate: Double get() = if (instances == 0) Double.NaN else (rankerTaps + modelTaps + neuralTaps + personalTaps).toDouble() / instances
    val meanPTap: Double get() = if (pTaps.isEmpty()) Double.NaN else pTaps.average()
    val medianPTap: Double
        get() {
            if (pTaps.isEmpty()) return Double.NaN
            val sorted = pTaps.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    val autocorrectedFraction: Double get() = if (instances == 0) Double.NaN else autocorrected.toDouble() / instances
    val stripHit3: Double get() = if (stripPairs == 0) Double.NaN else stripHits.toDouble() / stripPairs
}

/**
 * The roadmap's M5 comparison, preregistered on 2026-10-13 (docs/next-steps-2026-09-26.md): what the strip
 * showed against what the plain ranker with the corpus prior would have put in the same slots. Both sides
 * are read from the phone's own offers on one logged ladder, so nothing is replayed and the comparison
 * holds on a file whose replay is invalid.
 *
 * At each logged offer the displayed side hits when a slot tagged R, M, N or P holds the target. The
 * reference hits when the target is among the first k words of that offer's `ranked` list, where k is
 * the number of slots the strip gave to prediction there: those tagged R, M, N or P and the blank ones.
 * A slot held by an emoji, an expansion (S), a correction, a literal or a word of another origin (X) is
 * given to neither side. A word instance saves `len(w) - p*` characters at the smallest logged p with a
 * hit, and the rate is characters saved over `len(w) + 1` per word, as everywhere in this harness.
 *
 * A strip tap ends an instance's ladder, and the person tapped because the strip showed the word, so the
 * logged ladder favours the displayed side on tapped words: the reference never gets the prefixes after
 * the tap. [Mode.TAP_BOUND] gives the reference its best case there (a hit at the next prefix), and
 * [Mode.TYPED_ONLY] drops tapped words altogether, which leans the other way because it removes exactly
 * the words the strip finished. The interval resamples field chains, the unit a person's typing clusters in.
 */
class DisplayedSlots private constructor(
    val mode: Mode,
    val instances: Int,
    val chains: Int,
    val baseline: Int,
    val displayedSaved: Int,
    val referenceSaved: Int,
    /** Displayed minus reference, in rate units (0.01 is one savings point). */
    val savings: CorpusEvaluation.Interval,
    val completionPairs: Int,
    val displayedCompletionHits: Int,
    val referenceCompletionHits: Int,
    /** Displayed minus reference completion hit rate over logged pairs at p >= 1, resampling the field chains that have such a pair. */
    val completion: CorpusEvaluation.Interval,
    val nextPairs: Int,
    val displayedNextHits: Int,
    val referenceNextHits: Int,
    /** Logged pairs where fewer than every slot went to prediction, by what held the others (E, S, X, C or L). */
    val heldSlots: Map<Char, Int>,
) {
    enum class Mode(val label: String) {
        AS_LOGGED("as logged"),
        TAP_BOUND("tapped words given the plain ranker's best case after the tap"),
        TYPED_ONLY("typed words only"),
    }

    val displayedRate: Double get() = rate(displayedSaved, baseline)
    val referenceRate: Double get() = rate(referenceSaved, baseline)

    /** At least [MARGIN] with an interval that excludes zero. */
    val clearsMargin: Boolean get() = savings.difference >= MARGIN && savings.low > 0.0

    companion object {
        /** The roadmap's margin: three savings points. */
        const val MARGIN = 0.03
        private const val PREDICTION = "RMNP"

        private fun rate(part: Int, whole: Int) = if (whole == 0) Double.NaN else part.toDouble() / whole

        /** Slots the strip gave to prediction at [offer]: its prediction words and its blanks. */
        fun predictionSlots(offer: LogRecord.Offer): Int = offer.src3.count { it in PREDICTION || it == 'B' }

        fun displayedHit(offer: LogRecord.Offer, target: String): Boolean = offer.slots.indices.any { s ->
            offer.src3.getOrNull(s)?.let { it in PREDICTION } == true && AdaptiveNgramModel.normalize(offer.slots[s]) == target
        }

        fun referenceHit(offer: LogRecord.Offer, target: String): Boolean =
            offer.ranked.take(predictionSlots(offer)).any { it.first == target }

        /** A tap on a strip the log never saw says nothing about the strip that was logged, as for strip_hit@3. */
        fun eligible(instance: Instance): Boolean = instance.tap?.src != 'X'

        fun compute(scored: List<Instance>, mode: Mode): DisplayedSlots {
            val instances = scored.filter { eligible(it) && (mode != Mode.TYPED_ONLY || it.typed != null) }
            val chains = instances.map { it.chain }.distinct().sorted()
            val row = chains.withIndex().associate { (n, chain) -> chain to n }
            val displayed = IntArray(chains.size)
            val reference = IntArray(chains.size)
            val baseline = IntArray(chains.size)
            val displayedHits = IntArray(chains.size)
            val referenceHits = IntArray(chains.size)
            val pairs = IntArray(chains.size)
            var nextPairs = 0
            var displayedNext = 0
            var referenceNext = 0
            val held = sortedMapOf<Char, Int>()
            for (i in instances) {
                val n = row.getValue(i.chain)
                val w = i.target
                var bestDisplayed = -1
                var bestReference = -1
                for (point in i.loggedLadder) {
                    val offer = point.offer ?: continue
                    val d = displayedHit(offer, w)
                    val r = referenceHit(offer, w)
                    if (d && bestDisplayed < 0) bestDisplayed = point.p
                    if (r && bestReference < 0) bestReference = point.p
                    if (point.p == 0) {
                        nextPairs++
                        if (d) displayedNext++
                        if (r) referenceNext++
                    } else {
                        pairs[n]++
                        if (d) displayedHits[n]++
                        if (r) referenceHits[n]++
                    }
                    offer.src3.filter { it !in PREDICTION && it != 'B' }.toSet().forEach { held[it] = (held[it] ?: 0) + 1 }
                }
                // The ladder stops at the tap: the best the plain ranker could still have done is the next prefix.
                val tap = i.tap
                if (mode == Mode.TAP_BOUND && tap != null && bestReference < 0 && tap.p + 1 < w.length) bestReference = tap.p + 1
                displayed[n] += if (bestDisplayed < 0) 0 else maxOf(0, w.length - bestDisplayed)
                reference[n] += if (bestReference < 0) 0 else maxOf(0, w.length - bestReference)
                baseline[n] += i.baseline
            }
            // A chain with no offer at one letter or more says nothing about completion. Left in, a resample made only
            // of such chains has no rate at all and would be counted as a tie, pulling the interval's end to zero.
            val observed = pairs.indices.filter { pairs[it] > 0 }
            fun only(values: IntArray) = IntArray(observed.size) { values[observed[it]] }
            return DisplayedSlots(
                mode, instances.size, chains.size, baseline.sum(), displayed.sum(), reference.sum(),
                CorpusEvaluation.pairedBootstrap(reference, displayed, baseline, names = "ranked" to "displayed"),
                pairs.sum(), displayedHits.sum(), referenceHits.sum(),
                CorpusEvaluation.pairedBootstrap(only(referenceHits), only(displayedHits), only(pairs), names = "ranked" to "displayed"),
                nextPairs, displayedNext, referenceNext, held,
            )
        }
    }
}

/** How closely a replayed ranker reproduces the device's logged lists on the pairs it can legitimately reproduce. */
class Agreement {
    var pairs = 0
    var top3 = 0
    var top6 = 0
    var scoreDiffSum = 0.0
    var scoreDiffN = 0
    /** Offers skipped because O.dict is not the harness dictionary's size. */
    var dictMismatch = 0
    /** Offers skipped because the instance sits in an X-shadow or before a valid snapshot. */
    var unreplayable = 0
    /** The first few top-3 disagreements, one line each, for the report. */
    val examples = ArrayList<String>()
    /** Every top-3 disagreement as (sentence, prefix), so a test can ask what a ranker's divergence came from. */
    val disagreements = ArrayList<Pair<String, String>>()

    val top3Rate: Double get() = if (pairs == 0) Double.NaN else top3.toDouble() / pairs
    val top6Rate: Double get() = if (pairs == 0) Double.NaN else top6.toDouble() / pairs
    val meanScoreDiff: Double get() = if (scoreDiffN == 0) Double.NaN else scoreDiffSum / scoreDiffN

    /** Below this every candidate comparison is marked INVALID; meaningful for the reference replay only, a candidate may diverge by design. */
    val valid: Boolean get() = pairs > 0 && top3Rate >= VALID_FLOOR

    companion object {
        const val VALID_FLOOR = 0.98
    }
}

/** One ranker's answers on every instance, computed in file order so learning happens as it did on the device. */
class RankerRun(
    val ranker: Ranker,
    private val logged: Map<Int, List<LadderPoint>>,
    private val full: Map<Int, List<LadderPoint>>?,
    private val loggedWithCorrector: Map<Int, List<LadderPoint>>?,
    val agreement: Agreement?,
) {
    val ladders: List<Evaluation.Ladder> = buildList {
        add(Evaluation.Ladder.LOGGED)
        if (full != null) add(Evaluation.Ladder.FULL)
        if (loggedWithCorrector != null) add(Evaluation.Ladder.LOGGED_WITH_CORRECTOR)
    }

    fun points(instance: Instance, ladder: Evaluation.Ladder): List<LadderPoint> = when (ladder) {
        Evaluation.Ladder.LOGGED -> logged[instance.ordinal]
        Evaluation.Ladder.FULL -> full?.get(instance.ordinal)
        Evaluation.Ladder.LOGGED_WITH_CORRECTOR -> loggedWithCorrector?.get(instance.ordinal)
    }.orEmpty()
}

/**
 * Builds the scored set once, runs every ranker over the file in order, and computes every metric from
 * the answers. [seedPhrases] and [sentenceStarters] are the device build's; the synthetic fixture uses its own.
 */
class Scorer(
    private val dictionaryFor: (Boolean) -> WordDictionary = PredictionLogCorpus::dictionary,
    private val seedPhrases: List<String> = AdaptiveNgramModel.DEFAULT_SEED_PHRASES,
    private val sentenceStarters: List<String> = AdaptiveNgramModel.DEFAULT_SENTENCE_STARTERS,
) {
    fun evaluate(
        log: ParsedLog,
        rankers: List<Ranker>,
        parity: PredictionLogCorpus.Parity = PredictionLogCorpus.evalFields(),
    ): Evaluation {
        val chains = log.chains.filter { chain ->
            when (parity) {
                PredictionLogCorpus.Parity.ALL -> true
                PredictionLogCorpus.Parity.ODD -> chain.number % 2 == 1
                PredictionLogCorpus.Parity.EVEN -> chain.number % 2 == 0
            }
        }.map { it.index }.toSet()
        // Only a replay needs the prior, so a device-only evaluation of a log from another build still runs.
        val build = if (rankers.all { it is DeviceRanker }) null else {
            PredictionLogCorpus.validateNeural(log)
            PredictionLogCorpus.build(log.header, seedPhrases, sentenceStarters)
        }
        val runs = rankers.map { if (it is DeviceRanker) deviceRun(log, it) else replayRun(log, it, chains, build!!) }
        return Evaluation(log, parity, chains, runs, dictionaryFor, build)
    }

    private fun deviceRun(log: ParsedLog, ranker: DeviceRanker): RankerRun {
        val logged = LinkedHashMap<Int, List<LadderPoint>>()
        val withCorrector = LinkedHashMap<Int, List<LadderPoint>>()
        for (i in log.instances) {
            if (i.target.isEmpty()) continue
            logged[i.ordinal] = i.loggedLadder
            withCorrector[i.ordinal] = i.loggedLadderWithCorrector
        }
        return RankerRun(ranker, logged, null, withCorrector, null)
    }

    private fun replayRun(log: ParsedLog, ranker: Ranker, chains: Set<Int>, build: ReplayBuild): RankerRun {
        val logged = LinkedHashMap<Int, List<LadderPoint>>()
        val full = LinkedHashMap<Int, List<LadderPoint>>()
        val agreement = Agreement()
        var profanity = false
        var live = false
        for (entry in log.entries) when (entry) {
            is ParsedLog.Entry.Snapshot -> {
                val tables = entry.tables
                if (tables != null) {
                    ranker.reset(tables, dictionaryFor(profanity), build)
                    live = true
                }
            }
            is ParsedLog.Entry.Field -> {
                profanity = entry.record.profanity
                if (live) ranker.dictionary(dictionaryFor(profanity))
            }
            is ParsedLog.Entry.Word -> {
                val i = entry.instance
                if (i.target.isNotEmpty() && live) {
                    val loggedPs = i.loggedLadder.map { it.p }.toSet()
                    val points = i.fullLadderPrefixes().map { (p, prefix) -> LadderPoint(p, prefix, ranker.predict(i.word.sentence, prefix, CANDIDATES), null) }
                    full[i.ordinal] = points
                    logged[i.ordinal] = points.filter { it.p in loggedPs }
                    if (i.scored && i.chain in chains) agree(agreement, ranker, i)
                }
                if (ranker.adaptive && i.mutates) {
                    val previous = i.observedPrevious
                    if (previous != null) ranker.observe(previous.ifEmpty { null }, i.target) else ranker.observeWord(i.target)
                }
            }
            is ParsedLog.Entry.Repair -> if (ranker.adaptive && entry.record.learned) {
                val r = entry.record
                when (r.prev) {
                    "" -> ranker.observeWord(r.replacement)
                    AdaptiveNgramModel.START -> ranker.observe(null, r.replacement)
                    else -> ranker.observe(r.prev, r.replacement)
                }
            }
            is ParsedLog.Entry.Mutation -> if (ranker.adaptive) {
                val x = entry.record
                when {
                    x.word.isEmpty() -> Unit
                    x.prev.isEmpty() -> ranker.observeWord(x.word)
                    x.prev == AdaptiveNgramModel.START -> ranker.observe(null, x.word)
                    else -> ranker.observe(x.prev, x.word)
                }
            }
            else -> Unit
        }
        return RankerRun(ranker, logged, full, null, agreement)
    }

    private fun agree(agreement: Agreement, ranker: Ranker, i: Instance) {
        val dictSize = dictionaryFor(i.field?.profanity == true).size
        for (offer in i.offers) {
            if (!i.qualifies(offer)) continue
            if (i.unreplayable) { agreement.unreplayable++; continue }
            if (offer.dict != dictSize) { agreement.dictMismatch++; continue }
            val replayed = ranker.predict(i.word.sentence, offer.prefix, CANDIDATES)
            agreement.pairs++
            if (replayed.take(3).map { it.first } == offer.ranked.take(3).map { it.first }) {
                agreement.top3++
            } else {
                agreement.disagreements += i.word.sentence to offer.prefix
                if (agreement.examples.size < MISMATCH_EXAMPLES) {
                    agreement.examples += "wid ${offer.wid} p ${offer.p} '${offer.prefix}' after '${i.word.sentence.takeLast(40)}': device ${offer.ranked.take(3)} replay ${replayed.take(3)}"
                }
            }
            if (replayed.map { it.first }.toSet() == offer.ranked.map { it.first }.toSet()) agreement.top6++
            val replayedScores = replayed.toMap()
            for ((word, score) in offer.ranked) {
                val other = replayedScores[word] ?: continue
                agreement.scoreDiffSum += kotlin.math.abs(score - other)
                agreement.scoreDiffN++
            }
        }
    }

    companion object {
        /** The device logs up to six ranked words per offer. */
        const val CANDIDATES = 6
        /** How many disagreements the report lists; `-Dlocaltype.mismatchExamples=N` lists more when a real log fails the gate. */
        val MISMATCH_EXAMPLES: Int = Integer.getInteger("localtype.mismatchExamples", 10)
    }
}

/** Everything a report prints, kept as values so a test can assert on them. */
class Evaluation(
    val log: ParsedLog,
    val parity: PredictionLogCorpus.Parity,
    val evaluatedChains: Set<Int>,
    val runs: List<RankerRun>,
    private val dictionaryFor: (Boolean) -> WordDictionary,
    /** How the replays rebuilt the device's ranker; null when only the device ran. */
    val build: ReplayBuild? = null,
) {
    enum class Ladder(val label: String) {
        LOGGED("logged ladder, censored at the tap"),
        FULL("full ladder, every prefix length"),
        LOGGED_WITH_CORRECTOR("logged ladder with branch-C rows"),
    }

    enum class Subset(val label: String) {
        PRIMARY("I"),
        NO_AUTOCORRECT("I without autocorrected instances"),
        WITH_R_MARKED("I with R-marked instances"),
        OUTSIDE_SHADOWS("I outside X-shadows"),
    }

    /** Instances in the evaluated chains, whatever their exclusion. */
    val evaluated: List<Instance> = log.instances.filter { it.chain in evaluatedChains || (it.chain < 0 && parity == PredictionLogCorpus.Parity.ALL) }

    /** The scored set I. */
    val scored: List<Instance> = evaluated.filter { it.scored }

    fun instances(subset: Subset): List<Instance> = when (subset) {
        Subset.PRIMARY -> scored
        Subset.NO_AUTOCORRECT -> scored.filter { !it.autocorrected }
        Subset.WITH_R_MARKED -> evaluated.filter { it.scored || it.exclusion == Exclusion.R_MARKED }
        Subset.OUTSIDE_SHADOWS -> scored.filter { !it.unreplayable }
    }

    fun run(name: String): RankerRun = runs.first { it.ranker.name == name }

    fun metrics(run: RankerRun, ladder: Ladder, subset: Subset = Subset.PRIMARY): Metrics = metrics(run, ladder, instances(subset))

    fun metrics(run: RankerRun, ladder: Ladder, instances: List<Instance>): Metrics = Metrics.compute(instances) { run.points(it, ladder) }

    fun device(subset: Subset = Subset.PRIMARY): DeviceMetrics = DeviceMetrics(instances(subset))

    fun displayedSlots(mode: DisplayedSlots.Mode): DisplayedSlots = DisplayedSlots.compute(scored, mode)

    /**
     * The M5 gate as preregistered, or why it cannot be read on this file. Passing needs the as-logged
     * difference and the tap bound both at [DisplayedSlots.MARGIN] or more with intervals above zero, and
     * a completion hit rate whose interval is not wholly below zero.
     */
    val displayedSlotsVerdict: String by lazy {
        val primary = displayedSlots(DisplayedSlots.Mode.AS_LOGGED)
        val bound = displayedSlots(DisplayedSlots.Mode.TAP_BOUND)
        when {
            PredictionLogCodec.neuralDigest(log.header) == null ->
                "not read: Chime was off in this file, so both sides come from the plain ranker and the difference only checks the method"
            log.header["code"].isNullOrEmpty() -> "not read: the file names no build (#code)"
            primary.instances == 0 -> "not read: no scored word instance"
            primary.completionPairs == 0 -> "not read: no offer at one letter or more, so the completion condition cannot be judged"
            !primary.clearsMargin -> "NOT PASSED: as logged, the difference is below ${fmt(DisplayedSlots.MARGIN)} or its interval includes zero"
            !bound.clearsMargin -> "NOT PASSED: with tapped words given the plain ranker's best case, the difference is below ${fmt(DisplayedSlots.MARGIN)} or its interval includes zero"
            primary.completion.high < 0.0 -> "NOT PASSED: the completion hit rate regressed, with an interval wholly below zero"
            else -> "PASSED: at least ${fmt(DisplayedSlots.MARGIN)} as logged and under the tap bound, intervals above zero, no completion regression"
        }
    }

    val exclusions: Map<Exclusion, Int> = Exclusion.entries.associateWith { reason -> evaluated.count { it.exclusion == reason } }
    val emojiTaps: Int get() = exclusions.getValue(Exclusion.EMOJI_ONLY)
    val typoOffers: Int get() = scored.sumOf { it.typoOffers }
    val correctorOnlyPairs: Int get() = scored.sumOf { it.correctorOnlyPairs }
    val offers: Int get() = log.instances.sumOf { it.offers.size }
    val scoredPairs: Int get() = scored.sumOf { it.loggedLadder.size }
    val shadowFraction: Double get() = if (scored.isEmpty()) Double.NaN else scored.count { it.unreplayable }.toDouble() / scored.size
    val psZeroRows: Int get() = log.instances.count { (it.tap?.ps ?: it.typed?.ps) == false }

    /** ss and prev recomputed from the stored sentence against what the device wrote. */
    val selfCheckMismatches: Pair<Int, Int> by lazy {
        var ss = 0
        var prev = 0
        for (i in scored) {
            if (AdaptiveNgramModel.isSentenceStart(i.word.sentence) != i.word.ss) ss++
            if (AdaptiveNgramModel.lastCompletedWord(i.word.sentence).orEmpty() != i.word.prev) prev++
        }
        ss to prev
    }

    /** The reference every candidate is compared against: the first replay bigram given. */
    val reference: RankerRun? = runs.firstOrNull { it.ranker is BigramReplayRanker }

    class Diff(val n: Int, val mean: Double, val wins: Int, val losses: Int, val ties: Int)

    class Paired(val candidate: String, val reference: String, val potential: Diff, val nextHit3: Diff, val completion: Diff)

    /**
     * Why [candidate] gets no paired comparison, or null when it does: the reference replay must be
     * valid on this file, and the candidate must not have been trained or tuned on any evaluated chain.
     */
    fun pairedRefusal(candidate: RankerRun): String? {
        val reference = reference ?: return "no replay bigram to compare against"
        if (candidate === reference || candidate.ranker is DeviceRanker) return "not a candidate"
        val agreement = reference.agreement
        if (agreement?.valid != true) return "replay agreement is ${agreement?.top3Rate?.let { fmt(it) } ?: "n/a"} on this file, below ${Agreement.VALID_FLOOR}: INVALID"
        val leaked = log.chains.filter { it.index in evaluatedChains && it.fingerprint in candidate.ranker.trainedOn }
        if (leaked.isNotEmpty()) return "candidate was trained on evaluated chain(s) " + leaked.joinToString(", ") { it.number.toString() } + ": refused"
        return null
    }

    /** Candidate minus reference per instance on the full ladder, over I outside X-shadows; no interval in v1. */
    fun paired(candidate: RankerRun): Paired? {
        val reference = reference ?: return null
        if (pairedRefusal(candidate) != null) return null
        val instances = instances(Subset.OUTSIDE_SHADOWS)
        val a = metrics(candidate, Ladder.FULL, instances)
        val b = metrics(reference, Ladder.FULL, instances)
        fun diff(values: (Metrics, Instance) -> Double?): Diff {
            val diffs = instances.mapNotNull { i ->
                val x = values(a, i) ?: return@mapNotNull null
                val y = values(b, i) ?: return@mapNotNull null
                x - y
            }
            return Diff(diffs.size, if (diffs.isEmpty()) Double.NaN else diffs.average(), diffs.count { it > 0 }, diffs.count { it < 0 }, diffs.count { it == 0.0 })
        }
        return Paired(
            candidate.ranker.name,
            reference.ranker.name,
            diff { m, i -> m.instancePotential[i.ordinal]?.toDouble() },
            diff { m, i -> m.instanceNextHit3[i.ordinal]?.let { if (it) 1.0 else 0.0 } },
            diff { m, i -> m.instanceCompletion[i.ordinal] },
        )
    }

    class Latency(val label: String, values: List<Int>) {
        private val sorted = values.sorted()
        val n: Int get() = sorted.size
        val over3000: Int = sorted.count { it > 3_000 }
        fun percentile(q: Double): Int = if (sorted.isEmpty()) 0 else sorted[((q * sorted.size).toInt()).coerceIn(0, sorted.size - 1)]
        val max: Int get() = sorted.lastOrNull() ?: 0
    }

    val predictLatency: Latency by lazy { Latency("predict (O.us)", log.instances.flatMap { i -> i.offers.map { it.us } }) }
    val refreshLatency: Latency by lazy { Latency("refresh (O.rus)", log.instances.flatMap { i -> i.offers.map { it.rus } }) }

    /** The plain-text report for one ranker. */
    fun report(run: RankerRun): String {
        val out = StringBuilder()
        val ranker = run.ranker
        val neuralMode = PredictionLogCodec.neuralDigest(log.header) != null
        val regime = if (ranker.adaptive) "adaptive (observe applied)" else "frozen (observe is a no-op)"
        out.line("LocalType prediction replay: ranker ${ranker.name}, regime $regime")
        if (ranker.description.isNotEmpty()) out.line("Ranker: ${ranker.description}")
        val referenceName = reference?.ranker?.name ?: "bigram-replay"
        if (ranker.onlineOnly) {
            out.line("Primary comparison (pre-registered): per-instance potential characters saved on the full ladder, adaptive regime, candidate minus $referenceName (adaptive), because this candidate is an online-only control: it learns only from the file, so its frozen column equals the reference by construction and the frozen comparison is identically zero; every other number is secondary and descriptive.")
        } else {
            out.line("Primary comparison (pre-registered): per-instance potential characters saved on the full ladder, frozen regime, candidate minus $referenceName; every other number is secondary and descriptive.")
        }
        out.line("Regimes: device = logged ladder, right-censored at the tap for tapped instances; bigram-replay (logged) = validity column, must match the device; bigram-replay (full) = the reference for candidates.")
        if (neuralMode) {
            out.line("Neural mode: #neural=${log.header["neural"]}; O.ranked and replay agreement describe the n-gram reference only. Neural and protected personal slots contribute to strip_hit@3, with neural_saved and personal_saved separately. CIFG is not replayed: W.sentence is a bounded, verified and sometimes redacted tail, not the full sentence the model saw. No neural agreement or full-ladder neural comparison is claimed.")
        }
        out.line("Units: characters saved count the word's letters plus its separator; backspaces are not modeled. Every hit rate is measured on words finished by a separator or a strip tap.")
        out.line("")
        out.line("Coverage")
        out.line("  file sha256: ${log.fileHash}")
        out.line("  header: " + PredictionLogCodec.HEADER_KEYS.joinToString(" ") { "$it=${log.header[it] ?: "?"}" })
        out.line("  field chains: ${log.chains.size}; evaluated (${parity.name.lowercase(Locale.ROOT)}): " + log.chains.filter { it.index in evaluatedChains }.joinToString(", ") { "chain ${it.number} (F ${it.seqs.joinToString("+")})" }.ifEmpty { "none" })
        for (chain in log.chains) out.line("    chain ${chain.number} fingerprint ${chain.fingerprint}")
        out.line("  snapshots: " + log.snapshotReasons().entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "none" })
        out.line("  DROP blocks: ${log.droppedBlocks}; truncated W blocks: ${log.truncated}; stray rows: ${log.stray}")
        out.line("  decode: malformed=${log.stats.malformed} unknown=${log.stats.unknown}")
        out.line("  instances written: ${log.instances.size}; in evaluated chains: ${evaluated.size}; |I|: ${scored.size}")
        out.line("  exclusions: " + Exclusion.entries.joinToString(", ") { "${it.label}=${exclusions.getValue(it)}" })
        val ef = log.efTotals
        out.line("  EF totals (${ef.fields} fields): dup=${ef.dup} stale=${ef.stale} single=${ef.single} unverifiedShort=${ef.unverifiedShort} unverifiedForeign=${ef.unverifiedForeign} unknown=${ef.unknown} revisit=${ef.revisit} x=${ef.x}")
        out.line("  offers: $offers total, $scoredPairs logged pairs in I, typo_offers=$typoOffers, c_excluded_pairs=$correctorOnlyPairs")
        out.line("  X rows: ${log.mutations.size} (empty prev: ${log.mutations.count { it.word.isNotEmpty() && it.prev.isEmpty() }}, hidden: ${log.hiddenMutations}); ps=0 rows: $psZeroRows; R rows: ${log.revisitRows}")
        out.line("  repair outcomes (whole file): ${log.repairOutcomes}; learned=${log.repairLearning}; retained benefit=${log.repairBenefit} (never added to prediction savings; Undo retains learning)")
        out.line("  fraction of I inside an X-shadow or before a valid snapshot: ${fmt(shadowFraction)}")
        val (ssMismatch, prevMismatch) = selfCheckMismatches
        out.line("  ss/prev self-check mismatches: ss=$ssMismatch prev=$prevMismatch")
        val dictSizes = listOf(false, true).joinToString(", ") { "${if (it) "with" else "without"} profanity ${dictionaryFor(it).size}" }
        out.line("  harness dictionaries: $dictSizes; file #dict=${log.header["dict"] ?: "?"}")
        build?.let { out.line("  replays rank with: ${it.description} (file #prior=${log.header["prior"] ?: "absent, a build before the prior"})") }
        out.line("")
        run.agreement?.let { a ->
            // Validity is the reference replay's property: a candidate diverges from the device wherever its own term reorders the top 3.
            if (run === reference) {
                out.line("Replay agreement (${ranker.name} vs device, logged branch-N shown dict>0 offers in I, outside X-shadows)")
            } else {
                out.line("Divergence from the device (informational): ${ranker.name} vs device, logged branch-N shown dict>0 offers in I, outside X-shadows; validity is judged on ${referenceName} only")
            }
            out.line("  top-3 exact: ${fmt(a.top3Rate)} (n=${a.pairs}); top-6 set: ${fmt(a.top6Rate)}; mean |score diff|: ${fmt(a.meanScoreDiff)}")
            out.line("  skipped: dict_mismatch=${a.dictMismatch} unreplayable=${a.unreplayable}")
            for (example in a.examples) out.line("  disagreement: $example")
            if (a.dictMismatch > 0) out.line("  warning: ${a.dictMismatch} offers were logged with a dictionary size the harness does not have (file #dict=${log.header["dict"] ?: "?"}, harness $dictSizes)")
            if (run === reference) {
                out.line("  validity: " + if (a.valid) "VALID (>= ${Agreement.VALID_FLOOR})" else "INVALID (below ${Agreement.VALID_FLOOR}); every candidate comparison on this file is INVALID")
            }
            out.line("")
        }
        for (ladder in run.ladders) {
            for (subset in Subset.entries) {
                if (subset != Subset.PRIMARY && ladder == Ladder.LOGGED_WITH_CORRECTOR) continue
                val kind = if (subset == Subset.PRIMARY) "" else " (secondary)"
                out.line("Metrics on ${ladder.label}, ${subset.label}$kind, regime $regime")
                val m = metrics(run, ladder, subset)
                out.line("  instances: ${m.instances}")
                out.line("  next_hit@1 ${fmt(m.nextHit1)} @3 ${fmt(m.nextHit3)} @6 ${fmt(m.nextHit6)} mrr ${fmt(m.nextMrr)} (n=${m.nextN})")
                out.line("  completion_hit@3 by p: " + Metrics.BUCKETS.joinToString(" ") { b -> val c = m.cells.getValue(b); "$b=${fmt(c.rate(c.hit3))}(${c.pairs})" })
                val pooled = m.pooled
                out.line("  completion pooled: @1 ${fmt(pooled.rate(pooled.hit1))} @3 ${fmt(pooled.rate(pooled.hit3))} @6 ${fmt(pooled.rate(pooled.hit6))} (pairs=${pooled.pairs}); mean per-instance hit@3 ${fmt(m.meanInstanceCompletion)}")
                out.line("  potential_saved ${m.potentialSaved} / ${m.baseline} = ${fmt(m.potentialRate)}")
                if (ranker is DeviceRanker && ladder == Ladder.LOGGED) {
                    val d = device(subset)
                    val uptake = if (m.potentialSaved == 0) Double.NaN else d.realizedSaved.toDouble() / m.potentialSaved
                    out.line("  realized_saved ${d.realizedSaved} / ${d.baseline} = ${fmt(d.realizedRate)}; uptake ${fmt(uptake)}; model_saved ${d.modelSaved} (never added)" +
                        if (neuralMode) "; neural_saved ${d.neuralSaved} (never added); personal_saved ${d.personalSaved} (never added)" else "")
                    out.line("  taps: ranker=${d.rankerTaps} model=${d.modelTaps}" +
                        (if (neuralMode) " neural=${d.neuralTaps} personal=${d.personalTaps}" else "") + " corrector=${d.correctorTaps} literal=${d.literalTaps} emoji=$emojiTaps x=${d.xTaps}")
                    out.line("  tap_rate ${fmt(d.tapRate)}; p_tap mean ${fmt(d.meanPTap)} median ${fmt(d.medianPTap)}; autocorrected fraction ${fmt(d.autocorrectedFraction)}")
                    out.line("  strip_hit@3 ${fmt(d.stripHit3)} (pairs=${d.stripPairs}); the gap to completion_hit@3 is the " +
                        (if (neuralMode) "neural, protected personal, " else "") + "model-word, profanity-filter and arrangement effect")
                }
                out.line("")
            }
        }
        paired(run)?.let { p ->
            out.line("Paired comparison: ${p.candidate} minus ${p.reference}, full ladder, I outside X-shadows, regime $regime")
            // The pre-registered primary is the frozen run, or the adaptive run of an online-only control; an adaptive run of anything else is secondary.
            val primaryHere = !ranker.adaptive || ranker.onlineOnly
            if (!primaryHere) out.line("  adaptive regime; not the pre-registered primary, which is this candidate's frozen column against ${p.reference}")
            fun show(label: String, d: Diff, primary: Boolean) =
                out.line("  ${if (primary) "PRIMARY" else "secondary"} $label: n=${d.n} mean diff ${fmt(d.mean)} win/loss/tie ${d.wins}/${d.losses}/${d.ties}")
            show("potential_saved", p.potential, primary = primaryHere)
            show("next_hit@3", p.nextHit3, primary = false)
            show("mean completion hit@3", p.completion, primary = false)
            out.line("  no interval is computed in v1; a win is never claimed here")
            out.line("")
        }
        if (run !== reference && ranker !is DeviceRanker) pairedRefusal(run)?.let { reason ->
            out.line("Paired comparison withheld: $reason")
            out.line("")
        }
        if (ranker is DeviceRanker) {
            out.line("Displayed slots against the plain ranker (roadmap M5, preregistered 2026-10-13): displayed minus ranked, same instances, same logged ladder, nothing replayed")
            out.line("  build: #app=${log.header["app"] ?: "?"} #code=${log.header["code"] ?: "?"}; Chime: " + if (neuralMode) "on" else "off")
            for (mode in DisplayedSlots.Mode.entries) {
                val d = displayedSlots(mode)
                val kind = if (mode == DisplayedSlots.Mode.AS_LOGGED) "PRIMARY" else "sensitivity"
                out.line("  $kind, ${mode.label}: n=${d.instances} in ${d.chains} field chains; displayed ${d.displayedSaved} / ${d.baseline} = ${fmt(d.displayedRate)}; " +
                    "ranked ${d.referenceSaved} / ${d.baseline} = ${fmt(d.referenceRate)}; difference ${points(d.savings)}")
            }
            val d = displayedSlots(DisplayedSlots.Mode.AS_LOGGED)
            out.line("  left out of ${scored.size} in I: ${scored.size - d.instances} tapped on a strip the log never saw")
            out.line("  completion hit at p>=1 (pairs=${d.completionPairs}): displayed ${fmt(d.displayedCompletionHits.toDouble() / d.completionPairs)}; " +
                "ranked ${fmt(d.referenceCompletionHits.toDouble() / d.completionPairs)}; difference ${points(d.completion)}")
            out.line("  next-word hit at p=0 (n=${d.nextPairs}): displayed ${d.displayedNextHits}; ranked ${d.referenceNextHits}")
            out.line("  logged pairs with a slot held by something else: " + d.heldSlots.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "none" } +
                " (E emoji, S expansion, X another origin; the ranked side gets as many words as the strip gave to prediction)")
            out.line("  typed words only leaves out the words a suggestion finished, so it leans against the strip; the tap bound keeps them")
            out.line("  gate: $displayedSlotsVerdict")
            out.line("")
        }
        for (latency in listOf(predictLatency, refreshLatency)) {
            out.line("Latency ${latency.label}: p50 ${latency.percentile(0.5)} p90 ${latency.percentile(0.9)} p99 ${latency.percentile(0.99)} max ${latency.max} us over ${latency.n} offers; above 3000 us: ${latency.over3000}")
        }
        out.line("  predict is the ranker alone; refresh is the whole strip refresh up to the log call. Neither replaces the corrector lookup figure.")
        return out.toString()
    }

    /** Writes one report per ranker under [dir] and returns the files. */
    fun writeReports(dir: File = File("build/reports/prediction-replay")): List<File> {
        dir.mkdirs()
        return runs.map { run ->
            File(dir, "${run.ranker.name}.txt").also { it.writeText(report(run)) }
        }
    }

    private fun StringBuilder.line(s: String) {
        append(s).append('\n')
    }

    private fun fmt(x: Double): String = if (x.isNaN()) "n/a" else String.format(Locale.ROOT, "%.3f", x)

    /** A bootstrap difference in savings points with its interval and what was resampled. */
    private fun points(i: CorpusEvaluation.Interval): String = if (i.difference.isNaN()) "n/a" else
        String.format(Locale.ROOT, "%+.2f points [%+.2f, %+.2f] (%d resamples of %d field chains)", i.difference * 100, i.low * 100, i.high * 100, i.resamples, i.lines)
}
