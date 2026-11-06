package dev.localtype.keyboard

import java.io.File

/**
 * A scripted typing session through the real [PredictionLogSession] and a real [AdaptiveNgramModel]
 * on the corrector tests' dictionary, ranking with the bundled corpus prior as the keyboard does,
 * written the way the keyboard's worker writes a file. No clock
 * and no randomness, so the same lines come out every run; the replay test scores the in-memory
 * generation, so a ranker or seed change moves the numbers without breaking the gate.
 *
 * The script covers every shape the harness has to handle: taps at p = 0, 1, 2 and 4, typed-through
 * words, a word ended by a period (learned at the space after it), autocorrect, a revisit with an R row,
 * corrector-branch words, an abandonment, an emoji tap,
 * a literal tap, model words with a model-answer merge, a tap on a strip the log never saw, a
 * dictionary that lands between two refreshes, a hidden strip, an unverified-short and an
 * unverified-foreign word, a pre-filled boundary, a hidden mutation followed by `SNAP unlogged`,
 * a `SNAP same` after a process restart, and a cleared file with blocks dropped before its snapshot.
 */
object PredictionLogFixture {
    /** What the script did, so the replay test can check the harness against it count by count. */
    class Expected {
        /** Every word the person finished or abandoned in the exported file's span, in order, written or not. */
        class Word(
            val target: String,
            /** T (a tap), Y (a separator), A (abandoned), E (an emoji tap), or D (dropped by the session, never written). */
            val close: Char,
            val src: Char?,
            val p: Int?,
            val corrected: Boolean,
            /** Between a hidden mutation and the next snapshot. */
            val shadow: Boolean,
            /** Smallest logged p where the target was in the device's top 3, from the offers the script made. */
            val loggedPStar: Int?,
            /** Smallest p where the device's own model, probed before it learned the word, had the target in its top 3. */
            val fullPStar: Int?,
        ) {
            /** In the scored set I, and if not, the harness's label for why. */
            var scored: Boolean = false
            var why: String? = null
            val written: Boolean get() = close != 'D'
        }

        val words = ArrayList<Word>()
        val snapshots = ArrayList<String>()
        var dropped = 0
        var hidden = 0
        var xRows = 0
        var rRows = 0
        var chains = 0
        var fields = 0
        var single = 0
        var unknown = 0
        var unverifiedShort = 0
        var unverifiedForeign = 0
        var dup = 0
        var revisit = 0
        val repairOutcomes = linkedMapOf('O' to 0, 'P' to 0, 'J' to 0, 'U' to 0)
        var fileNumber = 0
        val written: List<Word> get() = words.filter { it.written }
        val scored: List<Word> get() = words.filter { it.scored }

        /** A cleared file takes its expectations with it. */
        fun reset() {
            repairOutcomes.keys.forEach { repairOutcomes[it] = 0 }
            words.clear()
            snapshots.clear()
            dropped = 0; hidden = 0; xRows = 0; rRows = 0; chains = 0; fields = 0
            single = 0; unknown = 0; unverifiedShort = 0; unverifiedForeign = 0; dup = 0; revisit = 0
        }
    }

    class Generated(val lines: List<String>, val expected: Expected)

    /**
     * Six short seeds and six starters keep the snapshots small; the device ships more of both. A
     * replay of this file needs them for the prior's fade, so the scorer takes them (`Scorer(seedPhrases =`),
     * and the header's `#seeds` names their digest, as the device's names the shipped ones.
     */
    val SEEDS = listOf("thank you very much", "see you soon", "let me know", "good morning", "I will be there", "on my way")
    val STARTERS = listOf("I", "Thanks", "Good", "See", "Let", "Hey")

    fun generate(): List<String> = generateWithExpectations().lines

    fun generateWithExpectations(vocabulary: CurrentVocabulary = CurrentVocabulary.EMPTY,
                                 personalLearning: String? = null): Generated {
        val device = Device(TypoCorpus.dictionary, vocabulary, personalLearning)
        device.script()
        return Generated(device.writer.exported(), device.expected)
    }

