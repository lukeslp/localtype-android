package dev.localtype.keyboard

/** Main-thread state for immutable requests sent to the neural worker and its current answer. */
class NeuralRequestState {
    data class Request(val generation: Long, val sessionGeneration: Long, val context: String)

    var generation: Long = 0
        private set
    var sessionGeneration: Long = 0
        private set
    private var pending: Request? = null
    private var answerContext: String? = null
    private var words: List<String>? = null

    /** Invalidates answers on an append while allowing the worker to retain its recurrent state. */
    fun invalidate() {
        generation++
        pending = null
        answerContext = null
        words = null
    }

    /** A new field, forget, or non-append edit also needs a fresh worker session. */
    fun reset() {
        invalidate()
        sessionGeneration++
    }

    /** Nothing to queue when this exact context already has an answer or an in-flight request. */
    fun request(context: String): Request? {
        if (answerContext == context || pending?.context == context) return null
        generation++
        answerContext = null
        words = null
        return Request(generation, sessionGeneration, context).also { pending = it }
    }

    fun answer(context: String): List<String>? = words.takeIf { answerContext == context }

    /** A rejected callback must permit a retry without cancelling a newer request or answer. */
    fun abandon(request: Request) {
        if (pending == request) invalidate()
    }

    /** Both the editor's current text and its generation must still name the same request. */
    fun accepts(request: Request, currentContext: String): Boolean = pending == request &&
        request.generation == generation && request.sessionGeneration == sessionGeneration &&
        request.context == currentContext

    /** Stale completions leave a newer request or answer intact. An empty answer is cached too. */
    fun complete(request: Request, currentContext: String, words: List<String>): Boolean {
        if (!accepts(request, currentContext)) return false
        pending = null
        answerContext = request.context
        this.words = words.toList()
        return true
    }
}
