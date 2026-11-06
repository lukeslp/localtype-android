package dev.localtype.keyboard

import android.content.Context

/**
 * The F-Droid distribution has no on-device language model: no Gemini Nano and no Gemma. Every request
 * says so at once, and the keyboard and settings show no model controls.
 */
object FoundationModelProviderFactory {
    private object None : FoundationModelProvider, FoundationModelInstaller {
        override val label = "On-device model"
        override val displayName = "Gemma"
        override suspend fun status() = FoundationModelStatus.NOT_SUPPORTED
        override suspend fun predict(context: String, limit: Int, avoid: List<String>): List<String> = emptyList()
        override fun installedModel(): InstalledModel? = null
        override fun install(context: Context, source: android.net.Uri, onProgress: (Int) -> Unit, onResult: (Result<InstalledModel>) -> Unit) =
            onResult(Result.failure(UnsupportedOperationException("No model in this distribution")))
        override fun remove() = Unit
    }

    fun create(context: Context): FoundationModelProvider = None

    fun chatProviders(context: Context): List<FoundationModelProvider> = emptyList()

    fun installer(context: Context): FoundationModelInstaller = None

    fun systemDownloader(context: Context): SystemModelDownloader = AiCoreCompletionProvider(context.applicationContext)

    fun onModelFilesChanged(context: Context) = Unit
}
