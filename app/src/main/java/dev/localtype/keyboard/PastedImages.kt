package dev.localtype.keyboard

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Reads a copied image once, when the paste chip is tapped, and writes the cleaned copy ([ImageMetadata]) that is
 * pasted in its place. Runs off the main thread. The bytes live only in this call; what stays is the one file in
 * [PastedImageCache], shared through this app's own `FileProvider` (not exported; the editor gets a read grant
 * through commitContent).
 */
object PastedImages {
    /** A copy larger than this is not read: the strip says it couldn't be cleaned. */
    const val MAX_BYTES = 20 * 1024 * 1024

    sealed interface Outcome
    class Ready(val uri: Uri, val file: File, val mime: String, val removed: Int) : Outcome
    /** Not a JPEG, PNG, WebP or GIF, malformed, or too large: nothing is pasted while cleaning is on. */
    data object NotCleaned : Outcome
    /** The copy could not be read (the app that shared it took it back) or the cleaned copy not written. */
    data object Failed : Outcome

    fun authority(context: Context) = "${context.packageName}.pastedimages"

    fun cache(context: Context) = PastedImageCache(File(context.cacheDir, PastedImageCache.FOLDER))

    /**
     * [wanted] is asked just before the cleaned copy is written: a paste given up meanwhile (another image tapped,
     * the field left) writes nothing, so it cannot delete the copy a later paste's app is still loading.
     */
    fun clean(context: Context, source: Uri, wanted: () -> Boolean = { true }): Outcome {
        val bytes = read(context, source) ?: return Failed
        if (bytes.size > MAX_BYTES) return NotCleaned
        val cleaned = ImageMetadata.strip(bytes) as? ImageMetadata.Cleaned ?: return NotCleaned
        if (!wanted()) return Failed
        return runCatching {
            val file = cache(context).write(cleaned.bytes, cleaned.format.extension)
            Ready(FileProvider.getUriForFile(context, authority(context), file), file, cleaned.format.mime, cleaned.removed)
        }.getOrElse { Failed }
    }

    /** At most [MAX_BYTES] + 1 bytes, so a larger copy is known to be too large without reading it all. */
    private fun read(context: Context, source: Uri): ByteArray? = runCatching {
        val input = context.contentResolver.openInputStream(source) ?: return null
        input.use {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() <= MAX_BYTES) {
                val n = it.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()
}
