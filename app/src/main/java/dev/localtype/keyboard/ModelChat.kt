package dev.localtype.keyboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One line of the model chat: the person's message, a model's reply, or a note that no reply came.
 * [model] names the model that answered (or was asked, for a note).
 */
internal data class ChatLine(val kind: Kind, val text: String, val model: String? = null) {
    enum class Kind { PERSON, MODEL, NOTE }
}

/**
 * The pure parts of the model chat that About opens: which model can answer, what it is told and how
 * its reply is tidied. The conversation lives only in the chat screen's memory. None of it is saved,
 * logged, learned from or sent anywhere.
 */
internal object ModelChat {
    /** A message box limit, so one message always fits the prompt with room for the conversation before it. */
    const val MAX_MESSAGE_CHARS = 1_000
    /** Gemini Nano's Prompt API takes about 4,000 tokens; this stays well inside it, and inside Gemma's prefill. */
    const val MAX_PROMPT_CHARS = 3_000
    /** Gemini Nano's ceiling for one reply. Gemma gets the same, so both answer at chat length. */
    const val MAX_OUTPUT_TOKENS = 256
    const val TEMPERATURE = 0.7f
    const val TOP_K = 40

    enum class State { CHECKING, READY, LOADING, FAILED, NOT_SET_UP }

    /** What the chat can do now. [index] is the provider that is ready, loading or failed; -1 otherwise. */
    data class Availability(val state: State, val index: Int = -1)

    private const val HEADER =
        "You are a friendly assistant built into LocalType, a phone keyboard app, chatting with the person using it. " +
            "You run entirely on this phone without the internet, so you cannot look anything up.\n" +
            "Answer briefly in plain text, without Markdown.\n" +
            "Write only your next reply in this conversation.\n"

    /**
     * The chat as one prompt: the newest lines that fit [maxChars], oldest first. Notes are left out,
     * since the model never said them. The latest message is always kept, cut to fit if it must be.
     */
    fun prompt(lines: List<ChatLine>, maxChars: Int = MAX_PROMPT_CHARS): String {
        val spoken = lines.filter { it.kind != ChatLine.Kind.NOTE && it.text.isNotBlank() }
        var room = maxChars - HEADER.length
        val kept = ArrayDeque<String>()
        for (line in spoken.asReversed()) {
            val speaker = if (line.kind == ChatLine.Kind.PERSON) "Person" else "You"
            val entry = "$speaker: ${line.text.trim()}"
            if (entry.length + 1 > room) {
                if (kept.isEmpty()) kept.addFirst(entry.take((room - 1).coerceAtLeast(0)))
                break
            }
            kept.addFirst(entry)
            room -= entry.length + 1
        }
        return HEADER + "\n" + kept.joinToString("\n")
    }

    /**
     * Tidies a reply: drops a speaker label the model wrote, stops where it began writing the person's
     * next line, and removes Markdown emphasis. Null when nothing is left.
     */
    fun cleanReply(raw: String?): String? {
        var text = raw?.trim().orEmpty()
        text = text.replace(Regex("^(you|assistant|model|gemma|gemini nano)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        Regex("(^|\\n)\\s*person\\s*:", RegexOption.IGNORE_CASE).find(text)?.let { text = text.substring(0, it.range.first) }
        text = text.replace("**", "").replace("__", "")
        text = text.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        text = text.replace(Regex("\\n{3,}"), "\n\n").trim()
        return text.ifEmpty { null }
    }

    /**
     * Reads statuses in preference order (Gemini Nano, then Gemma). [statuses] may stop early at the
     * first ready one, as [check] does, so a ready Nano never starts a Gemma load.
     */
    fun availability(statuses: List<FoundationModelStatus>): Availability {
        statuses.indexOf(FoundationModelStatus.READY).takeIf { it >= 0 }?.let { return Availability(State.READY, it) }
        statuses.indexOf(FoundationModelStatus.INITIALIZING).takeIf { it >= 0 }?.let { return Availability(State.LOADING, it) }
        statuses.indexOf(FoundationModelStatus.UNAVAILABLE).takeIf { it >= 0 }?.let { return Availability(State.FAILED, it) }
        return Availability(State.NOT_SET_UP)
    }

    /** Asks each provider's status in order, stopping at the first ready one. A Gemma status starts its load. */
    suspend fun check(providers: List<FoundationModelProvider>): Availability {
        val statuses = ArrayList<FoundationModelStatus>(providers.size)
        for (provider in providers) {
            val status = withContext(Dispatchers.Default) { runCatching { provider.status() }.getOrDefault(FoundationModelStatus.UNAVAILABLE) }
            statuses += status
            if (status == FoundationModelStatus.READY) break
        }
        return availability(statuses)
    }

    /**
     * Asks the first ready provider and falls through to the next when it gives no usable answer, as
     * the keyboard does. The reply carries the name of the model that wrote it; null when none did.
     */
    suspend fun reply(providers: List<FoundationModelProvider>, lines: List<ChatLine>): ChatLine? {
        val prompt = prompt(lines)
        for (provider in providers) {
            val ready = withContext(Dispatchers.Default) { runCatching { provider.status() }.getOrNull() } == FoundationModelStatus.READY
            if (!ready) continue
            val text = withContext(Dispatchers.Default) { runCatching { provider.generate(prompt) }.getOrNull() }
            cleanReply(text)?.let { return ChatLine(ChatLine.Kind.MODEL, it, provider.label) }
        }
        return null
    }

    /**
     * [check], repeated every [pollMs] while a model is loading, for at most [waitMs]: a load that never
     * settles (a status that keeps timing out, say) is not polled forever. Each result goes to [onEach];
     * the last is returned, and may still be LOADING.
     */
    suspend fun checkUntilSettled(
        providers: List<FoundationModelProvider>,
        waitMs: Long = LOAD_WAIT_MS,
        pollMs: Long = 1_000L,
        onEach: (Availability) -> Unit = {},
    ): Availability {
        var waited = 0L
        while (true) {
            val next = check(providers)
            onEach(next)
            if (next.state != State.LOADING || waited >= waitMs) return next
            delay(pollMs)
            waited += pollMs.coerceAtLeast(1L)
        }
    }

    /**
     * [reply], after waiting up to [waitMs] for a model that is loading. Gemma unloads after a few quiet
     * minutes, so a message sent later reloads it first; the wait matches the model process's load watchdog.
     */
    suspend fun answer(
        providers: List<FoundationModelProvider>,
        lines: List<ChatLine>,
        waitMs: Long = LOAD_WAIT_MS,
        pollMs: Long = 1_000L,
    ): ChatLine? {
        checkUntilSettled(providers, waitMs, pollMs)
        return reply(providers, lines)
    }

    const val LOAD_WAIT_MS = ModelKitBridge.LOAD_TIMEOUT_MS
}

/**
 * Counts quick taps on the version line in About. [tap] returns how many more are needed, and 0 when
 * the last one lands, which also starts the count again. A pause longer than [gapMs] starts it over.
 */
internal class SecretTaps(private val needed: Int = 5, private val gapMs: Long = 1_500L) {
    private var count = 0
    private var last = Long.MIN_VALUE

    fun tap(nowMs: Long): Int {
        count = if (last != Long.MIN_VALUE && nowMs - last <= gapMs) count + 1 else 1
        last = nowMs
        val left = needed - count
        if (left <= 0) {
            count = 0
            last = Long.MIN_VALUE
            return 0
        }
        return left
    }
}
