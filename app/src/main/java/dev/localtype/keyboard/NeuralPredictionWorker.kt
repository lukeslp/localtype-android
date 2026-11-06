package dev.localtype.keyboard

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log

/** One worker owns the recurrent state. Neither loading nor predicting runs on the typing thread. */
class NeuralPredictionWorker(context: Context, private val main: Handler) {
    private val assets = context.applicationContext.assets
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var model: CifgModel? = null
    private var session: CifgModel.Session? = null
    private var sessionGeneration = -1L
    private var correctionSession: CifgModel.Session? = null
    @Volatile private var latestCorrection = 0L
    private var correctionInput: CorrectionInput? = null
    private var multiwordDictionary: WordDictionary? = null
    private var multiwordSearch: MultiwordCorrection? = null
    /** Laid out on the keys of the language the last correction was typed in. */
    private var correctionOffsets = TouchOffsets()
    private var offsetsLanguage = LanguageProfile.ENGLISH
    private val correctionCorrector = Corrector(
        dictionary = { correctionInput!!.dictionary }, lexicon = { correctionInput!!.lexicon },
        isKnown = { it in correctionInput!!.protectedWords }, isKept = { it in correctionInput!!.kept },
        blocked = { it in correctionInput!!.blocked },
        contextCount = { _, word -> correctionInput!!.counts[word] ?: 0 },
        touchOffsets = { correctionOffsets },
        language = { correctionInput?.language ?: LanguageProfile.ENGLISH },
    )
    @Volatile private var latest: NeuralRequestState.Request? = null
    @Volatile private var closed = false

    private fun handler(): Handler = worker ?: HandlerThread("localtype-neural", Process.THREAD_PRIORITY_BACKGROUND).let {
        it.start()
        thread = it
        Handler(it.looper).also { h -> worker = h }
    }

    /** Called only for an opted-in field. Failed reads are settled and logged once for the process. */
    fun load(publish: (Boolean) -> Unit) {
        if (closed) return
        handler().post {
            val loaded = synchronized(LOAD_LOCK) {
                if (!processSettled) {
                    processModel = try {
                        assets.open(NeuralAsset.FILE_NAME).use(NeuralAsset::load)
                    } catch (_: Exception) {
                        Log.w("LocalType", "Neural prediction asset unavailable; disabled for this process")
                        null
                    }
                    processSettled = true
                }
                processModel
            }
            model = loaded
            main.post { if (!closed) publish(loaded != null) }
        }
    }

    /** At most one waiting context; a running older answer is discarded before publication. */
    fun predict(request: NeuralRequestState.Request, publish: (List<String>?) -> Unit) {
        if (closed) return
        latest = request
        val h = handler()
        h.removeCallbacksAndMessages(INFERENCE)
        h.postAtTime({
            if (closed || latest !== request) return@postAtTime
            val loaded = model ?: return@postAtTime
            if (session == null || sessionGeneration != request.sessionGeneration) {
                session = loaded.newSession()
                sessionGeneration = request.sessionGeneration
            }
            // The read reaches the sentence start when it fits the safe editor bound. Tokenizing,
            // trimming and all model arithmetic stay here; no context is written to a store.
            val words = try {
                session!!.predict(NeuralContext.sentence(request.context), 12).map { it.word }
            } catch (_: Exception) {
                synchronized(LOAD_LOCK) {
                    if (processModel != null) Log.w("LocalType", "Neural predictions unavailable; disabled for this process")
                    processModel = null
                }
                session = null
                model = null
                null
            }
            if (closed || latest !== request) return@postAtTime
            main.post { if (!closed && latest === request) publish(words) }
        }, INFERENCE, SystemClock.uptimeMillis())
    }

    fun cancel(reset: Boolean = false) {
        latest = null
        worker?.removeCallbacksAndMessages(INFERENCE)
        if (reset) worker?.post {
            session = null
            sessionGeneration = -1L
        }
        if (reset) cancelCorrection()
    }

