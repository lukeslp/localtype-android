package dev.localtype.keyboard

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads the text of a word list a person picked in settings: a text file, or the first .txt inside a zip, which is
 * how Gboard exports its personal dictionary. Bounded by [WordListImport.MAX_BYTES] before and after unzipping;
 * nothing is written anywhere. [WordListImport.parse] takes the words out of the text.
 */
object WordListFile {
    sealed interface Read {
        data class Text(val text: String) : Read
        data object TooLarge : Read
        data object Unreadable : Read
    }

    fun read(stream: InputStream): Read = try {
        val bytes = bounded(stream) ?: return Read.TooLarge
        if (isZip(bytes)) unzip(bytes) else text(bytes)
    } catch (_: java.io.IOException) {
        Read.Unreadable
    } catch (_: IllegalArgumentException) {
        // A malformed zip entry name.
        Read.Unreadable
    }

    /** Up to the limit, or null when the stream holds more. */
    private fun bounded(stream: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > WordListImport.MAX_BYTES) return null
        }
    }

    private fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    private fun unzip(bytes: ByteArray): Read {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            repeat(MAX_ENTRIES) {
                val entry = zip.nextEntry ?: return Read.Unreadable
                if (!entry.isDirectory && entry.name.endsWith(".txt", ignoreCase = true)) {
                    return text(bounded(zip) ?: return Read.TooLarge)
                }
            }
        }
        return Read.Unreadable
    }

    /** UTF-8 only; a file with a NUL in it is not text. */
    private fun text(bytes: ByteArray): Read =
        if (bytes.any { it == 0.toByte() }) Read.Unreadable else Read.Text(String(bytes, Charsets.UTF_8))

    private const val MAX_ENTRIES = 16
}
