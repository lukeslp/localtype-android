package dev.localtype.keyboard

import android.content.Context

/** No ML Kit dependency, AICore binding or SDK initialization in this distribution. */
internal class AiCoreCompletionProvider(@Suppress("UNUSED_PARAMETER") context: Context) :
    FoundationModelProvider, SystemModelDownloader {
    override val label = "Gemini Nano"
    override val displayName = "Gemini Nano"
    override suspend fun status() = FoundationModelStatus.NOT_SUPPORTED
    override suspend fun download() = FoundationModelStatus.NOT_SUPPORTED
    override suspend fun predict(context: String, limit: Int, avoid: List<String>): List<String> = emptyList()
}
