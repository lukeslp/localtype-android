package dev.localtype.keyboard

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Uses an eligible device's AICore-managed Gemini Nano model. No model bytes are bundled. */
internal class AiCoreCompletionProvider(private val appContext: android.content.Context) : FoundationModelProvider, SystemModelDownloader {
    /** Created on first use: phones without AICore (such as GrapheneOS) may throw here. */
    private val model by lazy { Generation.getClient() }

    override val label = "Gemini Nano"
    override val displayName = "Gemini Nano"

    private val aiCoreInstalled: Boolean by lazy {
        runCatching { appContext.packageManager.getPackageInfo(AICORE_PACKAGE, 0) }.isSuccess
    }

    override suspend fun status(): FoundationModelStatus = withContext(Dispatchers.Default) {
        if (!aiCoreInstalled) return@withContext FoundationModelStatus.NOT_SUPPORTED
        val raw = runCatching { model.checkStatus() }
        raw.exceptionOrNull()?.let { error ->
            android.util.Log.w(TAG, "AICore status unavailable: ${error.message}")
            // FEATURE_NOT_FOUND (606) or no AICore at all: Gemini Nano isn't offered on this phone.
            val message = error.message.orEmpty()
            if ("606" in message || "FEATURE_NOT_FOUND" in message || error is NoClassDefFoundError || error is SecurityException) {
                return@withContext FoundationModelStatus.NOT_SUPPORTED
            }
        }
        when (raw.getOrNull()) {
            FeatureStatus.AVAILABLE -> FoundationModelStatus.READY
            FeatureStatus.DOWNLOADABLE -> FoundationModelStatus.SYSTEM_MODEL_DOWNLOADABLE
            FeatureStatus.DOWNLOADING -> FoundationModelStatus.INITIALIZING
            // ML Kit reports UNAVAILABLE when the phone doesn't offer the feature at all.
            FeatureStatus.UNAVAILABLE -> FoundationModelStatus.NOT_SUPPORTED
            else -> FoundationModelStatus.UNAVAILABLE
        }
    }

    override suspend fun proofread(text: String): String? = withContext(Dispatchers.Default) {
        if (status() != FoundationModelStatus.READY) return@withContext null
        runCatching {
            model.generateContent(proofreadPrompt(text)).candidates.firstOrNull()?.text?.let { cleanProofread(text, it) }
        }.getOrNull()
    }

    override suspend fun generate(prompt: String): String? = withContext(Dispatchers.Default) {
        if (status() != FoundationModelStatus.READY) return@withContext null
        runCatching {
            model.generateContent(generateContentRequest(TextPart(prompt)) {
                temperature = ModelChat.TEMPERATURE
                topK = ModelChat.TOP_K
                maxOutputTokens = ModelChat.MAX_OUTPUT_TOKENS
            }).candidates.firstOrNull()?.text
        }.getOrNull()
    }

    override suspend fun predict(context: String, limit: Int, avoid: List<String>): List<String> = withContext(Dispatchers.Default) {
        if (status() != FoundationModelStatus.READY) return@withContext emptyList()
        runCatching {
            model.generateContent(predictionPrompt(context, limit, avoid))
                .candidates
                .firstOrNull()
                ?.text
                .orEmpty()
                .let { raw -> parsePhraseCandidates(raw, limit + avoid.size).filter { it.substringBefore(' ') !in avoid }.take(limit) }
        }.getOrDefault(emptyList())
    }

    override suspend fun download(): FoundationModelStatus = withContext(Dispatchers.Default) {
        if (status() == FoundationModelStatus.SYSTEM_MODEL_DOWNLOADABLE) {
            runCatching { model.download().collect { } }
        }
        status()
    }

    override fun close() {
        runCatching { model.close() }
    }

    private companion object {
        const val TAG = "LocalTypeModel"
        const val AICORE_PACKAGE = "com.google.android.aicore"
    }
}
