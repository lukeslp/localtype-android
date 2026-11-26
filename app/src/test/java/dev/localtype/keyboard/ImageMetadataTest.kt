package dev.localtype.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.CRC32

/**
 * The pasted-image cleaner on invented fixtures (`src/test/fixtures/images`, written by `make_fixtures.py` there):
 * every detail goes, the picture's bytes stay exactly as they were, and a file that doesn't add up is refused.
 * Cleaned copies are written to `build/reports/image-metadata/` so a decoder outside the JVM can open them too.
 */
class ImageMetadataTest {
    private fun fixture(name: String) = File("src/test/fixtures/images/$name").readBytes()

    private fun clean(bytes: ByteArray, keep: String? = null): ImageMetadata.Cleaned {
        val result = ImageMetadata.strip(bytes)
        assertTrue("refused: $result", result is ImageMetadata.Cleaned)
        val cleaned = result as ImageMetadata.Cleaned
        if (keep != null) File("build/reports/image-metadata").apply { mkdirs() }.resolve(keep).writeBytes(cleaned.bytes)
        return cleaned
    }

    private fun ByteArray.has(text: String) = indexOf(text.toByteArray(Charsets.ISO_8859_1)) >= 0
    private fun ByteArray.indexOf(needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Words only the invented metadata carries: a camera, a place, comments, the XMP and Photoshop names. */
    private val secrets = listOf("FixtureCam", "Invented", "London", "Photoshop", "8BIM", "xmpmeta", "adobe", "GPS", "TRAILER", "trailing", "after the")

    private fun assertNoSecrets(bytes: ByteArray) = secrets.forEach { assertFalse("still has $it", bytes.has(it)) }

    // The JDK's own decoders. Unit tests compile against android.jar, which leaves out javax.imageio and java.awt,
    // so they are reached by name; the test JVM has them.
    private val imageIO = Class.forName("javax.imageio.ImageIO")
    private val bufferedImage = Class.forName("java.awt.image.BufferedImage")
    private val imageReader = Class.forName("javax.imageio.ImageReader")

    private fun pixels(bytes: ByteArray): IntArray {
        val image = imageIO.getMethod("read", java.io.InputStream::class.java).invoke(null, ByteArrayInputStream(bytes)) ?: error("not decodable")
        val width = bufferedImage.getMethod("getWidth").invoke(image) as Int
        val height = bufferedImage.getMethod("getHeight").invoke(image) as Int
        val rgb = bufferedImage.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
        return rgb.invoke(image, 0, 0, width, height, null, 0, width) as IntArray
    }

    private fun gifFrames(bytes: ByteArray): Int {
        val reader = (imageIO.getMethod("getImageReadersByFormatName", String::class.java).invoke(null, "gif") as Iterator<*>).next()
        val stream = imageIO.getMethod("createImageInputStream", Any::class.java).invoke(null, ByteArrayInputStream(bytes))
        imageReader.getMethod("setInput", Any::class.java).invoke(reader, stream)
        return imageReader.getMethod("getNumImages", Boolean::class.java).invoke(reader, true) as Int
    }

    // JPEG

    /** Segments as (marker, start, end) up to the first scan. */
    private fun jpegSegments(b: ByteArray): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        var p = 2
        while (p < b.size) {
            val marker = b[p + 1].toInt() and 0xFF
            val length = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            out += Triple(marker, p, p + 2 + length)
            if (marker == 0xDA) break
            p += 2 + length
        }
        return out
    }

