package dev.localtype.keyboard

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

    override suspend fun proofread(text: String): String? = proofreadReply(text).value()

    override suspend fun generate(prompt: String): String? = generateReply(prompt).value()

    override suspend fun predict(context: String, limit: Int, avoid: List<String>): List<String> =
        predictReply(context, limit, avoid).value().orEmpty()

    /** [proofread] for the keyboard's composite, which also needs to know when Android refused Nano. */
    suspend fun proofreadReply(text: String): ModelFallback.Reply<String> = reply {
        model.generateContent(proofreadPrompt(text)).candidates.firstOrNull()?.text?.let { cleanProofread(text, it) }
    }

    suspend fun generateReply(prompt: String): ModelFallback.Reply<String> = reply {
        model.generateContent(generateContentRequest(TextPart(prompt)) {
            temperature = ModelChat.TEMPERATURE
            topK = ModelChat.TOP_K
            maxOutputTokens = ModelChat.MAX_OUTPUT_TOKENS
        }).candidates.firstOrNull()?.text
    }

    suspend fun predictReply(context: String, limit: Int, avoid: List<String>): ModelFallback.Reply<List<String>> = reply {
        model.generateContent(predictionPrompt(context, limit, avoid))
            .candidates
            .firstOrNull()
            ?.text
            .orEmpty()
            .let { raw -> parsePhraseCandidates(raw, limit + avoid.size).filter { it.substringBefore(' ') !in avoid }.take(limit) }
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Runs one request when Nano is ready. Android's refusal to an app that isn't in front comes back as ML Kit's
     * [GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED]; it is [ModelFallback.Reply.Refused], kept apart from a real
     * failure. Nothing is logged here: the composite logs the first refusal once.
     */
    private suspend fun <T : Any> reply(request: suspend () -> T?): ModelFallback.Reply<T> = withContext<ModelFallback.Reply<T>>(Dispatchers.Default) {
        if (status() != FoundationModelStatus.READY) return@withContext ModelFallback.Reply.Failed
        try {
            request()?.let { ModelFallback.Reply.Answered(it) } ?: ModelFallback.Reply.Failed
        } catch (error: Throwable) {
            // Only this coroutine's own cancellation goes on up; anything ML Kit throws is a reply.
            ensureActive()
            if (refusedInBackground(error)) ModelFallback.Reply.Refused else ModelFallback.Reply.Failed
        }
    }

    private fun <T : Any> ModelFallback.Reply<T>.value(): T? = (this as? ModelFallback.Reply.Answered)?.value

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

        /** ML Kit's refusal to an app that isn't in front, wherever it sits in the cause chain. */
        fun refusedInBackground(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.take(8).any {
                it is GenAiException && it.errorCode == GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED
            }
    }
}
