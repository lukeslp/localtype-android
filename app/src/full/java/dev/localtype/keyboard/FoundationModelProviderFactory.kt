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
        composite ?: CompositeFoundationModelProvider(providers = chatProviders(context)).also { composite = it }
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

private class CompositeFoundationModelProvider(
    private val providers: List<FoundationModelProvider>,
) : FoundationModelProvider {
    override val label: String = "On-device model"

    override suspend fun status(): FoundationModelStatus {
        // Ask in preference order and stop at the first ready provider, so a ready Gemini Nano
        // never binds the :model process or starts a Gemma load that would go unused.
        val seen = ArrayList<FoundationModelStatus>(providers.size)
        for (provider in providers) {
            val status = provider.status()
            if (status == FoundationModelStatus.READY) return FoundationModelStatus.READY
            seen += status
        }
        return when {
            // A model that is loading will be ready soon; a downloadable one needs the person to act.
            FoundationModelStatus.INITIALIZING in seen -> FoundationModelStatus.INITIALIZING
            FoundationModelStatus.SYSTEM_MODEL_DOWNLOADABLE in seen -> FoundationModelStatus.SYSTEM_MODEL_DOWNLOADABLE
            FoundationModelStatus.MODEL_FILE_MISSING in seen -> FoundationModelStatus.MODEL_FILE_MISSING
            else -> FoundationModelStatus.UNAVAILABLE
        }
    }

    override suspend fun predict(context: String, limit: Int, avoid: List<String>): List<String> {
        providers.forEach { provider ->
            if (provider.status() == FoundationModelStatus.READY) {
                provider.predict(context, limit, avoid).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return emptyList()
    }

    override suspend fun proofread(text: String): String? {
        providers.forEach { provider ->
            if (provider.status() == FoundationModelStatus.READY) provider.proofread(text)?.let { return it }
        }
        return null
    }

    override fun setKeepLoaded(keep: Boolean) = providers.forEach { it.setKeepLoaded(keep) }

    override fun close() = providers.forEach { it.close() }
}
