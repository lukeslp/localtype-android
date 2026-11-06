package dev.localtype.keyboard

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Asks the download page for the newest version: when the person taps Check for updates, and when settings opens
 * with Check for updates automatically on (UpdateBanner). Never from the keyboard.
 */
object UpdateChecker {
    /** Blocking; call off the main thread. Null when the page can't be reached or read. */
    fun latest(): ReleaseInfo? = if (BuildConfig.DIRECT_UPDATES) fetchLatest() else null

    private fun fetchLatest(): ReleaseInfo? = runCatching {
        val connection = (URL(ReleaseInfo.LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            readResponse(connection.inputStream)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Closes the stream after at most the limit plus one byte to distinguish EOF from oversize. */
    internal fun readResponse(input: InputStream): ReleaseInfo? = input.use { stream ->
        val bytes = ByteArray(MAX_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val count = stream.read(bytes, size, bytes.size - size)
            if (count < 0) break
            if (count == 0) {
                val next = stream.read()
                if (next < 0) break
                bytes[size++] = next.toByte()
            } else {
                size += count
            }
        }
        if (size > MAX_BYTES) return@use null
        val body = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, size)).toString()
        } catch (_: CharacterCodingException) {
            return@use null
        }
        ReleaseInfo.parse(body)
    }

    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 16_384
}