    /** What the scripted shortcut expands to: a stand-in for a saved text, which no line of the file may hold. */
    const val EXPANDED = "Flat 4, 221B Baker Street. "

    /** Writes the checked-in fixture; run from app/ like the tests. */
    @JvmStatic
    fun main(args: Array<String>) {
        val target = File(args.firstOrNull() ?: "src/test/fixtures/prediction_log_sample.tsv")
        target.parentFile?.mkdirs()
        target.writeText(generate().joinToString("\n", postfix = "\n"))
        println("wrote ${target.path}")
    }

    private class MemoryStore : AdaptiveNgramModel.LearningStore {
        private val counts = LinkedHashMap<Pair<String, String>, Int>()
        override fun entries() = counts.map { (pair, count) -> Triple(pair.first, pair.second, count) }
        override fun record(previous: String, next: String) {
            counts[previous to next] = (counts[previous to next] ?: 0) + 1
        }
        override fun clear() = counts.clear()
    }

    /** The worker's file handling: header on create, blocks dropped before the first snapshot, DROP after it, `same` by hash. */
    private class Writer(
        private val dictWords: Int,
        private val dictWordsWithProfanity: Int,
        private val priorHeader: Map<String, String>,
        private val expected: Expected,
    ) {
        lateinit var session: PredictionLogSession
        private var current: MutableList<String>? = null
        private var snapWritten = false
        private var dropped = 0
        private var lastHash: String? = null
        var hiddenMutation = false
        var snapshotPending = true

        fun write(records: List<LogRecord>) {
            if (records.isEmpty()) return
            if (records.any { it is LogRecord.Mutation && it.word.isEmpty() }) hiddenMutation = true
            val snapshot = records.first() is LogRecord.Snapshot
            if (!snapshot && !snapWritten) {
                dropped++
                return
            }
            val file = current ?: create()
            records.forEach { file += PredictionLogCodec.encode(it) }
            if (snapshot && dropped > 0) {
                file += PredictionLogCodec.encode(LogRecord.Dropped(dropped))
                dropped = 0
            }
            if (snapshot) snapWritten = true
        }

        fun snapshot(reason: String, tables: AdaptiveNgramModel.Tables) {
            snapshotPending = false
            val written = if (current == null) PredictionLogCodec.SNAP_FILE else reason
            val hash = session.snapshot(written, tables.unigrams, tables.bigrams, lastHash)
            expected.snapshots += if (hash != null && hash == lastHash) PredictionLogCodec.SNAP_SAME else written
            lastHash = hash
        }

        /** Clear log in settings: the session forgets, the file goes, the next snapshot opens a new one. */
        fun clear() {
            session.reset()
            current = null
            snapWritten = false
            dropped = 0
            lastHash = null
            snapshotPending = true
            expected.reset()
        }

        /** What an export gives: the current file only. */
        fun exported(): List<String> = current.orEmpty().toList()

        private fun create(): MutableList<String> {
            expected.fileNumber++
            val header = PredictionLogCodec.HEADER_KEYS.filter {
                it != "personalLearning" || it in priorHeader
            }.associateWith { key ->
                when (key) {
                    "app" -> "0.4.5"
                    "code" -> "15"
                    "flavor" -> PredictionLogCodec.FLAVOR
                    "sdk" -> "35"
                    "file" -> expected.fileNumber.toString()
                    "dict" -> "$dictWords,$dictWordsWithProfanity"
                    "contextScope" -> "verified-sentence"
                    "contextChars" -> "160"
                    "keyChars" -> "200"
                    "candidates" -> "6"
                    "neural" -> PredictionLogCodec.NEURAL_NONE
                    "addedWords" -> AddedWords.VERSION
                    else -> (priorHeader + PredictionLogCodec.correctionHeader("fixture-repair-v1", true, true))[key].orEmpty()
                }
            }
            val file = PredictionLogCodec.encodeHeader(header).trimEnd('\n').split('\n').toMutableList()
            current = file
            return file
        }
    }

