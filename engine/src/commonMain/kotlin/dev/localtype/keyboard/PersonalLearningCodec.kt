package dev.localtype.keyboard

/** Whole-snapshot format. Damage rejects the entire history, never a subset of its evidence. */
object PersonalLearningCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 8192
    const val MAX_WORD_BYTES = 512
    const val MAX_WORD_UNITS = 128
    private const val MAGIC = 0x4c54504c // LTPL
    private const val VERSION = 1

    fun encode(entries: List<PersonalLearningState.Entry>, oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords): ByteArray {
        validate(entries, oneLetterWords)
        val out = ByteWriter()
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(entries.size)
        for (entry in entries) {
            out.writeInt(entry.context.size)
            for (word in entry.context) writeWord(out, word)
            writeWord(out, entry.word)
            out.writeInt(entry.observations)
            out.writeInt(entry.explicitObservations)
            out.writeLong(entry.lastObserved)
        }
        val payload = out.toByteArray()
        require(payload.size <= MAX_BYTES - 4)
        out.writeInt(crc32(payload).toInt())
        return out.toByteArray()
    }

    /** Throws IllegalArgumentException for every malformed input, including invalid UTF-8. */
    /** [oneLetterWords] are the language's ([LanguageProfile.oneLetterWords]), so a Spanish snapshot holding "y" is valid. */
    fun decode(bytes: ByteArray, oneLetterWords: Set<String> = LanguageProfile.ENGLISH.oneLetterWords): List<PersonalLearningState.Entry> {
        require(bytes.size in 16..MAX_BYTES)
        val payloadSize = bytes.size - 4
        val expected = ByteReader(bytes, payloadSize, 4).getInt().toLong() and 0xffffffffL
        require(crc32(bytes, 0, payloadSize) == expected)
        try {
            val input = ByteReader(bytes, 0, payloadSize)
            require(input.getInt() == MAGIC)
            require(input.getInt() == VERSION)
            val count = input.getInt()
            require(count in 0..MAX_ENTRIES)
            val entries = ArrayList<PersonalLearningState.Entry>(count)
            repeat(count) {
                val contextSize = input.getInt()
                require(contextSize in 1..3)
                val context = List(contextSize) { readWord(input) }
                entries += PersonalLearningState.Entry(context, readWord(input), input.getInt(),
                    input.getInt(), input.getLong())
            }
            require(input.remaining() == 0)
            validate(entries, oneLetterWords)
            return entries
        } catch (_: ByteReader.Underflow) {
            throw IllegalArgumentException("Invalid personal learning snapshot")
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Invalid personal learning snapshot")
        }
    }

    fun validWordSize(word: String): Boolean =
        word.length in 1..MAX_WORD_UNITS && word.encodeToByteArray().size <= MAX_WORD_BYTES

    private fun validate(entries: List<PersonalLearningState.Entry>, oneLetterWords: Set<String>) {
        require(entries.size <= MAX_ENTRIES)
        for (entry in entries) {
            require(entry.context.size in 1..3)
            require(validWordSize(entry.word) && entry.context.all(::validWordSize))
            require(entry.observations in 1..100)
            require(entry.explicitObservations in 0..entry.observations)
            require(entry.lastObserved >= 0)
        }
        val restored = PersonalLearningState(oneLetterWords = oneLetterWords).apply { restore(entries) }.entries()
        // Reject normalization, duplicates, discarded words and retention overflow alike.
        require(restored.size == entries.size && restored.toSet() == entries.toSet())
    }

    private fun writeWord(out: ByteWriter, word: String) {
        val bytes = word.encodeToByteArray()
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readWord(input: ByteReader): String {
        val size = input.getInt()
        require(size in 1..MAX_WORD_BYTES && size <= input.remaining())
        val bytes = ByteArray(size)
        input.get(bytes)
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }
}
