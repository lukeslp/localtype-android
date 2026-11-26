package dev.localtype.keyboard

/**
 * Android lets an app use Gemini Nano only while that app is in front (ML Kit's `BACKGROUND_USE_BLOCKED`). A
 * keyboard serving another app never is, so on some phones Nano answers in LocalType's own screens and is refused
 * everywhere else, while its status still says ready. This keeps what the keyboard has learned about that in this
 * process, and decides without Android types which model a keyboard request goes to, when it waits for Gemma, and
 * what the strip says when no model answers.
 *
 * Only the keyboard's composite and the keyboard use it. The model chat and Settings call the providers from an
 * activity, where Nano is allowed, so they never consult it.
 */
class ModelFallback {
    /** What Gemini Nano did with one request. */
    sealed interface Reply<out T : Any> {
        data class Answered<T : Any>(val value: T) : Reply<T>
        /** Android refused Nano to an app that isn't in front: no fault of the request or the model. */
        data object Refused : Reply<Nothing>
        /** Not ready, no usable reply, or any other error. */
        data object Failed : Reply<Nothing>
    }

    /** What a [record]ed reply means beyond this request, for the one log line and the note Settings shows. */
    enum class Note { NONE, FIRST_REFUSAL, ANSWERED_IN_OTHER_APP }

    /** Why a keyboard request ended without an answer, which picks the strip's message. */
    enum class Failure {
        /** A model was still loading when the wait ran out, and it wasn't Gemma (or which one is unknown). */
        STILL_LOADING,
        GEMMA_STILL_LOADING,
        /** Ready, asked, and no usable answer. */
        NO_ANSWER,
        /** Gemma was the model asked, because Nano is refused here, and it had no usable answer. */
        GEMMA_NO_ANSWER,
        /** Gemma is on the phone but can't run (a failed load, or its process gave up). */
        GEMMA_FAILED,
        /** Nano is refused here and there's no Gemma to use instead. */
        NANO_REFUSED_NO_GEMMA,
        /** No model is set up. */
        SET_UP,
    }

    /** What one keyboard request came to: an answer, or why there is none. */
    data class Outcome<T : Any>(val answer: T?, val failure: Failure?)

    @Volatile private var refusedCount = 0

    /**
     * True while the keyboard serves one of LocalType's own screens (Settings' test box, the model chat). The app is
     * then in front, so Android lets Nano answer, and Nano is asked first as before.
     */
    @Volatile var servingOwnApp = false

    /** Gemma's status as the composite last read it, or null when a ready Nano meant Gemma wasn't asked. */
    @Volatile var gemmaStatus: FoundationModelStatus? = null

    /** How many times Nano has been refused in this process. A change during a request means it goes to Gemma now. */
    val refusals: Int get() = refusedCount

    /** Whether Android has refused Nano to the keyboard in this process. */
    val refusedSeen: Boolean get() = refusedCount > 0

    /**
     * Whether a keyboard request asks Gemini Nano first: always in LocalType's own screens, and elsewhere until
     * Android has refused it once. The refusal lasts for the process, since a keyboard serving another app never
     * becomes the app in front; the next process tries Nano once more.
     */
    fun asksNano(): Boolean = servingOwnApp || refusedCount == 0

    /** Notes Nano's [reply] to a keyboard request. */
    @Synchronized fun record(reply: Reply<*>): Note = when (reply) {
        Reply.Refused -> if (++refusedCount == 1) Note.FIRST_REFUSAL else Note.NONE
        is Reply.Answered -> if (!servingOwnApp) Note.ANSWERED_IN_OTHER_APP else Note.NONE
        Reply.Failed -> Note.NONE
    }

    /** Why a request ended on [status] without an answer, from what the composite last saw. */
    fun failure(status: FoundationModelStatus): Failure = failure(status, gemmaStatus, nanoRefusedHere = !asksNano())

