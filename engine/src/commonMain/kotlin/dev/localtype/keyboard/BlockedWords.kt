package dev.localtype.keyboard

/** Personal exclusions, separate from learned evidence. Accents remain significant; case and curly apostrophes do not. */
object BlockedWords {
    const val MAX_WORDS = 256
    const val MAX_WORD_UNITS = 80
    const val MAX_BYTES = 128 * 1024
    // Letters, with the marks and the non-joiner some languages write inside a word ([WordRule]).
    private val word = Regex("\\p{L}[\\p{L}\\p{Mn}\u200C]*(?:['-]\\p{L}[\\p{L}\\p{Mn}\u200C]*)*'?")

    fun normalize(raw: String): String? {
        if (raw.length > MAX_WORD_UNITS + 2) return null
        return AdaptiveNgramModel.normalize(raw.trim()).takeIf {
            it.length in 1..MAX_WORD_UNITS && it.encodeToByteArray().size <= 320 && word.matches(it)
        }
    }

    fun contains(words: Set<String>, value: String): Boolean = AdaptiveNgramModel.normalize(value) in words

    fun blocksText(words: Set<String>, text: String): Boolean =
        AdaptiveNgramModel.WORD_REGEX.findAll(text).any { contains(words, it.value) }

    /** Neither a blocked completion nor a blocked context is allowed to become new personal evidence. */
    fun mayLearn(words: Set<String>, context: List<String>, value: String): Boolean =
        !contains(words, value) && context.none { contains(words, it) }

    fun encode(words: Set<String>, language: LanguageProfile): ByteArray {
        require(words.size <= MAX_WORDS && words.all { normalize(it) == it })
        val body = "localtype-blocked-v1\n${language.code}\n${words.size}\n" + words.sorted().joinToString("\n", postfix = if (words.isEmpty()) "" else "\n")
        val bytes = body.encodeToByteArray()
        val checksum = Sha256.digest(bytes).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return (body + checksum + "\n").encodeToByteArray().also { require(it.size <= MAX_BYTES) }
    }

    /** Rejects the whole file on damage, duplicates, overflow, a different language or malformed UTF-8. */
    fun decode(bytes: ByteArray, language: LanguageProfile): Set<String>? {
        if (bytes.size > MAX_BYTES) return null
        val text = try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { return null }
        val lines = text.split('\n')
        if (lines.size < 5 || lines[0] != "localtype-blocked-v1" || lines[1] != language.code || lines.last() != "") return null
        val count = lines[2].toIntOrNull()?.takeIf { it in 0..MAX_WORDS && it.toString() == lines[2] } ?: return null
        if (lines.size != count + 5) return null
        val values = lines.subList(3, 3 + count)
        if (values.any { normalize(it) != it } || values.distinct().size != count || values != values.sorted()) return null
        val words = values.toSet()
        return words.takeIf { encode(it, language).contentEquals(bytes) }
    }
}

/** Worker-only file protocol. A caller publishes a new exclusion set only after read-back verification. */
class BlockedWordsFile(private val disk: Disk, private val language: LanguageProfile) {
    interface Disk {
        fun read(maxBytes: Int): ByteArray?
        fun write(bytes: ByteArray)
    }
    fun load(): Set<String> = disk.read(BlockedWords.MAX_BYTES)?.let {
        BlockedWords.decode(it, language) ?: error("Invalid blocked-word file")
    } ?: emptySet()

    fun save(words: Set<String>): Set<String> {
        val bytes = BlockedWords.encode(words, language)
        disk.write(bytes)
        return load().also { check(it == words) { "Blocked-word write did not verify" } }
    }
}
