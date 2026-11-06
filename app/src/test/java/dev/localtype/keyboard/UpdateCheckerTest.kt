package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class UpdateCheckerTest {
    @Test
    fun `reads the current feed without needing a content length`() {
        val stream = TrackingStream(FEED.toByteArray(Charsets.UTF_8), chunkSize = 3)
        assertEquals(0, stream.available())
        assertEquals(ReleaseInfo(17, "0.4.7"), UpdateChecker.readResponse(stream))
        assertTrue(stream.closed)
        assertEquals(stream.bytes.size, stream.consumed)
    }

    @Test
    fun `a feed of exactly sixteen kibibytes is accepted and closed`() {
        val bytes = FEED.toByteArray(Charsets.UTF_8)
        val stream = TrackingStream(bytes + ByteArray(LIMIT - bytes.size) { ' '.code.toByte() })
        assertEquals(ReleaseInfo(17, "0.4.7"), UpdateChecker.readResponse(stream))
        assertEquals(LIMIT, stream.consumed)
        assertTrue(stream.closed)
    }

    @Test
    fun `an oversized feed is rejected after one probe byte even with a valid prefix`() {
        val bytes = FEED.toByteArray(Charsets.UTF_8)
        for (size in listOf(LIMIT + 1, LIMIT * 4)) {
            val stream = TrackingStream(bytes + ByteArray(size - bytes.size) { ' '.code.toByte() })
            assertNull(UpdateChecker.readResponse(stream))
            assertEquals(LIMIT + 1, stream.consumed)
            assertTrue(stream.closed)
        }
    }

    @Test
    fun `chunked oversized data stops at the same byte bound`() {
        val stream = TrackingStream(ByteArray(LIMIT + 100) { ' '.code.toByte() }, chunkSize = 7)
        assertNull(UpdateChecker.readResponse(stream))
        assertEquals(LIMIT + 1, stream.consumed)
        assertTrue(stream.closed)
    }

    @Test
    fun `the limit counts utf8 bytes rather than characters`() {
        val json = """{"versionCode":17,"versionName":"0.4.7","note":"${"é".repeat(LIMIT / 2)}"}"""
        assertTrue(json.length < LIMIT)
        val stream = TrackingStream(json.toByteArray(Charsets.UTF_8), chunkSize = 5)
        assertNull(UpdateChecker.readResponse(stream))
        assertEquals(LIMIT + 1, stream.consumed)
        assertTrue(stream.closed)
    }

    @Test
    fun `valid multibyte text survives a split across reads`() {
        val json = """{"versionCode":17,"versionName":"0.4.7","note":"café ☕"}"""
        val stream = TrackingStream(json.toByteArray(Charsets.UTF_8), chunkSize = 1)
        assertEquals(ReleaseInfo(17, "0.4.7"), UpdateChecker.readResponse(stream))
        assertTrue(stream.closed)
    }

    @Test
    fun `malformed and truncated utf8 are rejected even after valid version fields`() {
        for (suffix in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xe2.toByte(), 0x82.toByte()))) {
            val stream = TrackingStream(FEED.toByteArray(Charsets.UTF_8) + suffix, chunkSize = 1)
            assertNull(UpdateChecker.readResponse(stream))
            assertTrue(stream.closed)
        }
    }

    @Test
    fun `empty and unparseable responses are closed and give no release`() {
        for (body in listOf("", "<html>not found</html>", """{"versionCode":17}""")) {
            val stream = TrackingStream(body.toByteArray(Charsets.UTF_8))
            assertNull(UpdateChecker.readResponse(stream))
            assertTrue(stream.closed)
        }
    }

    @Test
    fun `read failure closes the stream before the caller handles the error`() {
        val failure = IOException("interrupted response")
        val stream = object : InputStream() {
            var closed = false
            override fun read(): Int = throw failure
            override fun close() { closed = true }
        }
        assertEquals(failure, assertThrows(IOException::class.java) { UpdateChecker.readResponse(stream) })
        assertTrue(stream.closed)
    }

    @Test
    fun `a zero length read makes progress without spinning`() {
        val stream = object : TrackingStream(FEED.toByteArray(Charsets.UTF_8)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertEquals(ReleaseInfo(17, "0.4.7"), UpdateChecker.readResponse(stream))
        assertEquals(stream.bytes.size, stream.consumed)
        assertTrue(stream.closed)
    }

    /** Models chunked data with no usable length, and fails if the reader consumes past its bound. */
    private open class TrackingStream(val bytes: ByteArray, private val chunkSize: Int = Int.MAX_VALUE) : InputStream() {
        var consumed = 0
            private set
        var closed = false
            private set

        override fun available(): Int = 0

        override fun read(): Int {
            check(!closed)
            if (consumed == bytes.size) return -1
            check(consumed < LIMIT + 1) { "Read past the response bound" }
            return bytes[consumed++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            if (length == 0) return 0
            if (consumed == bytes.size) return -1
            val count = minOf(length, chunkSize, bytes.size - consumed)
            check(consumed + count <= LIMIT + 1) { "Read past the response bound" }
            bytes.copyInto(buffer, offset, consumed, consumed + count)
            consumed += count
            return count
        }

        override fun close() { closed = true }
    }

    companion object {
        private const val LIMIT = 16_384
        private val FEED = """
            {
              "versionCode": 17,
              "versionName": "0.4.7",
              "page": "https://dr.eamer.dev/downloads/apps/localtype/",
              "apks": {
                "full": {
                  "url": "https://dr.eamer.dev/downloads/android/localtype/localtype-0.4.7-17-7a8d6cdd.apk",
                  "sha256": "14623f8f9718f4d59c6c188fbe8d01dc4dfedba1e4697ee18c09fb8357de8c46",
                  "bytes": 55611247
                }
              }
            }
        """.trimIndent()
    }
}
