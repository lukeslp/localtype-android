package dev.localtype.keyboard

import android.content.Context
import dev.localtype.modelkit.ModelDownloads

/**
 * Preference order: a ready OS-managed AICore model, then an imported Gemma LiteRT-LM model.
 * Both paths execute locally; unavailable providers fall through to the small n-gram predictor.
 */
object FoundationModelProviderFactory {
    @Volatile private var composite: CompositeFoundationModelProvider? = null
    @Volatile private var gemma: LiteRtGemmaCompletionProvider? = null
    @Volatile private var aiCore: AiCoreCompletionProvider? = null

    fun create(context: Context): FoundationModelProvider = synchronized(this) {
        composite ?: CompositeFoundationModelProvider(context.applicationContext, aiCoreProvider(context), gemmaProvider(context))
            .also { composite = it }
    }

    /**
     * The same two providers the keyboard uses, in its preference order, for the model chat, which
     * names the one that answered. Their loading, idle unload and Keep model loaded are shared.
     */
    fun chatProviders(context: Context): List<FoundationModelProvider> = listOf(aiCoreProvider(context), gemmaProvider(context))

    fun installer(context: Context): FoundationModelInstaller = gemmaProvider(context)

    fun systemDownloader(context: Context): SystemModelDownloader = aiCoreProvider(context)

    fun onModelFilesChanged(context: Context) = gemmaProvider(context).onModelFilesChanged()

    private fun aiCoreProvider(context: Context): AiCoreCompletionProvider = synchronized(this) {
        aiCore ?: AiCoreCompletionProvider(context.applicationContext).also { aiCore = it }
    }

    private fun gemmaProvider(context: Context): LiteRtGemmaCompletionProvider = synchronized(this) {
        gemma ?: LiteRtGemmaCompletionProvider(context.applicationContext).also {
            gemma = it
            ModelDownloads.filesChanged = { changed -> onModelFilesChanged(changed) }
        }
    }
}

/**
 * The keyboard's model: Gemini Nano first, then Gemma. Android refuses Nano to a keyboard serving another app on some
 * phones while its status still says ready; after the first refusal in a process, [ModelFallback] keeps Nano out of
 * the keyboard's requests and readiness in other apps, so callers wait on Gemma's loading and ask Gemma. In
 * LocalType's own screens Nano is still asked first.
 */
private class CompositeFoundationModelProvider(
    private val appContext: Context,
    private val nano: AiCoreCompletionProvider,
    private val gemma: LiteRtGemmaCompletionProvider,
    private val fallback: ModelFallback = ModelFallback.keyboard,
) : FoundationModelProvider {
    override val label: String = "On-device model"

    override suspend fun status(): FoundationModelStatus {
        // Ask in preference order and stop at a ready Nano, so a ready Gemini Nano never binds the :model process or
        // starts a Gemma load that would go unused. Where Nano is refused, Gemma's readiness is the keyboard's.
        val nanoStatus = if (fallback.asksNano()) nano.status() else null
        if (nanoStatus == FoundationModelStatus.READY) {
            fallback.gemmaStatus = null
            return FoundationModelStatus.READY
        }
        return ModelFallback.combine(nanoStatus, gemmaStatus())
    }

    private suspend fun gemmaStatus(): FoundationModelStatus = gemma.status().also { fallback.gemmaStatus = it }

    /** Nano's answer when it is asked and gives one; otherwise Gemma's, if Gemma is ready. */
    private suspend fun <T : Any> answer(fromNano: suspend () -> ModelFallback.Reply<T>, fromGemma: suspend () -> T?): T? {
        if (fallback.asksNano() && nano.status() == FoundationModelStatus.READY) {
            val reply = fromNano()
            note(fallback.record(reply))
            if (!ModelFallback.asksGemmaAfter(reply)) return (reply as ModelFallback.Reply.Answered).value
        }
        // A Gemma that isn't loaded starts loading here and answers on a later try; the keyboard waits for it.
        return if (gemmaStatus() == FoundationModelStatus.READY) fromGemma() else null
    }

    private fun note(note: ModelFallback.Note) {
        when (note) {
            ModelFallback.Note.FIRST_REFUSAL -> {
                // Once per process, and nothing of the request: no text, no prompt.
                android.util.Log.i(TAG, "Gemini Nano refused to the keyboard outside LocalType (ML Kit BACKGROUND_USE_BLOCKED); Gemma serves other apps")
                KeyboardSettings.rememberNanoRefusedToKeyboard(appContext, true)
            }
            // Android let Nano answer the keyboard in another app after all: Settings stops saying it can't.
            ModelFallback.Note.ANSWERED_IN_OTHER_APP -> KeyboardSettings.rememberNanoRefusedToKeyboard(appContext, false)
            ModelFallback.Note.NONE -> Unit
        }
    }

    override suspend fun predict(context: String, limit: Int, avoid: List<String>): List<String> =
        answer({ nano.predictReply(context, limit, avoid) }, { gemma.predict(context, limit, avoid).takeIf { it.isNotEmpty() } })
            .orEmpty()

    override suspend fun proofread(text: String): String? =
        answer({ nano.proofreadReply(text) }, { gemma.proofread(text) })

    override suspend fun generate(prompt: String): String? = generate(prompt) { it }

    /** A reply [accept] takes: Nano's first, and Gemma's when Nano gives none or none that [accept] takes. */
    override suspend fun generate(prompt: String, accept: (String) -> String?): String? =
        answer({ nano.generateReply(prompt).accepting(accept) }, { gemma.generate(prompt)?.let(accept) })

    private fun ModelFallback.Reply<String>.accepting(accept: (String) -> String?): ModelFallback.Reply<String> =
        if (this is ModelFallback.Reply.Answered) accept(value)?.let { ModelFallback.Reply.Answered(it) } ?: ModelFallback.Reply.Failed else this

    override fun setKeepLoaded(keep: Boolean) {
        nano.setKeepLoaded(keep)
        gemma.setKeepLoaded(keep)
    }

    override fun close() {
        nano.close()
        gemma.close()
    }

    private companion object {
        const val TAG = "LocalTypeModel"
    }
}
