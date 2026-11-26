package dev.localtype.keyboard

import java.io.File
import java.util.UUID

/**
 * The folder that holds the one cleaned copy of a pasted image the receiving app may still be loading. Writing
 * a new copy deletes every earlier one first, and [expire] deletes any copy older than [MAX_AGE_MS], so at most
 * one image is kept, never past an hour. The folder is in the app's cache, which Android never backs up, and
 * only `FileProvider` reaches it (`res/xml/pasted_images.xml`). Pure file work, so the JVM tests cover it.
 */
class PastedImageCache(private val folder: File) {
    /** Writes [bytes] as a new copy under a name nothing has used, after deleting the earlier ones. */
    fun write(bytes: ByteArray, extension: String): File = synchronized(LOCK) {
        clearFiles()
        folder.mkdirs()
        val file = File(folder, "image-${UUID.randomUUID()}.$extension")
        val part = File(folder, file.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) {
            part.delete()
            throw java.io.IOException("could not keep the cleaned image")
        }
        file
    }

    /** Deletes copies whose last change is [MAX_AGE_MS] or more before [now] (milliseconds since the epoch). */
    fun expire(now: Long): Unit = synchronized(LOCK) {
        folder.listFiles()?.forEach { if (now - it.lastModified() >= MAX_AGE_MS || it.lastModified() > now) it.delete() }
    }

    /** How long from [now] until the oldest copy still kept reaches [MAX_AGE_MS], or null with nothing kept. */
    fun dueIn(now: Long): Long? = synchronized(LOCK) {
        folder.listFiles()?.minOfOrNull { it.lastModified() }?.let { (it + MAX_AGE_MS - now).coerceIn(0L, MAX_AGE_MS) }
    }

    fun clear(): Unit = synchronized(LOCK) { clearFiles() }

    private fun clearFiles() {
        folder.listFiles()?.forEach { it.delete() }
    }

    /** The copies now kept, for tests and dumpsys. */
    fun count(): Int = folder.listFiles()?.size ?: 0

    companion object {
        const val FOLDER = "pasted_images"
        const val MAX_AGE_MS = 60 * 60 * 1000L
        /** One lock for the folder whichever instance names it: each paste makes its own. */
        private val LOCK = Any()
    }
}
