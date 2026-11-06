package dev.localtype.keyboard


/** Reviewed spellings and editorial prediction scores, independent of correction frequency. */
class CurrentVocabulary private constructor(
    private val entries: Map<String, Entry>,
    val sha256: String?,
    private val observedDay: Long,
    val newsBoost: Int,
) {
    private data class Entry(val word: String, val display: String, val score: Int, val news: Boolean, val previous: Set<String>)
    val words: Set<String> get() = entries.keys

    /** Immutable phase pinned at field start; no clock reads or I/O while predicting. */
    fun at(epochDay: Long, cap: Int = NEWS_CAP): CurrentVocabulary {
        require(cap in setOf(0, 2, 4, 8))
        val age = epochDay - observedDay
        return withBoost(when { age < 0 || age >= 30 -> 0; age >= 14 -> cap / 2; else -> cap })
    }

    /** Replay restores the effective bonus, never a typing timestamp. */
    fun withBoost(boost: Int): CurrentVocabulary {
        require(boost in setOf(0, 1, 2, 4, 8))
        return CurrentVocabulary(entries, sha256, observedDay, boost)
    }

    operator fun contains(word: String): Boolean = normalize(word) in entries

    fun preferred(word: String): String {
        val key = normalize(word)
        entries[key]?.let { return it.display }
        val suffix = when { key.endsWith("'s") -> "'s"; key.endsWith("s'") -> "'"; else -> return word }
        return entries[key.dropLast(suffix.length)]?.display?.plus(suffix) ?: word
    }

    fun display(word: String, prefix: String, sentenceStart: Boolean, capsLock: Boolean = false, protected: Boolean = false): String =
        TextRules.applyCase(if (protected) normalize(word) else preferred(word), prefix, sentenceStart, capsLock)

    /** Only this explicitly selected acronym is automatically capitalized, through normal Undo. */
    fun capitalization(typed: String): String? =
        if (typed in setOf("ai", "Ai") && "ai" in entries) entries.getValue("ai").display.takeIf { it != typed } else null

    /** Prefix candidates and factual name pairs; never unsolicited empty-field news starters. */
    fun candidates(prefix: String, previous: String?): List<Pair<String, Int>> {
        val key = normalize(prefix)
        val before = previous?.let(::normalize)
        return entries.values.asSequence().filter {
            (key.length >= 3 && it.word.startsWith(key)) || (before != null && before in it.previous && it.word.startsWith(key))
        }.map { it.word to (it.score + (if (it.news) newsBoost else 0) + (if (before in it.previous) 64 else 0)) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).toList()
    }

    fun predictionLift(word: String, dictionaryScore: Int, previous: String?): Int {
        val entry = entries[word] ?: return 0
        return (entry.score - dictionaryScore).coerceAtLeast(0) + (if (entry.news) newsBoost else 0) +
            (if (previous?.let(::normalize) in entry.previous) 64 else 0)
    }

    fun header(): Map<String, String> = mapOf(
        "vocabulary" to (sha256 ?: "none"), "vocabularyPolicy" to POLICY, "newsBoost" to newsBoost.toString(),
    )

    companion object {
        const val ASSET = "current_words.txt"
        const val POLICY = "current-vocabulary-v1"
        const val NEWS_CAP = 4
        private const val MAX_ENTRIES = 256
        val EMPTY = CurrentVocabulary(emptyMap(), null, 0, 0)
        private fun normalize(word: String) = word.replace('’', '\'').lowercase()

        /** Days since 1970-01-01 of a strict ISO date ("2026-10-18"), as LocalDate.parse(date).toEpochDay(). */
        fun epochDay(date: String): Long {
            val match = requireNotNull(Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(date)) { "not an ISO date: $date" }
            val (y, m, d) = match.destructured.toList().map { it.toLong() }
            val leap = y % 4 == 0L && (y % 100 != 0L || y % 400 == 0L)
            val days = longArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            require(m in 1..12 && d in 1..days[(m - 1).toInt()]) { "not a date: $date" }
            // Days from civil, proleptic Gregorian (Hinnant), as java.time counts them.
            val yy = if (m <= 2) y - 1 else y
            val era = (if (yy >= 0) yy else yy - 399) / 400
            val yoe = yy - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }

        /** Malformed/duplicate/excluded spellings reject the whole supplement, never partially load. */
        fun parse(text: String): CurrentVocabulary {
            require(text.length <= 64 * 1024)
            val lines = text.lineSequence().toList()
            val observed = lines.single { it.startsWith("# observed=") }.substringAfter('=')
            val day = epochDay(observed)
            val entries = LinkedHashMap<String, Entry>()
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val parts = line.split('\t')
                require(parts.size == 5)
                val (word, display, scoreText, kind, previousText) = parts
                require(word.length in 2..80 && AdaptiveNgramModel.WORD_REGEX.matches(word) && normalize(word) == word)
                require(normalize(display) == word && !SuggestionWordRules.isExcluded(word))
                val score = scoreText.toInt()
                require(score in 0..60 && kind in setOf("news", "technical", "cultural", "functional"))
                val previous = if (previousText == "-") emptySet() else previousText.split(',').toSet()
                require(previous.size <= 4 && previous.all { it.length in 2..80 && AdaptiveNgramModel.WORD_REGEX.matches(it) && normalize(it) == it && !SuggestionWordRules.isExcluded(it) })
                require(entries.put(word, Entry(word, display, score, kind == "news", previous)) == null)
                require(entries.size <= MAX_ENTRIES)
            }
            require(entries.isNotEmpty())
            val sha = hex(sha256(text.encodeToByteArray()))
            return CurrentVocabulary(entries.toMap(), sha, day, 0)
        }
    }
}
