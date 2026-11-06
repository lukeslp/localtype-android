package dev.localtype.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * The shared engine runs plain-Kotlin stand-ins for java.* on iOS (engine/src/commonMain). On the JVM it
 * still uses java.*, so each stand-in is checked here against the call it replaces.
 */
class EnginePlatformParityTest {
    private val random = Random(20261018)

    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    @Test
    fun `sha-256 matches MessageDigest`() {
        for (n in listOf(0, 1, 55, 56, 63, 64, 65, 119, 120, 1000, 65_537)) {
            val data = bytes(n)
            assertArrayEquals("length $n", MessageDigest.getInstance("SHA-256").digest(data), Sha256.digest(data))
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Sha256.digest(ByteArray(0))))
        assertEquals(hex(sha256("también".encodeToByteArray())), hex(Sha256.digest("también".encodeToByteArray())))
    }

    @Test
    fun `crc-32 matches java util zip`() {
        for (n in listOf(0, 1, 7, 64, 1000, 100_000)) {
            val data = bytes(n)
            val offset = if (n > 10) 3 else 0
            val length = n - offset
            val expected = CRC32().apply { update(data, offset, length) }.value
            assertEquals("length $n", expected, Crc32.of(data, offset, length))
            assertEquals(expected, crc32(data, offset, length))
        }
    }

    @Test
    fun `the byte reader reads as ByteBuffer does`() {
        val data = bytes(64)
        for (little in listOf(false, true)) {
            val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            val java = ByteBuffer.wrap(data).order(order)
            val ours = ByteReader(data, littleEndian = little)
            assertEquals(java.get(), ours.get())
            assertEquals(java.getShort(), ours.getShort())
            assertEquals(java.getInt(), ours.getInt())
            assertEquals(java.getLong(), ours.getLong())
            assertEquals(java.getFloat().toBits(), ours.getFloat().toBits())
            assertEquals(java.getInt(40), ours.getInt(40))
            assertEquals(java.remaining(), ours.remaining())
        }
        val short = ByteReader(data, 60, 4)
        assertEquals(ByteBuffer.wrap(data, 60, 4).int, short.getInt())
        assertThrows(ByteReader.Underflow::class.java) { short.get() }
        val writer = ByteWriter(4)
        writer.writeByte(7); writer.writeShort(-2); writer.writeInt(0x01020304); writer.writeLong(-5L); writer.write(byteArrayOf(9, 8))
        val expected = ByteBuffer.allocate(17).put(7).putShort(-2).putInt(0x01020304).putLong(-5L).put(byteArrayOf(9, 8)).array()
        assertArrayEquals(expected, writer.toByteArray())
    }

    @Test
    fun `code points match Character`() {
        val samples = "aZé9 ñ.,!?¿¡́‍😀🇪🇸𝔸\uD800x"
        var i = 0
        while (i < samples.length) {
            assertEquals("at $i", Character.codePointAt(samples, i), codePointAt(samples, i))
            assertEquals("before ${i + 1}", Character.codePointBefore(samples, i + 1), codePointBefore(samples, i + 1))
            val cp = Character.codePointAt(samples, i)
            assertEquals(Character.charCount(cp), charCount(cp))
            assertEquals(String(Character.toChars(cp)), codePointString(cp))
            assertEquals(Character.isLetter(cp), isLetterCodePoint(cp))
            assertEquals(Character.isDigit(cp), isDigitCodePoint(cp))
            i += Character.charCount(cp)
        }
        // On the JVM the shared numbering is Character's; Kotlin's own categories (iOS, below the astral planes) agree.
        for (c in listOf('a', 'Z', 'é', '9', ' ', '.', '¿', '-', '(', ')', '«', '»', '_', '́', '€', '©')) {
            assertEquals("$c", Character.getType(c).toInt(), c.category.value)
        }
    }

    @Test
    fun `epoch days match LocalDate`() {
        for (date in listOf("1970-01-01", "2000-02-29", "2026-10-18", "1969-12-31", "2100-03-01", "0001-01-01")) {
            assertEquals(date, LocalDate.parse(date).toEpochDay(), CurrentVocabulary.epochDay(date))
        }
        for (bad in listOf("2026-02-30", "2025-02-29", "2026-13-01", "26-10-18", "2026-1-1")) {
            assertThrows(bad, IllegalArgumentException::class.java) { CurrentVocabulary.epochDay(bad) }
        }
    }

    @Test
    fun `key positions from a language's rows match every drawn letters layout`() {
        for (language in LanguageProfile.ALL) for (numberRow in listOf(false, true)) for (switchKey in listOf(false, true)) {
            val drawn = LetterGeometry.from(KeyboardLayout.build(KeyboardLayout.Mode.LETTERS, switchKey, numberRow, language = language))
            val name = "$language numberRow=$numberRow switch=$switchKey"
            if (language.words) assertEquals(name, language.geometry, drawn)
            // IPA has no words, so nothing corrects with its geometry. Read off the drawing, its stress key (ˈ, a
            // modifier letter) counts as a letter too, so compare the letters and the symbol row above them.
            else for (c in language.letters.toList() + language.topRow.toList()) {
                assertEquals("$name $c", drawn.touchOn(c, 0f, 0f), language.geometry.touchOn(c, 0f, 0f))
            }
        }
        assertEquals(LetterGeometry.QWERTY, LetterGeometry.fromRows(LanguageProfile.ENGLISH.keyRows))
    }

    @Test
    fun `an lru cache evicts as an access-ordered LinkedHashMap does`() {
        val java = object : LinkedHashMap<Int, String?>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String?>?) = size > 3
        }
        val ours = LruCache<Int, String?>(3)
        val ops = List(400) { random.nextInt(3) to random.nextInt(6) }
        for ((op, key) in ops) {
            when (op) {
                0 -> { java[key] = "v$key"; ours[key] = "v$key" }
                1 -> assertEquals(java[key], ours[key])
                else -> assertEquals(java.containsKey(key), ours.containsKey(key))
            }
            assertEquals(java.size, ours.size)
            for (k in 0 until 6) assertEquals(java.containsKey(k), ours.containsKey(k))
        }
    }
}
