package dev.localtype.keyboard

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.localtype.modelkit.GemmaArtifact
import dev.localtype.modelkit.GemmaModelFiles
import dev.localtype.modelkit.GemmaModelStore
import dev.localtype.modelkit.GemmaStatus
import dev.localtype.modelkit.ModelDownloadProgress
import dev.localtype.modelkit.ModelDownloads
import dev.localtype.modelkit.ModelImportRules
import dev.localtype.modelkit.PinnedModel
import dev.localtype.modelkit.SharedModelClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gemma for the keyboard. File handling (download, import, removal) happens here, in the
 * keyboard's process; the model itself runs in the separate `:model` process through
 * [RemoteModelClient], so a native crash or low-memory kill can't take the keyboard down.
 */
/**
 * Whether the other app's STATUS answer means it has a Gemma of its own, loaded or not: any state but a missing file.
 * Its door reports only its own file, so an app borrowing this one's copy answers MODEL_FILE_MISSING.
 */
internal fun peerHasModel(answer: SharedModelClient.Peer): Boolean =
    answer is SharedModelClient.Peer.Answered && answer.status != GemmaStatus.MODEL_FILE_MISSING

internal class LiteRtGemmaCompletionProvider(
    private val appContext: Context,
) : FoundationModelProvider, FoundationModelInstaller {
    private val files = GemmaModelFiles(appContext)
    private val client = RemoteModelClient(appContext)
    /**
     * LocalLauncher's door, for when its copy of Gemma is the one on this phone. The keyboard is a priority caller
     * there, so the bind keeps LocalLauncher's model process at the keyboard's or the chat's own priority.
     */
    private val peer = SharedModelClient(appContext, LAUNCHER_PACKAGE,
        appContext.resources.getStringArray(R.array.modelkit_known_certs).toList(), priority = true)
    @Volatile private var usingPeer = false

    // Imports share one pending file, and the copy outlives the settings screen that started it.
    private val importRunning = AtomicBoolean(false)
    @Volatile private var importPercent: Int? = null
    override val importProgress: Int? get() = importPercent

    override val label = "Gemma"
    override val displayName = "Gemma"

    private val modelDir: File get() = files.directory
    private val modelFile: File get() = files.imported
    private val nameFile: File get() = files.importedName
    private val downloaded: GemmaModelStore get() = files.downloaded

    override val downloadableModel: PinnedModel get() = GemmaArtifact.MODEL

    override fun startDownload(context: Context, wifiOnly: Boolean): Result<Unit> {
        // GrapheneOS can switch off an app's Network permission while still reporting it as granted,
        // and a job would then wait forever. If the phone is online but this app can't even resolve
        // the model host, network access is blocked for this app. Call this off the main thread.
        if (networkBlockedForApp(context)) return Result.failure(NetworkPermissionDenied())
        val memory = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        activityManager.getMemoryInfo(memory)
        val verdict = ModelImportRules.check(
            fileName = GemmaArtifact.MODEL.fileName,
            sizeBytes = GemmaArtifact.MODEL.bytes,
            freeBytes = modelDir.apply { mkdirs() }.usableSpace + (downloaded.partial.takeIf { it.isFile }?.length() ?: 0L),
            totalRamBytes = memory.totalMem,
            lowRamDevice = activityManager.isLowRamDevice,
        )
        if (verdict is ModelImportRules.Verdict.Rejected) return Result.failure(IllegalStateException(verdict.reason))
        return ModelDownloads.start(context, wifiOnly)
    }

    /**
     * An app whose network is blocked sees no active network and can't resolve hosts, which looks
     * the same as being offline. Either way a scheduled job would just wait, so ask the person.
     */
    private fun networkBlockedForApp(context: Context): Boolean =
        runCatching { java.net.InetAddress.getByName(java.net.URL(GemmaArtifact.MODEL.url).host) }.isFailure

    override fun cancelDownload(context: Context) = ModelDownloads.cancel(context)

    override fun downloadProgress(context: Context): ModelDownloadProgress = ModelDownloads.readProgress(context)

    override fun partialDownloadBytes(): Long = downloaded.partial.takeIf { it.isFile }?.length() ?: 0L

    /** Called after a download lands so the model process drops its old engine. */
    fun onModelFilesChanged() = client.reload()

    /**
     * Checks files locally first, so a phone without a model never starts LocalType's model process. With
     * no model of its own, LocalType asks LocalLauncher, whose copy it then uses ([usingPeer]); a phone
     * keeps one copy of Gemma between the two apps.
     */
    override suspend fun status(): FoundationModelStatus {
        if (files.active() != null) return client.status().also { usingPeer = false }
        val answer = askPeer(peek = false)
        return if (answer is SharedModelClient.Peer.Answered) FoundationModelStatus.valueOf(answer.status.name)
        else FoundationModelStatus.MODEL_FILE_MISSING
    }

    /** As a priority caller the keyboard's status makes LocalLauncher load its copy; a [peek] only asks. */
    private suspend fun askPeer(peek: Boolean): SharedModelClient.Peer = peer.status(peek).also { answer ->
        usingPeer = answer is SharedModelClient.Peer.Answered && answer.status != GemmaStatus.MODEL_FILE_MISSING
    }

    override suspend fun predict(context: String, limit: Int, avoid: List<String>): List<String> {
        if (!borrowing()) return client.predict(context, limit, avoid)
        val ask = KeyboardGemma.predict(context, limit, avoid)
        return KeyboardGemma.readPrediction(peer.ask(ask, ModelRequestGate.REQUEST_TIMEOUT_MS), limit, avoid)
    }

    override suspend fun proofread(text: String): String? {
        if (!borrowing()) return client.proofread(text)
        return KeyboardGemma.readProofread(text, peer.ask(KeyboardGemma.proofread(text), RemoteModelClient.PROOFREAD_TIMEOUT_MS))
    }

    override suspend fun generate(prompt: String): String? =
        if (!borrowing()) client.generate(prompt) else peer.ask(KeyboardGemma.chat(prompt), RemoteModelClient.GENERATE_TIMEOUT_MS)

    /**
     * True when this phone's Gemma is LocalLauncher's and LocalType has none of its own. For Settings, so it only
     * peeks: LocalLauncher doesn't load 2 GB for the question (a LocalLauncher built before modelkit's peek still does).
     */
    override suspend fun sharedFromPeer(): Boolean = files.active() == null && run { askPeer(peek = true); usingPeer }

    /**
     * True when LocalType's own Gemma is the pinned download and LocalLauncher answers that it has one too, so the
     * phone keeps the same 2.4 GB twice. An imported model is a different file and doesn't count. For Settings, so
     * it only peeks, and it leaves [usingPeer] alone: LocalType keeps using its own copy.
     */
    override suspend fun peerHasCopyToo(): Boolean {
        if (files.active() != downloaded.model) return false
        return peerHasModel(peer.status(peek = true))
    }

    private fun borrowing() = usingPeer && files.active() == null

    private suspend fun SharedModelClient.ask(ask: KeyboardGemma.Ask, waitMs: Long) = generate(ask.prompt, ask.sampling, ask.maxTokens, waitMs)

    override fun setKeepLoaded(keep: Boolean) {
        client.keepLoaded = keep
    }

    override fun installedModel(): InstalledModel? {
        val active = files.active() ?: return null
        return if (active == modelFile) {
            val name = nameFile.takeIf { it.isFile }?.readText()?.trim().orEmpty().ifEmpty { active.name }
            InstalledModel(name, active.length())
        } else {
            InstalledModel(GemmaArtifact.MODEL.displayName, GemmaArtifact.MODEL.bytes)
        }
    }

    override fun install(
        context: Context,
        source: Uri,
        onProgress: (Int) -> Unit,
        onResult: (Result<InstalledModel>) -> Unit,
    ) {
        if (!importRunning.compareAndSet(false, true)) {
            // Off the caller's thread too, as the contract promises for every callback.
            Thread { onResult(Result.failure(IllegalStateException("A model file is already being imported."))) }.start()
            return
        }
        importPercent = 0
        Thread {
            val result = try {
                runCatching { copyIntoPlace(context, source, onProgress) }
            } finally {
                // Cleared before onResult, so the screen it refreshes no longer sees an import running.
                importPercent = null
                importRunning.set(false)
            }
            onResult(result)
        }.start()
    }

    /** Checks the picked file, copies it in and swaps it into place. Throws with a readable message. */
    private fun copyIntoPlace(context: Context, source: Uri, onProgress: (Int) -> Unit): InstalledModel {
        val (name, size) = queryNameAndSize(context, source)
        val memory = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        activityManager.getMemoryInfo(memory)
        val verdict = ModelImportRules.check(
            fileName = name,
            sizeBytes = size,
            freeBytes = modelDir.apply { mkdirs() }.usableSpace,
            totalRamBytes = memory.totalMem,
            lowRamDevice = activityManager.isLowRamDevice,
        )
        if (verdict is ModelImportRules.Verdict.Rejected) error(verdict.reason)

        val pending = File(modelDir, "$MODEL_FILE_NAME.pending")
        try {
            requireNotNull(context.contentResolver.openInputStream(source)) { "Couldn't read the selected file." }.use { input ->
                pending.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (size != null && size > 0) {
                            val percent = (copied * 100 / size).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                importPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            require(pending.length() >= ModelImportRules.MINIMUM_MODEL_BYTES) { "That file is too small to be a language model." }
            require(pending.renameTo(modelFile)) { "Couldn't move the model into place." }
            // Name the new file before the model process can load it; the name picks hardware-specific backends.
            nameFile.writeText(name.orEmpty())
            files.npuAttemptMarker(modelFile).delete()
            client.reload()
        } finally {
            pending.delete()
        }
        return InstalledModel(name.orEmpty().ifEmpty { MODEL_FILE_NAME }, modelFile.length())
    }

    override fun hasModelFiles(): Boolean =
        listOf(modelFile, nameFile, files.npuAttemptMarker(modelFile)).any { it.exists() } || downloaded.hasModelFiles()

    override fun remove() {
        client.reload()
        client.unbind()
        for (file in listOf(modelFile, nameFile, files.npuAttemptMarker(modelFile))) {
            Files.deleteIfExists(file.toPath())
            if (file.exists()) throw IOException("Some Gemma files could not be removed. Try again.")
        }
        downloaded.remove()
        ModelDownloads.writeProgress(appContext, ModelDownloadProgress(ModelDownloadProgress.State.IDLE))
    }

    private fun queryNameAndSize(context: Context, source: Uri): Pair<String?, Long?> =
        context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                name to size
            } ?: (null to null)

    override fun close() {
        client.unbind()
        peer.unbind()
    }

    private companion object {
        const val MODEL_FILE_NAME = GemmaModelFiles.IMPORTED_FILE_NAME
        const val LAUNCHER_PACKAGE = "dev.localtype.launcher"
    }
}
