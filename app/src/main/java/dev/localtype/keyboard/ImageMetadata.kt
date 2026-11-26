package dev.localtype.keyboard

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Takes the details a photo carries about where and how it was taken off a pasted image, without decoding it:
 * the picture's own bytes are copied exactly, so nothing is re-encoded, no quality is lost and an animation keeps
 * every frame. Each format keeps a short list of the parts a picture needs (an allowlist), so anything else a
 * camera, phone or editor wrote, named or not, goes with the rest:
 *
 * - JPEG keeps its tables, frames and scans, a JFIF header without the small copy of the picture it can carry
 *   (a thumbnail, which an editor may leave showing the photo as it was before a crop), an ICC color profile and
 *   an Adobe color marker. Exif
 *   and XMP (APP1), IPTC and Photoshop (APP13), comments, the other APP segments (MPF, C2PA, maker data) and
 *   anything after the end of the image (a gain map, a motion photo's video) go.
 * - PNG keeps its image and color chunks and APNG's animation; `eXIf`, `tEXt`, `zTXt`, `iTXt`, `tIME` and any
 *   other chunk go, as does anything after `IEND`.
 * - WebP keeps its image, alpha, color profile and animation chunks; `EXIF`, `XMP ` and others go, inside an
 *   animation's frames as well, and the RIFF size and the VP8X flags are set to match.
 * - GIF keeps its frames, timing and the loop (`NETSCAPE2.0` or `ANIMEXTS1.0`); comments, other application
 *   extensions (XMP among them) and anything after the trailer go.
 *
 * Where Exif turned the picture (Orientation 2 to 8), a new Exif block holding only that one tag takes its place,
 * so the image still shows the right way up. Any other format, or a file whose structure doesn't add up, is
 * refused: the caller pastes nothing rather than the original. So is a file with no picture in it, and one cut
 * into more parts than a picture has.
 */
object ImageMetadata {
    enum class Format(val mime: String, val extension: String) {
        JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp"), GIF("image/gif", "gif"),
    }

    sealed interface Result
    /** [bytes] is the cleaned image; [removed] counts the blocks of details taken off (0 when there were none). */
    class Cleaned(val bytes: ByteArray, val format: Format, val removed: Int) : Result
    /** Not one of the four formats, or not a well-formed one. */
    data class Refused(val reason: Reason) : Result
    enum class Reason { UNSUPPORTED, MALFORMED }

    private class Malformed(message: String) : Exception(message)

    /** More parts than any real picture has; see [jpeg]. */
    private const val MAX_PARTS = 65_536

    /** The format by the file's first bytes; the type a clip claims is not trusted. */
    fun format(bytes: ByteArray): Format? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> Format.JPEG
        bytes.size >= 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } -> Format.PNG
        bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP" -> Format.WEBP
        bytes.size >= 6 && (ascii(bytes, 0, 6) == "GIF87a" || ascii(bytes, 0, 6) == "GIF89a") -> Format.GIF
        else -> null
    }

    fun strip(bytes: ByteArray): Result {
        val format = format(bytes) ?: return Refused(Reason.UNSUPPORTED)
        return try {
            when (format) {
                Format.JPEG -> jpeg(bytes)
                Format.PNG -> png(bytes)
                Format.WEBP -> webp(bytes)
                Format.GIF -> gif(bytes)
            }
        } catch (_: Malformed) {
            Refused(Reason.MALFORMED)
        } catch (_: IndexOutOfBoundsException) {
            Refused(Reason.MALFORMED)
        }
    }

    // JPEG

    private fun jpeg(b: ByteArray): Cleaned {
        val kept = ArrayList<IntRange>()
        // A kept segment written in another form than it came in, by where it starts: a JFIF header less its thumbnail.
        val rewritten = HashMap<Int, ByteArray>()
        var removed = 0
        var orientation: Int? = null
        var jfifEnd = -1
        var pos = 2
        var end = -1
        var scans = 0
        while (end < 0) {
            // A picture has a few dozen parts. A file made of millions of tiny ones is not a picture, and listing them
            // would take the memory the keyboard runs in.
            if (kept.size > MAX_PARTS) throw Malformed("too many segments")
            if (pos >= b.size || b[pos] != 0xFF.toByte()) throw Malformed("no marker at $pos")
            // Fill bytes: any number of 0xFF before a marker.
            while (pos < b.size && b[pos] == 0xFF.toByte()) pos++
            if (pos >= b.size) throw Malformed("ends in fill")
            val marker = u8(b, pos)
            val start = pos - 1
            pos++
            when (marker) {
                0xD9 -> { kept += start until pos; end = pos }
                0x01, in 0xD0..0xD7 -> kept += start until pos
                0xD8, 0x00 -> throw Malformed("marker $marker out of place")
                in 0x02..0xBF -> throw Malformed("reserved marker $marker")
                else -> {
                    val length = u16be(b, pos)
                    if (length < 2 || pos + length > b.size) throw Malformed("segment length")
                    val segmentEnd = pos + length
                    val payload = pos + 2
                    val keep = when (marker) {
                        0xE0 -> startsWith(b, payload, segmentEnd, "JFIF\u0000")
                        0xE2 -> startsWith(b, payload, segmentEnd, "ICC_PROFILE\u0000")
                        0xEE -> startsWith(b, payload, segmentEnd, "Adobe")
                        in 0xE1..0xEF, 0xFE -> false
                        else -> true
                    }
                    val exif = marker == 0xE1 && startsWith(b, payload, segmentEnd, "Exif\u0000\u0000")
                    if (exif && orientation == null) orientation = exifOrientation(b, payload + 6, segmentEnd)
                    if (keep) {
                        kept += start until segmentEnd
                        if (marker == 0xE0) jfifWithoutThumbnail(b, payload, segmentEnd)?.let { rewritten[start] = it; removed++ }
                        if (marker == 0xE0 && kept.size == 1) jfifEnd = segmentEnd
                    } else if (!(exif && isOrientationOnly(b, payload + 6, segmentEnd))) {
                        removed++
                    }
                    pos = segmentEnd
                    // Entropy-coded data follows the scan header up to the next marker that isn't a stuffed zero or a restart.
                    if (marker == 0xDA) {
                        scans++
                        var scan = pos
                        while (true) {
                            if (scan + 1 >= b.size) throw Malformed("scan runs past the end")
                            if (b[scan] == 0xFF.toByte()) {
                                val next = u8(b, scan + 1)
                                if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) break
                            }
                            scan++
                        }
                        kept += pos until scan
                        pos = scan
                    }
                }
            }
        }
        if (scans == 0) throw Malformed("no picture data")
        if (end < b.size) removed++
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        val orientationSegment = orientation?.let { jpegOrientation(it) }
        if (orientationSegment != null && jfifEnd < 0) out.write(orientationSegment)
        for (range in kept) {
            rewritten[range.first]?.let { out.write(it) } ?: out.write(b, range.first, range.last - range.first + 1)
            if (orientationSegment != null && range.last + 1 == jfifEnd) out.write(orientationSegment)
        }
        return Cleaned(out.toByteArray(), Format.JPEG, removed)
    }

    /**
     * A JFIF header ends in the width and height of a thumbnail and then the thumbnail itself, uncompressed. When
     * there is one, or anything else past the header's 14 bytes, the header comes back with a thumbnail of 0 by 0
     * and nothing after it; a header that has none already is left as it is (null).
     */
    private fun jfifWithoutThumbnail(b: ByteArray, payload: Int, end: Int): ByteArray? {
        val size = end - payload
        if (size < JFIF_HEADER) return null
        if (size == JFIF_HEADER && b[payload + 12] == 0.toByte() && b[payload + 13] == 0.toByte()) return null
        val length = JFIF_HEADER + 2
        return byteArrayOf(0xFF.toByte(), 0xE0.toByte(), (length shr 8).toByte(), length.toByte()) +
            b.copyOfRange(payload, payload + 12) + byteArrayOf(0, 0)
    }

    /** "JFIF" and its zero, the version, the density unit and two densities, then the thumbnail's width and height. */
    private const val JFIF_HEADER = 14

    private fun jpegOrientation(value: Int): ByteArray {
        val tiff = orientationTiff(value)
        val header = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        val length = 2 + header.size + tiff.size
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + header + tiff
    }

    // PNG

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** The chunks a PNG keeps: the image, transparency, color, physical size, background and APNG's animation. */
    private val PNG_KEEPS = setOf(
        "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "cICP", "mDCV", "mDCv", "cLLI", "cLLi",
        "bKGD", "hIST", "pHYs", "sPLT", "acTL", "fcTL", "fdAT",
    )

    private fun png(b: ByteArray): Cleaned {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var pos = 8
        var removed = 0
        var orientation: Int? = null
        var orientationWritten = false
        var first = true
        var pictureData = false
        while (true) {
            if (pos + 12 > b.size) throw Malformed("chunk header past the end")
            val length = u32be(b, pos)
            if (length < 0 || length > b.size - pos - 12) throw Malformed("chunk length")
            val type = ascii(b, pos + 4, 4)
            if (!type.all { it in 'A'..'Z' || it in 'a'..'z' }) throw Malformed("chunk type")
            val dataStart = pos + 8
            val chunkEnd = dataStart + length + 4
            val crc = CRC32().apply { update(b, pos + 4, 4 + length) }.value
            if (crc != (u32be(b, dataStart + length).toLong() and 0xFFFFFFFFL)) throw Malformed("chunk CRC")
            if (first && type != "IHDR") throw Malformed("IHDR first")
            first = false
            if (type == "eXIf" && orientation == null) orientation = exifOrientation(b, dataStart, dataStart + length)
            if (type in PNG_KEEPS) {
                if (type == "IDAT") pictureData = true
                if (type == "IDAT" && !orientationWritten) {
                    orientationWritten = true
                    orientation?.let { out.write(pngChunk("eXIf", orientationTiff(it))) }
                }
                out.write(b, pos, chunkEnd - pos)
            } else if (type[0].isUpperCase()) {
                throw Malformed("unknown critical chunk $type")
            } else if (!(type == "eXIf" && isOrientationOnly(b, dataStart, dataStart + length))) {
                removed++
            }
            pos = chunkEnd
            if (type == "IEND") break
        }
        if (!pictureData) throw Malformed("no picture data")
        if (pos < b.size) removed++
        return Cleaned(out.toByteArray(), Format.PNG, removed)
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value
        return be32(data.size) + typeBytes + data + be32(crc.toInt())
    }

    // WebP

    /** The chunks a WebP keeps: the image itself, alpha, its color profile and animation. */
    private val WEBP_KEEPS = setOf("VP8X", "VP8 ", "VP8L", "ALPH", "ICCP", "ANIM", "ANMF")
    /** Inside an animation frame, after its 16 bytes of position, size and timing: the frame's picture and its alpha. */
    private val WEBP_FRAME_KEEPS = setOf("VP8 ", "VP8L", "ALPH")
    private const val WEBP_FRAME_HEADER = 16
    private const val VP8X_EXIF = 0x08
    private const val VP8X_XMP = 0x04

    private fun webp(b: ByteArray): Cleaned {
        val riffSize = u32le(b, 4)
        if (riffSize < 4 || riffSize > b.size - 8) throw Malformed("RIFF size")
        val end = 8 + riffSize
        var pos = 12
        var removed = 0
        var orientation: Int? = null
        val chunks = ByteArrayOutputStream(b.size)
        var vp8x = -1
        var first = true
        var pictureData = false
        while (pos < end) {
            if (pos + 8 > end) throw Malformed("chunk header past the end")
            val type = ascii(b, pos, 4)
            val size = u32le(b, pos + 4)
            if (size < 0 || size > end - pos - 8) throw Malformed("chunk size")
            val padded = size + (size and 1)
            val chunkEnd = minOf(pos + 8 + padded, end)
            if (first && type !in setOf("VP8 ", "VP8L", "VP8X")) throw Malformed("first chunk $type")
            if (first && type == "VP8X" && size < 10) throw Malformed("VP8X size")
            first = false
            var orientationOnly = false
            if (type == "EXIF") {
                val data = pos + 8
                val tiff = if (startsWith(b, data, data + size, "Exif\u0000\u0000")) data + 6 else data
                if (orientation == null) orientation = exifOrientation(b, tiff, data + size)
                orientationOnly = isOrientationOnly(b, tiff, data + size)
            }
            if (type == "ANMF") {
                // A frame holds chunks of its own: only its picture and alpha are kept, like the file's.
                val frame = webpFrame(b, pos + 8, pos + 8 + size)
                removed += frame.second
                pictureData = true
                chunks.write("ANMF".toByteArray(Charsets.ISO_8859_1))
                chunks.write(le32(frame.first.size))
                chunks.write(frame.first)
            } else if (type in WEBP_KEEPS) {
                if (type == "VP8X") vp8x = chunks.size() + 8
                if (type == "VP8 " || type == "VP8L") pictureData = true
                chunks.write(b, pos, chunkEnd - pos)
                // A writer that left off the last pad byte at the very end: put it back so the chunk stays even.
                if (chunkEnd - pos < 8 + padded) chunks.write(0)
            } else if (!orientationOnly) {
                removed++
            }
            pos += 8 + padded
        }
        if (!pictureData) throw Malformed("no picture data")
        if (end < b.size) removed++
        val orientationChunk = if (vp8x >= 0) orientation?.let { orientationTiff(it) } else null
        if (orientationChunk != null) {
            chunks.write("EXIF".toByteArray(Charsets.ISO_8859_1))
            chunks.write(le32(orientationChunk.size))
            chunks.write(orientationChunk)
        }
        val body = chunks.toByteArray()
        if (vp8x >= 0) {
            var flags = body[vp8x].toInt() and 0xFF
            flags = flags and VP8X_XMP.inv() and VP8X_EXIF.inv()
            if (orientationChunk != null) flags = flags or VP8X_EXIF
            body[vp8x] = flags.toByte()
        }
        val out = ByteArrayOutputStream(body.size + 12)
        out.write("RIFF".toByteArray(Charsets.ISO_8859_1))
        out.write(le32(body.size + 4))
        out.write("WEBP".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return Cleaned(out.toByteArray(), Format.WEBP, removed)
    }

    /**
     * An animation frame's data in [start, end) with only its picture and alpha chunks, and how many others went.
     * The result is always an even number of bytes, as a chunk's data is padded to.
     */
    private fun webpFrame(b: ByteArray, start: Int, end: Int): Pair<ByteArray, Int> {
        if (end - start < WEBP_FRAME_HEADER) throw Malformed("frame header")
        val out = ByteArrayOutputStream(end - start)
        out.write(b, start, WEBP_FRAME_HEADER)
        var pos = start + WEBP_FRAME_HEADER
        var removed = 0
        var picture = false
        while (pos < end) {
            if (pos + 8 > end) throw Malformed("frame chunk header past the end")
            val type = ascii(b, pos, 4)
            val size = u32le(b, pos + 4)
            if (size < 0 || size > end - pos - 8) throw Malformed("frame chunk size")
            val padded = size + (size and 1)
            if (type in WEBP_FRAME_KEEPS) {
                if (type != "ALPH") picture = true
                out.write(b, pos, 8 + size)
                if (size and 1 == 1) out.write(0)
            } else {
                removed++
            }
            pos += 8 + padded
        }
        if (!picture) throw Malformed("frame without a picture")
        return out.toByteArray() to removed
    }

    // GIF

    private fun gif(b: ByteArray): Cleaned {
        if (b.size < 13) throw Malformed("short header")
        val out = ByteArrayOutputStream(b.size)
        val packed = u8(b, 10)
        var pos = 13 + if (packed and 0x80 != 0) 3 * (1 shl ((packed and 7) + 1)) else 0
        if (pos > b.size) throw Malformed("global color table")
        out.write(b, 0, pos)
        var removed = 0
        var frames = 0
        while (true) {
            if (pos >= b.size) throw Malformed("no trailer")
            when (u8(b, pos)) {
                0x3B -> { out.write(0x3B); pos++; break }
                0x2C -> {
                    if (pos + 10 > b.size) throw Malformed("image descriptor")
                    val local = u8(b, pos + 9)
                    var p = pos + 10 + if (local and 0x80 != 0) 3 * (1 shl ((local and 7) + 1)) else 0
                    p += 1 // LZW minimum code size
                    p = subBlocksEnd(b, p)
                    out.write(b, pos, p - pos)
                    pos = p
                    frames++
                }
                0x21 -> {
                    if (pos + 2 > b.size) throw Malformed("extension")
                    val label = u8(b, pos + 1)
                    val p = subBlocksEnd(b, pos + 2)
                    val keep = when (label) {
                        0xF9, 0x01 -> true // graphic control (timing, transparency), plain text drawn as part of the picture
                        0xFF -> pos + 3 + 11 <= b.size && u8(b, pos + 2) == 11 &&
                            ascii(b, pos + 3, 11).let { it == "NETSCAPE2.0" || it == "ANIMEXTS1.0" }
                        else -> false // comments, XMP and every other application's data
                    }
                    if (keep) out.write(b, pos, p - pos) else removed++
                    pos = p
                }
                else -> throw Malformed("block ${u8(b, pos)}")
            }
        }
        if (frames == 0) throw Malformed("no picture data")
        if (pos < b.size) removed++
        return Cleaned(out.toByteArray(), Format.GIF, removed)
    }

    /** The position just past a run of GIF sub-blocks and its zero-length terminator. */
    private fun subBlocksEnd(b: ByteArray, start: Int): Int {
        var p = start
        while (true) {
            if (p >= b.size) throw Malformed("sub-blocks past the end")
            val size = u8(b, p)
            p += 1 + size
            if (size == 0) return p
        }
    }

    // Exif orientation

    /** Exif's Orientation (tag 0x0112) from TIFF data in [start, end), when it turns or flips the picture (2 to 8). */
    internal fun exifOrientation(b: ByteArray, start: Int, end: Int): Int? {
        if (start < 0 || end > b.size || end - start < 8) return null
        val little = when (ascii(b, start, 2)) { "II" -> true; "MM" -> false; else -> return null }
        fun u16(at: Int): Int? = if (at < start || at + 2 > end) null
            else if (little) u8(b, at) or (u8(b, at + 1) shl 8) else (u8(b, at) shl 8) or u8(b, at + 1)
        fun u32(at: Int): Long? = if (at < start || at + 4 > end) null else {
            val v = if (little) (u8(b, at).toLong()) or (u8(b, at + 1).toLong() shl 8) or (u8(b, at + 2).toLong() shl 16) or (u8(b, at + 3).toLong() shl 24)
                else (u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or (u8(b, at + 2).toLong() shl 8) or u8(b, at + 3).toLong()
            v
        }
        if (u16(start + 2) != 42) return null
        val ifd = u32(start + 4) ?: return null
        if (ifd > end - start) return null
        val ifdAt = start + ifd.toInt()
        val count = u16(ifdAt) ?: return null
        for (i in 0 until count) {
            val entry = ifdAt + 2 + 12 * i
            if (entry + 12 > end) return null
            if (u16(entry) != 0x0112) continue
            if (u16(entry + 2) != 3 || u32(entry + 4) != 1L) return null
            return u16(entry + 8)?.takeIf { it in 2..8 }
        }
        return null
    }

    /** Whether TIFF data in [start, end) is already the block [orientationTiff] writes, so cleaning again removes nothing. */
    private fun isOrientationOnly(b: ByteArray, start: Int, end: Int): Boolean {
        val value = exifOrientation(b, start, end) ?: return false
        val minimal = orientationTiff(value)
        return end - start == minimal.size && minimal.indices.all { b[start + it] == minimal[it] }
    }

    /** A TIFF block (big-endian) holding one entry, Orientation = [value], and no further IFD: 26 bytes. */
    internal fun orientationTiff(value: Int): ByteArray = byteArrayOf(
        'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
        0, 1,
        0x01, 0x12, 0, 3, 0, 0, 0, 1, (value shr 8).toByte(), value.toByte(), 0, 0,
        0, 0, 0, 0,
    )

    // Byte helpers; every read past the end throws, which strip() turns into a refusal.

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
    private fun u16be(b: ByteArray, at: Int): Int = (u8(b, at) shl 8) or u8(b, at + 1)
    private fun u32be(b: ByteArray, at: Int): Int = (u8(b, at) shl 24) or (u8(b, at + 1) shl 16) or (u8(b, at + 2) shl 8) or u8(b, at + 3)
    private fun u32le(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16) or (u8(b, at + 3) shl 24)
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun ascii(b: ByteArray, at: Int, length: Int): String {
        if (at < 0 || at + length > b.size) throw IndexOutOfBoundsException()
        return String(b, at, length, Charsets.ISO_8859_1)
    }
    private fun startsWith(b: ByteArray, at: Int, end: Int, prefix: String): Boolean =
        end - at >= prefix.length && ascii(b, at, prefix.length) == prefix
}