    /** Immutable spelling candidates only; this session never shares prediction recurrent state. */
    fun scoreCorrection(id: Long, context: String, candidates: List<String>, publish: (List<CifgModel.Candidate>?) -> Unit) {
        require(candidates.size <= CorrectionSnapshot.MAX_CANDIDATES)
        if (closed) return
        latestCorrection = id
        val words = candidates.toList()
        val h = handler()
        h.removeCallbacksAndMessages(CORRECTION)
        h.postAtTime({
            if (closed || latestCorrection != id) return@postAtTime
            val loaded = model ?: return@postAtTime
            val scores = runCatching {
                val scorer = correctionSession ?: loaded.newSession().also { correctionSession = it }
                scorer.scoreWords(NeuralContext.sentence(context), words)
            }.getOrNull()
            main.post { if (!closed && latestCorrection == id) publish(scores) }
        }, CORRECTION, SystemClock.uptimeMillis())
    }

    fun cancelCorrection() {
        latestCorrection++
        worker?.removeCallbacksAndMessages(CORRECTION)
        worker?.post { correctionSession = null; correctionInput = null; correctionCorrector.forgetRecentAnswers() }
    }

    /** Expanded spelling search, context and optional neural scoring all stay off the typing thread. */
    fun evaluateCorrection(
        id: Long, input: CorrectionInput, prior: CorpusPrior?, policy: CorrectionPolicy, useNeural: Boolean,
        publish: (CorrectionDecision?) -> Unit,
    ) {
        if (closed) return
        latestCorrection = id
        val h = handler()
        h.removeCallbacksAndMessages(CORRECTION)
        h.postAtTime({
            if (closed || latestCorrection != id) return@postAtTime
            val decision = runCatching {
                correctionInput = input
                if (input.language !== offsetsLanguage) {
                    offsetsLanguage = input.language
                    correctionOffsets = if (input.language.english) TouchOffsets() else TouchOffsets(input.language.geometry)
                }
                correctionOffsets.load(input.offsets)
                val pool = correctionCorrector.candidates(input.typed, input.touches, input.previous, input.sentenceStart, input.afterComma)
                val scores = if (useNeural && model != null && pool.candidates.isNotEmpty()) {
                    val scorer = correctionSession ?: model!!.newSession().also { correctionSession = it }
                    scorer.scoreWords(NeuralContext.sentence(input.contextBeforeWord), pool.candidates.map { it.word })
                } else emptyList()
                val chosen = policy.decide(pool, CorrectionContext.capture(pool, prior, input.previous, scores))
                val possessive = if (pool.legacy?.automatic != true) correctionCorrector.possessiveSuggestion(input.typed) else null
                if (possessive != null) {
                    chosen.copy(suggestion = possessive, reasons = chosen.reasons + "manual-possessive", lateAutomaticEligible = false)
                } else if (!chosen.automatic.changed && pool.contextualEligible && pool.legacy?.automatic != true && input.language.english) {
                    if (multiwordDictionary !== input.dictionary) {
                        multiwordDictionary = input.dictionary
                        multiwordSearch = MultiwordCorrection(input.dictionary)
                    }
                    // Over a single-word offer only a one-slip split ("ofbthe" → "of the") can win, so skip repairs.
                    val split = multiwordSearch!!.suggest(input.typed, protected = pool.guardReason != null,
                        context = { left, right -> prior?.followers(left)?.firstOrNull { it.word == right }?.logProbability },
                        blocked = { it in input.blocked }, exactOnly = chosen.suggestion != null) {
                        correctionCorrector.candidates(it).candidates.take(3).map { candidate -> candidate.word }
                    }
                    if (split != null && MultiwordCorrection.preferred(split, chosen.suggestion)) chosen.copy(suggestion = split.text, automatic = pool.unchanged,
                        reasons = chosen.reasons + "manual-multiword" +
                            if (split.unusualPairs.isNotEmpty()) listOf("unusual-letter-pairs") else emptyList(),
                        lateAutomaticEligible = false) else chosen
                } else chosen
            }.getOrNull()
            correctionInput = null
            correctionCorrector.forgetRecentAnswers()
            main.post { if (!closed && latestCorrection == id) publish(decision) }
        }, CORRECTION, SystemClock.uptimeMillis())
    }

    fun close() {
        closed = true
        cancel()
        worker?.removeCallbacksAndMessages(null)
        // Drop private recurrent state on its owning thread before terminating it.
        worker?.post { session = null; correctionSession = null; model = null }
        thread?.quitSafely()
    }

    companion object {
        private val LOAD_LOCK = Any()
        private val INFERENCE = Any()
        private val CORRECTION = Any()
        @Volatile private var processModel: CifgModel? = null
        @Volatile private var processSettled = false
        val unavailable: Boolean get() = processSettled && processModel == null
    }
}
