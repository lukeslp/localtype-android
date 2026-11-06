package dev.localtype.keyboard

import android.content.Context
import java.util.Locale

/*
 * The F-Droid distribution carries no Gemma: no modelkit, no LiteRT-LM and its native library, no model
 * download. These stand-ins give the code both distributions share the same names, and say "none".
 */

data class PinnedModel(val displayName: String, val fileName: String, val bytes: Long, val sha256: String, val url: String)

data class ModelDownloadProgress(
    val state: State,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val message: String? = null,
) {
    enum class State { IDLE, WAITING_FOR_NETWORK, DOWNLOADING, VERIFYING, DONE, FAILED }

    val isActive: Boolean get() = state == State.WAITING_FOR_NETWORK || state == State.DOWNLOADING || state == State.VERIFYING
}

typealias ModelDownloadState = ModelDownloadProgress.State

object ModelKitBridge {
    const val LOAD_TIMEOUT_MS = 120_000L

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes > 0 -> "${maxOf(1, bytes / 1024)} KB"
        else -> "0 MB"
    }

    fun sharingOffered(@Suppress("UNUSED_PARAMETER") sdk: Int): Boolean = false

    fun sharingEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    fun setSharingEnabled(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") on: Boolean) = Unit

    fun repairQueuedDownload(@Suppress("UNUSED_PARAMETER") context: Context): Result<Unit> = Result.success(Unit)
}
