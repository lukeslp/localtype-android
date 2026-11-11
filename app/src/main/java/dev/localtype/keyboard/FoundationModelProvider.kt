package dev.localtype.keyboard

import android.content.Context
import android.net.Uri

/**
 * Contract for an on-device foundation model. Implementations must never make network calls and
 * must return quickly when the required device model or local model file is unavailable.
 */
interface FoundationModelProvider : AutoCloseable {
    val label: String

    suspend fun status(): FoundationModelStatus

    /**
     * Produces up to [limit] continuations of one or two words ([parsePhraseCandidates]) for the
     * text before the cursor. This function runs off the IME thread; callers must discard stale
     * results after editing resumes.
     */
    suspend fun predict(context: String, limit: Int = 3, avoid: List<String> = emptyList()): List<String>

    /** A corrected version of [text] (spelling, grammar, punctuation), or null when unavailable. */
    suspend fun proofread(text: String): String? = null

    /**
     * A free-form reply to [prompt] for the model chat ([ModelChat]), or null when unavailable. The
     * prompt and the reply are never logged or kept.
     */
    suspend fun generate(prompt: String): String? = null

    /** Keep the model in memory instead of unloading it after a few idle minutes. */
    fun setKeepLoaded(keep: Boolean) = Unit

    override fun close() = Unit
}

/** Downloads, imports and removes the Gemma model file the on-device runtime loads. */
interface FoundationModelInstaller {
    val displayName: String

    /**
     * The model the runtime will load: an imported file if there is one, otherwise the downloaded
     * Gemma. Null when neither is installed.
     */
    fun installedModel(): InstalledModel?

    /**
     * Copies [source] into private storage, reporting whole-number percent progress. Callbacks run off
     * the main thread. Only one import runs at a time; a second call fails without touching any file.
     */
    fun install(context: Context, source: Uri, onProgress: (Int) -> Unit, onResult: (Result<InstalledModel>) -> Unit)

    /**
     * Percent of a running import (0 while the file is checked), or null when none is running. It is
     * process-wide, so a recreated settings screen can pick up an import an earlier one started.
     */
    val importProgress: Int? get() = null

    /** Installed or orphaned model/metadata files still requiring an explicit removal retry. */
    fun hasModelFiles(): Boolean = installedModel() != null

    /** Throws when any requested model file could not be removed. */
    fun remove()

    /** True when the other app (LocalLauncher) has Gemma and lends it, so this app needs no copy of its own. */
    suspend fun sharedFromPeer(): Boolean = false

    /** True when this app downloaded the pinned Gemma and the other app has and lends one too: one is enough. */
    suspend fun peerHasCopyToo(): Boolean = false

    /** A pinned model this build can fetch by itself, or null when only import is possible. */
    val downloadableModel: PinnedModel? get() = null

    fun startDownload(context: Context, wifiOnly: Boolean): Result<Unit> = Result.failure(UnsupportedOperationException())

    fun cancelDownload(context: Context) = Unit

    fun downloadProgress(context: Context): ModelDownloadProgress = ModelDownloadProgress(ModelDownloadState.IDLE)

    /** Bytes of an unfinished download kept for resuming; 0 when there is none. Call this off the main thread. */
    fun partialDownloadBytes(): Long = 0L
}

data class InstalledModel(val name: String, val sizeBytes: Long)

/** Hugging Face can't be reached: the phone is offline, or the app's network access is switched off. */
class NetworkPermissionDenied : IllegalStateException(
    "LocalType can't reach Hugging Face. Check that the phone is online. On GrapheneOS, also make sure Network is turned on for LocalType in App info.",
)

/** A user-initiated download hook for an OS-managed on-device model, such as Gemini Nano. */
interface SystemModelDownloader {
    val displayName: String
    suspend fun status(): FoundationModelStatus
    suspend fun download(): FoundationModelStatus
}

enum class FoundationModelStatus {
    READY,
    SYSTEM_MODEL_DOWNLOADABLE,
    MODEL_FILE_MISSING,
    INITIALIZING,
    UNAVAILABLE,
    /** The OS model service exists but doesn't offer this feature on this phone. */
    NOT_SUPPORTED,
}