    /**
     * Waits for the keyboard's model while it loads, up to [loadWaitMs], and asks it with [ask]. When Nano refuses that
     * try, Gemma is the model now: the request waits for Gemma to load, with the same limit, and asks once more instead
     * of failing. [onLoading] runs once per wait, with true when Gemma is the model loading, and [onLoaded] when a
     * wait ends with the model ready. [status] is the composite's, which records [gemmaStatus]; [clock] and [pause]
     * are the caller's (uptime and a coroutine delay).
     */
    suspend fun <T : Any> request(
        status: suspend () -> FoundationModelStatus,
        ask: suspend () -> T?,
        loadWaitMs: Long,
        clock: () -> Long,
        pause: suspend (Long) -> Unit,
        onLoading: (gemma: Boolean) -> Unit = {},
        onLoaded: () -> Unit = {},
    ): Outcome<T> {
        var tries = 0
        while (true) {
            var current = status()
            val deadline = clock() + loadWaitMs
            var told = false
            while (current == FoundationModelStatus.INITIALIZING && clock() < deadline) {
                if (!told) {
                    told = true
                    onLoading(gemmaStatus == FoundationModelStatus.INITIALIZING)
                }
                pause(POLL_MS)
                current = status()
            }
            if (current != FoundationModelStatus.READY) return Outcome(null, failure(current))
            if (told) onLoaded()
            val before = refusals
            ask()?.let { return Outcome(it, null) }
            tries++
            if (!retries(refusedDuring = refusals != before, tries = tries)) return Outcome(null, failure(current))
        }
    }

    companion object {
        /** The keyboard process's record, shared by the model composite and the keyboard. */
        val keyboard = ModelFallback()

        /** How often a waiting request asks again whether the model has loaded. */
        const val POLL_MS = 1_000L

        /** At most two tries: the one Nano refused, then Gemma's. */
        const val MAX_TRIES = 2

        /**
         * The composite's status once a ready Nano has been ruled out. [nano] is null when Nano isn't asked here. A
         * model that is loading will be ready soon; a downloadable one needs the person to act.
         */
        fun combine(nano: FoundationModelStatus?, gemma: FoundationModelStatus): FoundationModelStatus {
            val seen = listOfNotNull(nano, gemma)
            return when {
                FoundationModelStatus.READY in seen -> FoundationModelStatus.READY
                FoundationModelStatus.INITIALIZING in seen -> FoundationModelStatus.INITIALIZING
                FoundationModelStatus.SYSTEM_MODEL_DOWNLOADABLE in seen -> FoundationModelStatus.SYSTEM_MODEL_DOWNLOADABLE
                FoundationModelStatus.MODEL_FILE_MISSING in seen -> FoundationModelStatus.MODEL_FILE_MISSING
                else -> FoundationModelStatus.UNAVAILABLE
            }
        }

        /** Whether Gemma is asked after Nano's [reply]: whenever Nano gave no answer, refused or not. */
        fun asksGemmaAfter(reply: Reply<*>): Boolean = reply !is Reply.Answered

        /** Whether a request that came back empty is tried again: only when Nano refused it, and only once. */
        fun retries(refusedDuring: Boolean, tries: Int): Boolean = refusedDuring && tries < MAX_TRIES

        /**
         * Why a request ended on [status]. [gemma] is Gemma's last status (null when a ready Nano was asked), and
         * [nanoRefusedHere] is true when Nano isn't asked because Android refused it to the keyboard here.
         */
        fun failure(status: FoundationModelStatus, gemma: FoundationModelStatus?, nanoRefusedHere: Boolean): Failure = when {
            status == FoundationModelStatus.INITIALIZING ->
                if (gemma == FoundationModelStatus.INITIALIZING) Failure.GEMMA_STILL_LOADING else Failure.STILL_LOADING
            status == FoundationModelStatus.READY -> if (nanoRefusedHere) Failure.GEMMA_NO_ANSWER else Failure.NO_ANSWER
            gemma == FoundationModelStatus.UNAVAILABLE -> Failure.GEMMA_FAILED
            nanoRefusedHere -> Failure.NANO_REFUSED_NO_GEMMA
            else -> Failure.SET_UP
        }
    }
}
