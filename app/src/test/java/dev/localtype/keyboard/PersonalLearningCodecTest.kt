package dev.localtype.keyboard

import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class PersonalLearningCodecTest {
    private fun entry(context: List<String> = listOf("at"), word: String = "home",
        count: Int = 2, explicit: Int = 1, time: Long = 7) =
        PersonalLearningState.Entry(context, word, count, explicit, time)

    private fun reject(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningCodec.decode(bytes) }
    }

    private fun edited(bytes: ByteArray, edit: (ByteBuffer) -> Unit): ByteArray = bytes.copyOf().also {
        edit(ByteBuffer.wrap(it))
        ByteBuffer.wrap(it).putInt(it.size - 4, CRC32().apply { update(it, 0, it.size - 4) }.value.toInt())
    }

    @Test fun `round trip empty Unicode and all context lengths preserves provenance and counts`() {
        assertEquals(emptyList<PersonalLearningState.Entry>(), PersonalLearningCodec.decode(PersonalLearningCodec.encode(emptyList())))
        val state = PersonalLearningState()
        state.record(listOf("i", "don't", "know"), "mañana", LearningOrigin.EXPLICIT_KEEP)
        state.record(listOf("i", "don't", "know"), "mañana", LearningOrigin.TYPED)
        state.record(listOf("at"), "office", LearningOrigin.VERIFIED_REPAIR)
        val entries = state.entries()
        val decoded = PersonalLearningCodec.decode(PersonalLearningCodec.encode(entries))
        assertEquals(entries, decoded)
        val restored = PersonalLearningState().apply { restore(decoded) }
        assertEquals(state.followers(listOf("i", "don't", "know")), restored.followers(listOf("i", "don't", "know")))
        assertEquals(0, decoded.single { it.word == "office" }.explicitObservations)
        (decoded.first().context as MutableList<String>)[0] = "changed"
        assertEquals(entries, state.entries())
    }

    @Test fun `every truncation and every single byte corruption rejects whole history`() {
        val bytes = PersonalLearningCodec.encode(listOf(entry(), entry(listOf("see", "you", "at"), "office")))
        for (length in 0 until bytes.size) reject(bytes.copyOf(length))
        for (index in bytes.indices) reject(bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() })
        reject(bytes + byteArrayOf(0))
        reject(ByteArray(PersonalLearningCodec.MAX_BYTES + 1))
    }

    @Test fun `valid checksum cannot hide bad header lengths counts or trailing data`() {
        val bytes = PersonalLearningCodec.encode(listOf(entry()))
        // Layout: header 12, context count 4, length + 'at' 6, length + 'home' 8,
        // counts 8, timestamp 8, checksum 4.
        for ((offset, values) in listOf(
            0 to listOf(0), 4 to listOf(0, 2, -1),
            8 to listOf(-1, 8193, Int.MAX_VALUE, 0, 2),
            12 to listOf(-1, 0, 4, Int.MAX_VALUE),
            16 to listOf(-1, 0, 513, Int.MAX_VALUE),
            22 to listOf(-1, 0, 513, Int.MAX_VALUE),
            30 to listOf(-1, 0, 101, Int.MAX_VALUE),
            34 to listOf(-1, 3, Int.MAX_VALUE),
        )) for (value in values) reject(edited(bytes) { it.putInt(offset, value) })
        reject(edited(bytes) { it.putLong(38, -1) })
        val trailing = bytes.copyOf(bytes.size + 1)
        reject(edited(trailing) { })
    }

    @Test fun `invalid UTF8 and invalid state words reject even with correct CRC`() {
        val bytes = PersonalLearningCodec.encode(listOf(entry()))
        reject(edited(bytes) { it.put(26, 0xc0.toByte()); it.put(27, 0xaf.toByte()) })
        reject(edited(bytes) { it.put(26, ' '.code.toByte()) })
        reject(edited(bytes) { it.put(26, 'H'.code.toByte()) })
        reject(edited(bytes) { it.put(18, '!'.code.toByte()) })
    }

    @Test fun `encoder rejects all state repairs instead of silently normalizing clamping or dropping`() {
        val invalid = listOf(
            entry(emptyList()), entry(listOf("a", "b", "c", "d")), entry(listOf("bad!")),
            entry(word = "HOME"), entry(word = "two words"), entry(word = "x"),
            entry(word = "\ud800"), entry(count = 0), entry(count = 101),
            entry(explicit = -1), entry(explicit = 3), entry(time = -1),
            entry(word = "a".repeat(129)), entry(listOf("a".repeat(129))),
            entry(word = "界".repeat(171)),
        )
        for (bad in invalid) assertThrows(IllegalArgumentException::class.java) {
            PersonalLearningCodec.encode(listOf(entry(), bad))
        }
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningCodec.encode(listOf(entry(), entry())) }
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningCodec.encode(List(8193) { entry() }) }
        val tooManyFollowers = (0..8).map { entry(word = "word" + ('a' + it)) }
        assertThrows(IllegalArgumentException::class.java) { PersonalLearningCodec.encode(tooManyFollowers) }
    }

    @Test fun `decoder applies word unit bound and duplicate validation to checksummed snapshots`() {
        val valid = PersonalLearningCodec.encode(listOf(entry(word = "a".repeat(128))))
        assertEquals(128, PersonalLearningCodec.decode(valid).single().word.length)
        // Insert one extra valid letter and repair length/checksum: bytes bound alone is insufficient.
        val tooLong = valid.copyOfRange(0, 26) + byteArrayOf('a'.code.toByte()) + valid.copyOfRange(26, valid.size)
        reject(edited(tooLong) { it.putInt(22, 129) })
        val one = PersonalLearningCodec.encode(listOf(entry()))
        val duplicate = one.copyOfRange(0, 12) + one.copyOfRange(12, one.size - 4) + one.copyOfRange(12, one.size)
        reject(edited(duplicate) { it.putInt(8, 2) })
    }

    @Test fun `maximum retained snapshot is accepted and restores without losses`() {
        fun letters(number: Int): String = "ctx" + ('a' + number / 676) + ('a' + number / 26 % 26) + ('a' + number % 26)
        val entries = (0 until 1024).flatMap { row ->
            (0 until 8).map { follower -> entry(listOf(letters(row)), "word" + ('a' + follower), 100, 100, row.toLong()) }
        }
        val decoded = PersonalLearningCodec.decode(PersonalLearningCodec.encode(entries))
        assertEquals(entries, decoded)
        assertEquals(entries.toSet(), PersonalLearningState().apply { restore(decoded) }.entries().toSet())
    }
}
