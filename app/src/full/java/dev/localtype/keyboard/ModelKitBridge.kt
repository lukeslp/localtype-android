package dev.localtype.keyboard

import android.content.Context
import dev.localtype.modelkit.ModelDownloads
import dev.localtype.modelkit.ModelHost
import dev.localtype.modelkit.ModelImportRules
import dev.localtype.modelkit.ModelSharing

/*
 * Code shared by both distributions names modelkit's types and calls only through this file. The full
 * distribution carries modelkit (Gemma's download, engine and the door to LocalLauncher); the F-Droid
 * distribution has its own copy of this file with no Gemma at all.
 */

typealias PinnedModel = dev.localtype.modelkit.PinnedModel
typealias ModelDownloadProgress = dev.localtype.modelkit.ModelDownloadProgress
typealias ModelDownloadState = dev.localtype.modelkit.ModelDownloadProgress.State

object ModelKitBridge {
    /** How long a first Gemma load may take before a caller gives up waiting. */
    const val LOAD_TIMEOUT_MS = ModelHost.LOAD_TIMEOUT_MS

    fun formatBytes(bytes: Long): String = ModelImportRules.formatBytes(bytes)

    /** Whether this Android can offer Share Gemma with LocalLauncher at all. */
    fun sharingOffered(sdk: Int): Boolean = ModelSharing.offered(sdk)

    fun sharingEnabled(context: Context): Boolean = ModelSharing.enabled(context)

    fun setSharingEnabled(context: Context, on: Boolean) = ModelSharing.setEnabled(context, on)

    /** Settings' resume step for a download Android queued under an older network rule. */
    fun repairQueuedDownload(context: Context): Result<Unit> = ModelDownloads.repairQueuedVpnConstraint(context)
}