    @Test
    fun `a JPEG loses its location, camera, XMP, IPTC, comment and trailer and keeps its picture and orientation`() {
        val original = fixture("gps_orientation.jpg")
        assertTrue(original.has("GPS") || original.has("FixtureCam"))
        val cleaned = clean(original, keep = "gps_orientation.jpg")
        assertEquals(ImageMetadata.Format.JPEG, cleaned.format)
        assertEquals("Exif, XMP, IPTC, comment, trailer", 5, cleaned.removed)
        assertNoSecrets(cleaned.bytes)
        // One APP1 is left: the 34-byte Exif block that holds only Orientation 6.
        val app1 = jpegSegments(cleaned.bytes).filter { it.first == 0xE1 }
        assertEquals(1, app1.size)
        assertEquals(34 + 2, app1.single().third - app1.single().second)
        assertEquals(6, ImageMetadata.exifOrientation(cleaned.bytes, app1.single().second + 10, app1.single().third))
        assertTrue(jpegSegments(cleaned.bytes).none { it.first == 0xED || it.first == 0xFE })
        // Everything from the first quantization table to the end of the image is the same bytes.
        val dqt = byteArrayOf(0xFF.toByte(), 0xDB.toByte())
        val from = original.indexOf(dqt)
        val to = original.indexOf(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), from) + 2
        val picture = original.copyOfRange(from, to)
        val cleanedFrom = cleaned.bytes.indexOf(dqt)
        assertArrayEquals(picture, cleaned.bytes.copyOfRange(cleanedFrom, cleaned.bytes.size))
        // It still opens, with the same pixels; the JFIF header is still first.
        assertArrayEquals(pixels(original), pixels(cleaned.bytes))
        assertEquals(0xE0, jpegSegments(cleaned.bytes).first().first)
    }

    private fun segment(marker: Int, payload: String) = segment(marker, payload.toByteArray(Charsets.ISO_8859_1))
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    /** The fixture's picture (tables, frame, scan, end) with chosen segments put in front of it. */
    private fun jpegWith(vararg segments: ByteArray): ByteArray {
        val original = fixture("gps_orientation.jpg")
        val from = original.indexOf(byteArrayOf(0xFF.toByte(), 0xDB.toByte()))
        val to = original.indexOf(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), from) + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segments.fold(ByteArray(0)) { a, s -> a + s } + original.copyOfRange(from, to)
    }

    @Test
    fun `a JPEG keeps its color profile and Adobe marker and drops MPF, JFXX, C2PA and maker segments`() {
        val icc = segment(0xE2, "ICC_PROFILE\u0000\u0001\u0001colorprofile")
        val adobe = segment(0xEE, "Adobe\u0000d\u0000\u0000\u0000\u0000\u0001")
        val source = jpegWith(
            segment(0xE0, "JFIF\u0000\u0001\u0002\u0000\u0000\u0001\u0000\u0001\u0000\u0000"),
            segment(0xE0, "JFXX\u0000\u0010thumbnail of the uncropped photo"),
            icc,
            segment(0xE2, "MPF\u0000II*\u0000second image offsets"),
            segment(0xEB, "JP\u0000\u0000c2pa content credentials"),
            segment(0xE3, "Meta maker data"),
            segment(0xEC, "Ducky\u0000copyright"),
            adobe,
        )
        val cleaned = clean(source)
        assertEquals(5, cleaned.removed)
        val markers = jpegSegments(cleaned.bytes).map { it.first }
        assertEquals(listOf(0xE0, 0xE2, 0xEE, 0xDB), markers.take(4))
        assertTrue(cleaned.bytes.indexOf(icc) > 0)
        assertTrue(cleaned.bytes.indexOf(adobe) > 0)
        listOf("JFXX", "MPF", "c2pa", "Meta", "Ducky").forEach { assertFalse(it, cleaned.bytes.has(it)) }
    }

    @Test
    fun `a JFIF header loses the thumbnail it carries and keeps its densities`() {
        // Version 1.2, dots per inch, 72 by 72, then a 2 by 1 thumbnail: six bytes of the picture as it was.
        val header = "JFIF\u0000\u0001\u0002\u0001\u0000H\u0000H"
        val withThumbnail = jpegWith(segment(0xE0, header + "\u0002\u0001" + "UNCROP"))
        val cleaned = clean(withThumbnail)
        assertEquals(1, cleaned.removed)
        assertFalse(cleaned.bytes.has("UNCROP"))
        val expected = jpegWith(segment(0xE0, header + "\u0000\u0000"))
        assertArrayEquals(expected, cleaned.bytes)
        // The same pixels, and cleaning the result again changes nothing.
        assertArrayEquals(pixels(expected), pixels(cleaned.bytes))
        val twice = clean(cleaned.bytes)
        assertEquals(0, twice.removed)
        assertArrayEquals(cleaned.bytes, twice.bytes)
        // Bytes hidden after a header that claims no thumbnail go as well.
        val padded = clean(jpegWith(segment(0xE0, header + "\u0000\u0000" + "HIDDEN")))
        assertEquals(1, padded.removed)
        assertArrayEquals(expected, padded.bytes)
        // With an orientation to keep, the new Exif block still follows the header.
        val turned = clean(jpegWith(
            segment(0xE0, header + "\u0001\u0001" + "RGB"),
            segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + ImageMetadata.orientationTiff(6)),
        ))
        assertEquals(listOf(0xE0, 0xE1, 0xDB), jpegSegments(turned.bytes).map { it.first }.take(3))
        assertFalse(turned.bytes.has("RGB"))
    }

    @Test
    fun `a JPEG with nothing to remove comes back byte for byte, and cleaning twice changes nothing`() {
        val bare = jpegWith()
        val cleaned = clean(bare)
        assertEquals(0, cleaned.removed)
        assertArrayEquals(bare, cleaned.bytes)
        val once = clean(fixture("gps_orientation.jpg"))
        val twice = clean(once.bytes)
        assertEquals(0, twice.removed)
        assertArrayEquals(once.bytes, twice.bytes)
    }

    @Test
    fun `an upright photo gets no new Exif block`() {
        val exif = ImageMetadata.orientationTiff(1)
        val cleaned = clean(jpegWith(segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + exif)))
        assertEquals(1, cleaned.removed)
        assertTrue(jpegSegments(cleaned.bytes).none { it.first == 0xE1 })
    }

    @Test
    fun `a broken JPEG is refused`() {
        val good = fixture("gps_orientation.jpg")
        val eoi = good.indexOf(byteArrayOf(0xFF.toByte(), 0xD9.toByte()), good.indexOf(byteArrayOf(0xFF.toByte(), 0xDA.toByte())))
        val malformed = ImageMetadata.Refused(ImageMetadata.Reason.MALFORMED)
        // Cut inside the scan, so no end of image.
        assertEquals(malformed, ImageMetadata.strip(good.copyOf(eoi - 10)))
        // A segment longer than the file.
        assertEquals(malformed, ImageMetadata.strip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x40, 0x00, 0x45)))
        // Garbage where a marker should be.
        assertEquals(malformed, ImageMetadata.strip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x00, 0x00, 0x12, 0x34)))
        // A second start of image, and a reserved marker.
        assertEquals(malformed, ImageMetadata.strip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD8.toByte())))
        assertEquals(malformed, ImageMetadata.strip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x10, 0x00, 0x02)))
        // Only the start.
        assertEquals(malformed, ImageMetadata.strip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }

    // PNG

    private data class Chunk(val type: String, val start: Int, val end: Int)

    private fun pngChunks(b: ByteArray): List<Chunk> {
        val out = ArrayList<Chunk>()
        var p = 8
        while (p + 8 <= b.size) {
            val length = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            out += Chunk(type, p, p + 12 + length)
            p += 12 + length
            if (type == "IEND") break
        }
        return out
    }

    @Test
    fun `a PNG loses its text, time and Exif chunks and keeps its image chunks byte for byte`() {
        val original = fixture("text_chunks.png")
        val types = pngChunks(original).map { it.type }
        assertTrue(types.containsAll(listOf("tEXt", "zTXt", "iTXt", "tIME", "eXIf")))
        val cleaned = clean(original, keep = "text_chunks.png")
        assertEquals(ImageMetadata.Format.PNG, cleaned.format)
        assertEquals("five chunks and the trailer", 6, cleaned.removed)
        assertNoSecrets(cleaned.bytes)
        val chunks = pngChunks(cleaned.bytes)
        assertTrue(chunks.none { it.type in setOf("tEXt", "zTXt", "iTXt", "tIME") })
        assertEquals("IEND", chunks.last().type)
        assertEquals(cleaned.bytes.size, chunks.last().end)
        // The Exif chunk that comes back holds Orientation 3 alone, before the image data, with a right CRC.
        val exif = chunks.single { it.type == "eXIf" }
        assertEquals(3, ImageMetadata.exifOrientation(cleaned.bytes, exif.start + 8, exif.end - 4))
        assertEquals(26 + 12, exif.end - exif.start)
        assertTrue(chunks.indexOf(exif) < chunks.indexOfFirst { it.type == "IDAT" })
        chunks.forEach { c ->
            val crc = CRC32().apply { update(cleaned.bytes, c.start + 4, c.end - c.start - 8) }.value
            val stored = (((cleaned.bytes[c.end - 4].toLong() and 0xFF) shl 24) or ((cleaned.bytes[c.end - 3].toLong() and 0xFF) shl 16) or
                ((cleaned.bytes[c.end - 2].toLong() and 0xFF) shl 8) or (cleaned.bytes[c.end - 1].toLong() and 0xFF))
            assertEquals(c.type, crc, stored)
        }
        // Every kept chunk is the original's bytes.
        val originalKept = pngChunks(original).filter { it.type !in setOf("tEXt", "zTXt", "iTXt", "tIME", "eXIf") }
        val cleanedKept = chunks.filter { it.type != "eXIf" }
        assertEquals(originalKept.map { it.type }, cleanedKept.map { it.type })
        originalKept.zip(cleanedKept).forEach { (o, c) ->
            assertArrayEquals(o.type, original.copyOfRange(o.start, o.end), cleaned.bytes.copyOfRange(c.start, c.end))
        }
        assertArrayEquals(pixels(original), pixels(cleaned.bytes))
    }

    @Test
    fun `a broken PNG is refused`() {
        val good = fixture("text_chunks.png")
        val malformed = ImageMetadata.Refused(ImageMetadata.Reason.MALFORMED)
        val badCrc = good.copyOf().also { it[pngChunks(good).first { c -> c.type == "IDAT" }.start + 10] = (it[pngChunks(good).first { c -> c.type == "IDAT" }.start + 10] + 1).toByte() }
        assertEquals(malformed, ImageMetadata.strip(badCrc))
        val iend = pngChunks(good).last()
        assertEquals("no IEND", malformed, ImageMetadata.strip(good.copyOf(iend.start)))
        assertEquals("cut in a chunk", malformed, ImageMetadata.strip(good.copyOf(60)))
        // IHDR must come first.
        val chunks = pngChunks(good)
        val noIhdr = good.copyOfRange(0, 8) + good.copyOfRange(chunks[1].start, good.size)
        assertEquals(malformed, ImageMetadata.strip(noIhdr))
    }

    // GIF

    @Test
    fun `an animated GIF loses its comment, XMP and trailer and keeps every frame and the loop`() {
        val original = fixture("animated_comment.gif")
        val cleaned = clean(original, keep = "animated_comment.gif")
        assertEquals(ImageMetadata.Format.GIF, cleaned.format)
        assertEquals("comment, XMP, trailer", 3, cleaned.removed)
        assertNoSecrets(cleaned.bytes)
        assertFalse(cleaned.bytes.has("XMP DataXMP"))
        assertTrue(cleaned.bytes.has("NETSCAPE2.0"))
        assertEquals(0x3B, cleaned.bytes.last().toInt())
        // The cleaned file is the original with the two inserted extensions and the trailer cut out, nothing else.
        val comment = original.indexOf(byteArrayOf(0x21, 0xFE.toByte()))
        val xmpStart = original.indexOf("XMP DataXMP".toByteArray(Charsets.ISO_8859_1)) - 3
        var xmpEnd = xmpStart + 3 + 11
        while (original[xmpEnd].toInt() != 0) xmpEnd += 1 + (original[xmpEnd].toInt() and 0xFF)
        xmpEnd++
        assertEquals(comment + 2 + 1 + 27 + 1, xmpStart)
        val trailer = original.lastIndexOf(0x3B) // the invented trailing text has no 0x3B
        val expected = original.copyOfRange(0, comment) + original.copyOfRange(xmpEnd, trailer + 1)
        assertArrayEquals(expected, cleaned.bytes)
        assertEquals(3, gifFrames(cleaned.bytes))
    }

    @Test
    fun `a broken GIF is refused`() {
        val good = fixture("animated_comment.gif")
        val malformed = ImageMetadata.Refused(ImageMetadata.Reason.MALFORMED)
        assertEquals("no trailer", malformed, ImageMetadata.strip(good.copyOf(good.lastIndexOf(0x3B))))
        assertEquals("cut in a frame", malformed, ImageMetadata.strip(good.copyOf(good.size - 40)))
        assertEquals("a block that isn't one", malformed, ImageMetadata.strip(good.copyOf(13 + 6) + byteArrayOf(0x55, 0x3B)))
        assertEquals("short header", malformed, ImageMetadata.strip("GIF89a".toByteArray(Charsets.ISO_8859_1)))
    }

    // WebP

    private fun webpChunks(b: ByteArray): List<Chunk> {
        val out = ArrayList<Chunk>()
        var p = 12
        while (p + 8 <= b.size) {
            val size = (b[p + 4].toInt() and 0xFF) or ((b[p + 5].toInt() and 0xFF) shl 8) or ((b[p + 6].toInt() and 0xFF) shl 16) or ((b[p + 7].toInt() and 0xFF) shl 24)
            out += Chunk(String(b, p, 4, Charsets.ISO_8859_1), p, p + 8 + size + (size and 1))
            p += 8 + size + (size and 1)
        }
        return out
    }

    private fun riffSize(b: ByteArray) = (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8) or ((b[6].toInt() and 0xFF) shl 16) or ((b[7].toInt() and 0xFF) shl 24)

    @Test
    fun `a WebP loses its EXIF and XMP, fixes its size and flags and keeps its image chunk`() {
        val original = fixture("exif_xmp.webp")
        assertEquals(listOf("VP8X", "VP8 ", "EXIF", "XMP "), webpChunks(original).map { it.type })
        val cleaned = clean(original, keep = "exif_xmp.webp")
        assertEquals(ImageMetadata.Format.WEBP, cleaned.format)
        assertEquals(2, cleaned.removed)
        assertNoSecrets(cleaned.bytes)
        val chunks = webpChunks(cleaned.bytes)
        assertEquals(listOf("VP8X", "VP8 ", "EXIF"), chunks.map { it.type })
        assertEquals(cleaned.bytes.size - 8, riffSize(cleaned.bytes))
        val flags = cleaned.bytes[chunks[0].start + 8].toInt()
        assertEquals("EXIF flag, for the orientation", 0x08, flags and 0x08)
        assertEquals("no XMP flag", 0, flags and 0x04)
        val exif = chunks[2]
        assertArrayEquals(ImageMetadata.orientationTiff(6), cleaned.bytes.copyOfRange(exif.start + 8, exif.end))
        val vp8 = webpChunks(original)[1]
        assertArrayEquals(original.copyOfRange(vp8.start, vp8.end), cleaned.bytes.copyOfRange(chunks[1].start, chunks[1].end))
        // The canvas size and the other flags are the original's.
        assertArrayEquals(original.copyOfRange(chunks[0].start + 9, chunks[0].end), cleaned.bytes.copyOfRange(chunks[0].start + 9, chunks[0].end))
    }

    @Test
    fun `an animated WebP keeps its animation and every frame`() {
        val original = fixture("animated_exif.webp")
        val cleaned = clean(original, keep = "animated_exif.webp")
        assertNoSecrets(cleaned.bytes)
        val before = webpChunks(original)
        val after = webpChunks(cleaned.bytes)
        assertTrue("EXIF" in before.map { it.type })
        val frames = before.filter { it.type == "ANMF" || it.type == "ANIM" }
        assertEquals(3, frames.count { it.type == "ANMF" })
        assertEquals(frames.map { it.type }, after.filter { it.type == "ANMF" || it.type == "ANIM" }.map { it.type })
        frames.zip(after.filter { it.type == "ANMF" || it.type == "ANIM" }).forEach { (o, c) ->
            assertArrayEquals(original.copyOfRange(o.start, o.end), cleaned.bytes.copyOfRange(c.start, c.end))
        }
        assertEquals("animation flag kept", 0x02, cleaned.bytes[after[0].start + 8].toInt() and 0x02)
        assertEquals(cleaned.bytes.size - 8, riffSize(cleaned.bytes))
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun webpChunk(type: String, data: ByteArray) =
        type.toByteArray(Charsets.ISO_8859_1) + le32(data.size) + data + if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0)
    private fun webpOf(vararg chunks: ByteArray): ByteArray {
        val body = "WEBP".toByteArray(Charsets.ISO_8859_1) + chunks.fold(ByteArray(0)) { a, c -> a + c }
        return "RIFF".toByteArray(Charsets.ISO_8859_1) + le32(body.size) + body
    }

    @Test
    fun `details inside an animated WebP's frames go too`() {
        val header = ByteArray(16) { it.toByte() }
        val picture = webpChunk("VP8L", "frame picture".toByteArray())
        val alpha = webpChunk("ALPH", "alpha".toByteArray())
        val hidden = webpChunk("EXIF", "GPS 34.42N inside a frame".toByteArray()) + webpChunk("XMP ", "<x:xmpmeta>".toByteArray())
        val vp8x = webpChunk("VP8X", byteArrayOf(0x0E, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        val anim = webpChunk("ANIM", ByteArray(6))
        val source = webpOf(vp8x, anim, webpChunk("ANMF", header + alpha + hidden + picture), webpChunk("ANMF", header + picture))
        val cleaned = clean(source)
        assertEquals(2, cleaned.removed)
        assertFalse(cleaned.bytes.has("GPS"))
        assertFalse(cleaned.bytes.has("xmpmeta"))
        val expected = webpOf(
            webpChunk("VP8X", byteArrayOf(0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0)), anim,
            webpChunk("ANMF", header + alpha + picture), webpChunk("ANMF", header + picture),
        )
        assertArrayEquals(expected, cleaned.bytes)
        // Cleaning again changes nothing, and a frame with no picture in it is no animation.
        assertEquals(0, clean(cleaned.bytes).removed)
        assertEquals(refused, ImageMetadata.strip(webpOf(vp8x, anim, webpChunk("ANMF", header + alpha))))
        assertEquals(refused, ImageMetadata.strip(webpOf(vp8x, anim, webpChunk("ANMF", ByteArray(8)))))
    }

    private val refused = ImageMetadata.Refused(ImageMetadata.Reason.MALFORMED)

    @Test
    fun `a file with no picture in it is refused`() {
        // A JPEG with tables and no scan, a PNG with a header and an end, a WebP with only a profile, a GIF with only a comment.
        val original = fixture("gps_orientation.jpg")
        val tables = original.indexOf(byteArrayOf(0xFF.toByte(), 0xDB.toByte()))
        val scan = original.indexOf(byteArrayOf(0xFF.toByte(), 0xDA.toByte()), tables)
        val noScan = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + original.copyOfRange(tables, scan) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertEquals(refused, ImageMetadata.strip(noScan))
        val png = fixture("text_chunks.png")
        val chunks = pngChunks(png)
        val headerOnly = png.copyOfRange(0, 8) + chunks.filter { it.type == "IHDR" || it.type == "IEND" }
            .fold(ByteArray(0)) { a, c -> a + png.copyOfRange(c.start, c.end) }
        assertEquals(refused, ImageMetadata.strip(headerOnly))
        assertEquals(refused, ImageMetadata.strip(webpOf(webpChunk("VP8X", ByteArray(10)), webpChunk("ICCP", "profile".toByteArray()))))
        val gif = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0) + byteArrayOf(0x21, 0xFE.toByte(), 2, 'h'.code.toByte(), 'i'.code.toByte(), 0, 0x3B)
        assertEquals(refused, ImageMetadata.strip(gif))
    }

    @Test
    fun `a JPEG cut into more parts than a picture has is refused before it is listed`() {
        // Seventy thousand restart markers: two bytes each, where a real picture has a few dozen segments.
        val many = ByteArray(2 + 70_000 * 2)
        many[0] = 0xFF.toByte(); many[1] = 0xD8.toByte()
        for (i in 0 until 70_000) { many[2 + 2 * i] = 0xFF.toByte(); many[3 + 2 * i] = 0xD0.toByte() }
        assertEquals(refused, ImageMetadata.strip(many))
    }

    @Test
    fun `a broken WebP is refused`() {
        val good = fixture("exif_xmp.webp")
        val malformed = ImageMetadata.Refused(ImageMetadata.Reason.MALFORMED)
        assertEquals("RIFF size past the end", malformed, ImageMetadata.strip(good.copyOf(good.size - 20)))
        val chunkTooLong = good.copyOf().also { it[16] = 0x7F; it[17] = 0x7F }
        assertEquals(malformed, ImageMetadata.strip(chunkTooLong))
        val notAnImageFirst = good.copyOf().also { "JUNK".toByteArray().copyInto(it, 12) }
        assertEquals(malformed, ImageMetadata.strip(notAnImageFirst))
    }

    // Others

    @Test
    fun `other formats are refused, never passed through`() {
        val unsupported = ImageMetadata.Refused(ImageMetadata.Reason.UNSUPPORTED)
        assertEquals(unsupported, ImageMetadata.strip(ByteArray(0)))
        assertEquals("BMP", unsupported, ImageMetadata.strip("BM\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("HEIC", unsupported, ImageMetadata.strip("\u0000\u0000\u0000\u0018ftypheic\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("TIFF", unsupported, ImageMetadata.strip("II*\u0000\u0008\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `the format is read from the bytes, not from the clip's type`() {
        assertEquals(ImageMetadata.Format.JPEG, ImageMetadata.format(fixture("gps_orientation.jpg")))
        assertEquals(ImageMetadata.Format.PNG, ImageMetadata.format(fixture("text_chunks.png")))
        assertEquals(ImageMetadata.Format.GIF, ImageMetadata.format(fixture("animated_comment.gif")))
        assertEquals(ImageMetadata.Format.WEBP, ImageMetadata.format(fixture("exif_xmp.webp")))
    }

    @Test
    fun `orientation is read from either byte order and only when it turns the picture`() {
        val big = ImageMetadata.orientationTiff(8)
        assertEquals(8, ImageMetadata.exifOrientation(big, 0, big.size))
        val little = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 5, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(5, ImageMetadata.exifOrientation(little, 0, little.size))
        assertEquals(null, ImageMetadata.exifOrientation(ImageMetadata.orientationTiff(1), 0, 26))
        assertEquals(null, ImageMetadata.exifOrientation(ImageMetadata.orientationTiff(9), 0, 26))
        assertEquals("IFD past the end", null, ImageMetadata.exifOrientation(big.copyOf(12), 0, 12))
    }
}