    /**
     * The keyboard as the log sees it: the text before the cursor, the word trace, the strip, the
     * model and its store, and the hooks the service posts. Every method mirrors one service path.
     */
    private class Device(private val dictionary: WordDictionary, private val vocabulary: CurrentVocabulary,
                         personalLearning: String?) {
        val expected = Expected()
        /** Loaded with the dictionary on the device, so it's absent exactly while the dictionary is. */
        private val prior = PredictionLogCorpus.bundledPrior
        val writer = Writer(
            dictionary.size, PredictionLogCorpus.dictionary(true).size,
            PredictionLogCodec.priorHeader(prior.sha256, AdaptiveNgramModel.PriorWeights.DEFAULT, PredictionLogCodec.seedsDigest(SEEDS, STARTERS)) + vocabulary.header() +
                (personalLearning?.let { mapOf("personalLearning" to it) } ?: emptyMap()),
            expected,
        )
        private val store = MemoryStore()
        private var model = fresh()
        private var session = newSession()
        private var text = ""
        private var touches = 0
        private var revisited = false
        private var stripRevision = 0
        private var slots: List<String> = emptyList()
        private var literalSlot = -1
        private var emojiSlot = -1
        private var expansionSlot = -1
        private var lastRanked: List<Pair<String, Int>> = emptyList()
        private var lastCached: List<String> = emptyList()
        private var modelMutated = false
        private var snapshotClean = false
        private var fieldSeq = 0
        private var dictLoaded = true
        private var proofreadShowing = false
        private var modelFlag = 0
        private var offerCount = 0
        private val kept = HashSet<String>()

        // Scripted answers for the next refresh: the corrector, the model cache and the emoji match.
        private var correction: String? = null
        private var cached: List<String> = emptyList()
        private var emoji: String? = null
        /** The next refresh offers an expansion (a saved text or a remembered host) in the last slot. */
        private var expansion = false

        /** The word a period ended (the text up to it, and whether it was touch-typed): typeText(".") defers its learning to the whitespace after. */
        private var pendingPeriod: Pair<String, Boolean>? = null

        /** Offers made for the open word, to find the smallest logged p with the target in the top 3. */
        private val openOffers = ArrayList<OfferEvent>()
        private var shadow = false

        private fun fresh() = AdaptiveNgramModel(store, SEEDS, dictionary, STARTERS, prior, vocabulary = vocabulary)

        private fun newSession() = PredictionLogSession(writer::write).also { writer.session = it }

        // region The script

        fun script() {
            // A first field that a Clear log in settings throws away, so the exported file is number 2.
            startField(restart = false)
            word("ok")
            endField()
            writer.clear()
            fieldSeq = 0
            // A field that reaches the worker before the new file's snapshot: both its blocks are dropped and counted.
            session.fieldStart(++fieldSeq, false, autocorrect = true, profanity = false, model = 0, emoji = true)
            session.fieldEnd()
            expected.dropped += 2

            // Chain 1: an empty editor, taps at every prefix length, autocorrect, the corrector, a revisit, an abandonment.
            startField(restart = false)
            word("Thanks", tapFrom = 0)
            word("for")
            word("the", tapFrom = 1)
            word("help", tapFrom = 2)
            separator('.')
            separator(' ')
            word("I", tapFrom = 0)
            word("will", tapFrom = 0)
            word("be", tapFrom = 1)
            word("there", tapFrom = 2)
            word("soon", tapFrom = 4)
            separator('.')
            separator(' ')
            autocorrected("teh", "the")
            autocorrected("coffe", "coffee")
            correctorTap("recieve", "receive")
            literalTap("steuber", "stubber")
            separator('.')
            separator(' ')
            word("Hey", tapFrom = 0)
            word("there", tapFrom = 0)
            word("hello", coalesced = true)
            revisitAndRetype("hello", "hey")
            separator(' ')
            abandonWithEnter("hel")
            // Ended by a period: nothing is learned until the space, and the word's block is written then.
            for (c in "sure") key(c)
            separator('.')
            separator(' ')
            word("Thanks", tapFrom = 0)
            endField()
            expected.chains++

            // Chain 2: pre-filled text, an editor that lags, a paste, a hidden mutation, then the keyboard re-shown.
            startField(restart = false, prefilled = "Dear John ")
            unknownWord("thanks")
            word("for")
            word("the", tapFrom = 1)
            word("help")
            laggedTap("he", 1)
            pastedWord("grea", "t")
            unknownWord("idea")
            word("for", tapFrom = 0)
            endField()
            startField(restart = true)
            emojiTap("pizza", "🍕")
            abandonWithEnter("hel")
            word("kubernetes")
            expansionTaken("addr")
            endField()
            expected.chains++

            // Chain 3: after a process restart, with the dictionary still loading and a model answering.
            restartProcess()
            dictLoaded = false
            startField(restart = false, model = 1)
            dictionaryLoads()
            word("Good", tapFrom = 0)
            word("morning", tapFrom = 0)
            modelTap("excellent", listOf("excellent", "sunshine"), tapAt = 1)
            unloggedStripTap("friends", listOf("sunshine", "friends", "all"))
            hiddenStrip("see", hiddenUntil = 2)
            word("you", tapFrom = 0)
            word("soon", tapFrom = 0)
            separator('.')
            separator(' ')
            hiddenStrip("ok", hiddenUntil = 4)
            word("see", tapFrom = 0)
            word("you", tapFrom = 0)
            word("later", tapFrom = 1)
            lateRepairAndUndo()
            endField()
            expected.chains++

            // The legacy fixture stays byte-stable. Vocabulary-enabled replay must also see
            // actual shared completions rather than only comparing the compatibility header.
            if (vocabulary.sha256 != null) {
                startField(restart = false)
                word("Gaza", tapFrom = 3)
                word("Gmail", tapFrom = 3)
                word("Mamdani", tapFrom = 4)
                endField()
                expected.chains++
            }
        }

        // endregion

        // region Word shapes

        /**
         * Types [word] letter by letter, tapping it the first time it is in the strip at p >= [tapFrom],
         * else finishing with a space; [coalesced] means the next key came inside the refresh debounce.
         */
        private fun word(word: String, tapFrom: Int? = null, coalesced: Boolean = false, learned: Boolean = true) {
            val target = AdaptiveNgramModel.normalize(word)
            for (p in 0..word.length) {
                if (tapFrom != null && p >= tapFrom) {
                    val slot = slots.indexOfFirst { AdaptiveNgramModel.normalize(it) == target }
                    if (slot >= 0 && slot != emojiSlot) {
                        tap(slot)
                        return
                    }
                }
                if (p < word.length) key(word[p])
            }
            separator(' ', coalesced = coalesced, learn = learned)
        }

        /** The corrector answers on the refresh that first sees the whole typo, as it does on the device. */
        private fun typeWithCorrection(typed: String, meant: String) {
            for (c in typed.dropLast(1)) key(c)
            correction = meant
            key(typed.last())
        }

        private fun autocorrected(typed: String, meant: String) {
            val before = text
            typeWithCorrection(typed, meant)
            separator(' ', autocorrect = true)
            repair(RepairEvent(before, typed, meant, 'P'))
        }

        private fun repair(event: RepairEvent) {
            session.repair(event)
            expected.repairOutcomes[event.outcome] = expected.repairOutcomes.getValue(event.outcome) + 1
        }

        private fun lateRepairAndUndo() {
            val before = text
            val previous = model.lastCompletedWord(before)
            word("tehh", coalesced = true, learned = false)
            repair(RepairEvent(before, "tehh", "the", 'O'))
            repair(RepairEvent(before, "tehh", "the", 'J'))
            model.accept(previous, "the")
            modelMutated = true
            repair(RepairEvent(before, "tehh", "the", 'P', learned = true, prev = previous))
            text = before + "the "
            refresh()
            word("team", coalesced = true)
            repair(RepairEvent(before, "tehh", "the", 'U'))
            text = before + "tehh team "
            refresh()
            word("folks")
        }

        private fun correctorTap(typed: String, meant: String) {
            typeWithCorrection(typed, meant)
            tap(1)
        }

        private fun literalTap(typed: String, correctionShown: String) {
            typeWithCorrection(typed, correctionShown)
            tap(0)
        }

        /**
         * Backspaces over the space right after [finished]: the last outcome close is still that word
         * (a p=0 refresh at the new key, when one happened, is only counted), so this is an R row.
         * Then deletes the word and ends the sentence with [replacement] typed on the tainted
         * instance, which leaves an X row with the word and its previous word once the space after
         * the period lets the keyboard learn it.
         */
        private fun revisitAndRetype(finished: String, replacement: String) {
            val previous = expected.words.last()
            previous.scored = false
            previous.why = "R-marked"
            expected.rRows++
            expected.revisit++
            repeat(finished.length + 1) { backspace() }
            // Retyped prefixes the backspaces already offered merge into them.
            expected.dup += (1..replacement.length).count { finished.startsWith(replacement.take(it)) }
            for (c in replacement) key(c)
            expected.unverifiedForeign++
            expected.xRows++
            separator('.', expectWritten = false)
        }

        private fun abandonWithEnter(letters: String) {
            for (c in letters) key(c)
            record(letters, close = 'A', why = "abandoned A/N")
            text += "\n"
            finishTrace()
            refresh()
        }

        /** The device's previous word is unverified, so the instance is unknown; the word still enters the history. */
        private fun unknownWord(word: String) {
            for (c in word) key(c)
            expected.unknown++
            expected.xRows++
            separator(' ', expectWritten = false)
        }

        /** The editor reports fewer letters than were tapped; the tap still learns, so an X row carries the word. */
        private fun laggedTap(letters: String, slot: Int) {
            for (c in letters) key(c)
            touches++
            expected.dup++
            refresh()
            expected.unverifiedShort++
            expected.xRows++
            tap(slot, expectWritten = false)
        }

        /** Pasted letters plus one typed: unverified, and the learned word leaves a hidden X that opens a shadow. */
        private fun pastedWord(pasted: String, thenTyped: String) {
            text += pasted
            refresh()
            for (c in thenTyped) key(c)
            expected.unverifiedForeign++
            expected.xRows++
            expected.hidden++
            separator(' ', expectWritten = false)
            shadow = true
        }

        /**
         * A shortcut's word typed and its saved text taken from the last slot. The offer names that slot S with
         * nothing in it and the tap writes no row. What replaced the word was not typed, so its instance is
         * abandoned, and the text that stands there afterwards is one the file never holds.
         */
        private fun expansionTaken(code: String) {
            for (c in code.dropLast(1)) key(c)
            expansion = true
            key(code.last())
            check(expansionSlot == 2 && slots[2].isEmpty()) { "the expansion must hold the last slot, blank" }
            expansion = false
            record(code, close = 'A', why = "abandoned A/N")
            text = text.dropLast(code.length) + EXPANDED
            finishTrace()
            refresh()
        }

        private fun emojiTap(word: String, emoji: String) {
            for (c in word.dropLast(1)) key(c)
            this.emoji = emoji
            key(word.last())
            tap(emojiSlot)
        }

        /** The model's words arrive after the first refresh at this prefix, merge in, and one is tapped. */
        private fun modelTap(word: String, answer: List<String>, tapAt: Int) {
            for (p in 0 until tapAt) key(word[p])
            cached = answer
            expected.dup++
            refresh()
            val slot = slots.indexOfFirst { AdaptiveNgramModel.normalize(it) == AdaptiveNgramModel.normalize(word) }
            check(slot >= 0) { "the model word must be on the strip" }
            tap(slot)
            cached = emptyList()
        }

        /** Generate-on-tap replaces the strip without a logged refresh; the tap is src X. */
        private fun unloggedStripTap(word: String, strip: List<String>) {
            setSlots(strip, literal = -1)
            tap(strip.indexOf(word))
        }

        /** The proofread row hides the strip for every offer of [word] below p = [hiddenUntil] - 1; the word is typed through. */
        private fun hiddenStrip(word: String, hiddenUntil: Int) {
            proofreadShowing = true
            // The row appearing refreshes the strip at the prefix already offered, so the p=0 offer merges as hidden.
            expected.dup++
            refresh()
            for (p in word.indices) {
                if (p + 1 >= hiddenUntil) proofreadShowing = false
                key(word[p])
            }
            proofreadShowing = false
            separator(' ', why = if (hiddenUntil > word.length) "shown0-only" else null)
        }

        // endregion

        // region Service paths

        fun startField(restart: Boolean, prefilled: String = "", model: Int = 0) {
            if (!restart) {
                text = prefilled
                finishTrace()
            }
            modelFlag = model
            if (writer.hiddenMutation) {
                writer.hiddenMutation = false
                snapshotClean = false
            }
            if (!snapshotClean || writer.snapshotPending) {
                val reason = if (modelMutated) PredictionLogCodec.SNAP_UNLOGGED else PredictionLogCodec.SNAP_PROCESS
                if (modelMutated) {
                    this.model = fresh()
                    modelMutated = false
                }
                writer.snapshot(reason, this.model.tables())
                snapshotClean = true
                shadow = false
            }
            session.fieldStart(++fieldSeq, restart, autocorrect = true, profanity = false, model = model, emoji = true)
            expected.fields++
            refresh()
        }

        fun endField() {
            pendingPeriod = null
            if (openOffers.isNotEmpty()) {
                // A word left open when the field ends closes as A/F, unless it only ever had the p=0 offer.
                val prefix = AdaptiveNgramModel.activePrefix(text)
                if (prefix.isEmpty()) expected.single++ else record(prefix, close = 'A', why = "abandoned A/F")
            }
            session.fieldEnd()
            openOffers.clear()
        }

        /** The IME process dies and comes back: a new session, a model rebuilt from the store, the field numbers carry on. */
        fun restartProcess() {
            session = newSession()
            writer.snapshotPending = true
            model = fresh()
            modelMutated = false
            snapshotClean = false
            text = ""
            finishTrace()
            openOffers.clear()
        }

        fun dictionaryLoads() {
            dictLoaded = true
            expected.dup++
            refresh()
        }

        private fun key(c: Char) {
            pendingPeriod = null
            val prefixBefore = AdaptiveNgramModel.activePrefix(text)
            if (prefixBefore.isEmpty()) {
                touches = 0
                revisited = false
            } else if (touches != prefixBefore.length) {
                touches = 0
                revisited = true
            }
            touches++
            text += c
            refresh()
        }

        private fun backspace() {
            pendingPeriod = null
            text = text.dropLast(1)
            val prefixAfter = AdaptiveNgramModel.activePrefix(text)
            if (touches > 0) touches--
            when {
                prefixAfter.isEmpty() -> {
                    touches = 0
                    revisited = false
                }
                touches != prefixAfter.length -> {
                    touches = 0
                    revisited = true
                }
            }
            refresh()
        }

        private fun finishTrace() {
            touches = 0
            revisited = false
        }

        /** refreshCandidates: predict, compose the strip as the service does, and log the offer. */
        private fun refresh() {
            val context = text
            val prefix = AdaptiveNgramModel.activePrefix(context)
            model.dictionary = if (dictLoaded) dictionary else WordDictionary.EMPTY
            model.prior = if (dictLoaded) prior else null
            val scored = model.predictScored(context, 6)
            val ranked = scored.map { it.first }
            val correction = correction?.takeIf { prefix.length >= 2 }
            val cachedNow = if (modelFlag > 0) cached.filter { it.startsWith(prefix.lowercase()) } else emptyList()
            val branch: Char
            val words: List<String>
            var literal = -1
            if (correction != null) {
                branch = 'C'
                literal = 0
                val others = ranked.filter { !it.equals(correction, ignoreCase = true) }
                words = listOf(prefix, correction, others.firstOrNull()?.let { TypingRules.applyCase(it, prefix, false) }.orEmpty())
            } else {
                branch = 'N'
                val merged = (cachedNow + ranked.filter { it !in cachedNow }).take(6)
                val sentenceStart = AdaptiveNgramModel.isSentenceStart(context)
                words = TypingRules.arrangeForStrip(merged.map { TypingRules.applyCase(it, prefix, sentenceStart) })
            }
            emojiSlot = -1
            expansionSlot = -1
            var strip = words
            if (expansion) {
                // The service's last-slot chooser: an expansion goes ahead of an emoji, and its own entry stays blank.
                expansionSlot = 2
                strip = words.toMutableList().also { it[2] = "" }
            } else emoji?.let { e ->
                emojiSlot = 2
                strip = words.toMutableList().also { it[2] = e }
            }
            setSlots(strip, literal)
            lastRanked = scored
            lastCached = cachedNow
            val src3 = String(CharArray(3) { slotSource(it, scored, cachedNow, branch) })
            offerCount++
            val us = 150 + (offerCount % 5) * 40
            val event = OfferEvent(
                context = context,
                prefix = prefix,
                touchCount = touches,
                revisited = revisited,
                mode = 'L',
                branch = branch,
                shown = !proofreadShowing,
                ranked = scored,
                slots = strip,
                src3 = src3,
                modelCount = cachedNow.size,
                dict = if (dictLoaded) dictionary.size else 0,
                us = us,
                rus = us * 2 + 80,
                stripRevision = stripRevision,
            )
            openOffers += event
            session.offer(event)
        }

        private fun setSlots(strip: List<String>, literal: Int) {
            slots = strip
            literalSlot = literal
            stripRevision++
        }

        private fun slotSource(index: Int, scored: List<Pair<String, Int>>, cachedNow: List<String>, branch: Char): Char {
            val word = slots.getOrNull(index).orEmpty()
            val normalized = AdaptiveNgramModel.normalize(word)
            return when {
                index == expansionSlot -> 'S'
                word.isEmpty() -> 'B'
                index == emojiSlot -> 'E'
                index == literalSlot -> 'L'
                branch == 'C' && index == 1 -> 'C'
                scored.any { it.first == normalized } -> 'R'
                cachedNow.any { AdaptiveNgramModel.normalize(it) == normalized } -> 'M'
                else -> 'X'
            }
        }

        private fun tapSource(index: Int, word: String): Char {
            val normalized = AdaptiveNgramModel.normalize(word)
            return when {
                index == emojiSlot -> 'E'
                index == literalSlot -> 'L'
                literalSlot == 0 && index == 1 -> 'C'
                lastRanked.any { it.first == normalized } -> 'R'
                lastCached.any { AdaptiveNgramModel.normalize(it) == normalized } -> 'M'
                else -> 'X'
            }
        }

        /** acceptCandidate. */
        private fun tap(index: Int, expectWritten: Boolean = true) {
            pendingPeriod = null
            val word = slots[index]
            val context = text
            val prefix = AdaptiveNgramModel.activePrefix(context)
            if (index == emojiSlot) {
                session.tapEmoji(index)
                record(word, close = 'E', why = "emoji-only")
                text = context.dropLast(prefix.length) + word + " "
                finishTrace()
                emoji = null
                refresh()
                return
            }
            val previous = model.lastCompletedWord(context)
            val src = tapSource(index, word)
            if (index == literalSlot) kept += word.lowercase()
            record(word, close = if (expectWritten) 'T' else 'D', src = src, p = prefix.length)
            text = context.dropLast(prefix.length) + word + " "
            model.accept(previous, word, persist = true)
            modelMutated = true
            val verified = (src != 'L' && src != 'C') || (touches == prefix.length && touches > 0)
            session.tap(TapEvent(context.dropLast(prefix.length), prefix, index, word, src, previous, stripRevision, verified))
            finishTrace()
            correction = null
            emoji = null
            refresh()
        }

        /**
         * typeSpace or a punctuation key: learn the finished word and post the typed outcome, as
         * learnJustCompletedWord does. A period is the exception, as on the device: it goes in at once
         * and the word is learned by the space that follows it (applyPendingPeriod). With [coalesced]
         * the next key arrives inside the refresh debounce and the strip is not refreshed for the empty prefix.
         */
        private fun separator(c: Char, autocorrect: Boolean = false, expectWritten: Boolean = true, why: String? = null, coalesced: Boolean = false, learn: Boolean = true) {
            val before = text
            val prefix = AdaptiveNgramModel.activePrefix(before)
            val fires = before.isNotEmpty() && before.last().isLetter()
            if (!fires) {
                // A separator after a separator: the p=0 instance closes with only its first offer.
                if (openOffers.isNotEmpty() && openOffers.all { it.prefix.isEmpty() }) expected.single++
                openOffers.clear()
                pendingPeriod?.takeIf { c == ' ' }?.let { (ended, verified) ->
                    val learned = model.learnTypedWord(ended, persist = true, isKept = { it in kept })
                    if (learned) modelMutated = true
                    session.typed(TypedEvent(ended, null, AdaptiveNgramModel.activePrefix(ended), c, learned, verified, model.lastCompletedWord(ended)))
                }
                pendingPeriod = null
                text += c
                finishTrace()
                refresh()
                return
            }
            val typedVerified = touches == prefix.length && touches > 0
            var committedText = before
            var typedRaw: String? = null
            val correction = correction
            if (autocorrect && correction != null) {
                committedText = before.dropLast(prefix.length) + correction
                typedRaw = prefix
            }
            val committed = AdaptiveNgramModel.activePrefix(committedText)
            record(committed, close = if (expectWritten) 'Y' else 'D', why = why, corrected = typedRaw != null)
            if (c == '.') {
                check(typedRaw == null && learn && !coalesced) { "the script ends only plain typed words with a period" }
                pendingPeriod = before to typedVerified
                text = before + c
                finishTrace()
                this.correction = null
                emoji = null
                refresh()
                return
            }
            val learned = learn && model.learnTypedWord(committedText, persist = true, isKept = { it in kept })
            if (learned) modelMutated = true
            text = committedText + c
            session.typed(TypedEvent(committedText, typedRaw, committed, c, learned, typedVerified, model.lastCompletedWord(committedText)))
            finishTrace()
            this.correction = null
            emoji = null
            if (!coalesced) refresh()
        }

        // endregion

        /** Books one word instance with what the device's own strip and model said about it, before the model learns it. */
        private fun record(word: String, close: Char, src: Char? = null, p: Int? = null, why: String? = null, corrected: Boolean = false) {
            val target = AdaptiveNgramModel.normalize(word)
            val context = text
            val prefix = AdaptiveNgramModel.activePrefix(context)
            val contextBeforeWord = context.dropLast(prefix.length)
            val loggedPStar = openOffers
                .filter { it.branch == 'N' && it.shown && it.dict > 0 && it.prefix.length < target.length && target.startsWith(AdaptiveNgramModel.normalize(it.prefix)) }
                .filter { o -> o.ranked.take(3).any { it.first == target } }
                .minOfOrNull { it.prefix.length }
            val fullPStar = if (close == 'T' || close == 'Y') {
                (0 until target.length).firstOrNull { q -> model.predictScored(contextBeforeWord + word.take(q), 6).take(3).any { it.first == target } }
            } else null
            val entry = Expected.Word(target, close, src, p, corrected, shadow, loggedPStar, fullPStar)
            entry.scored = (close == 'T' || close == 'Y') && why == null
            entry.why = why
            expected.words += entry
            openOffers.clear()
        }
    }
}
