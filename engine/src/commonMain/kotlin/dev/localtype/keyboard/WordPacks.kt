package dev.localtype.keyboard

/**
 * One opt-in word pack: specialist words (drug names, political offices and jargon) for one language, switched on in
 * Settings. A pack word is recognized, so the corrector never "fixes" it when typed; it is completed only from its
 * kind's minimum of typed letters (`min-prefix=`, never under [MIN_PREFIX]: the medical pack asks five for drug names,
 * following ECRI/ISMP's guidance for drug-name searches, and three for the rest); it is never offered with nothing typed; it is never applied as an automatic correction,
 * only offered (look-alike drug names: hydroxyzine and hydralazine); and it never reaches Chime, whose vocabulary is
 * frozen. Officeholder names fade from completion the way `current_words.txt`'s news bonus fades, measured from the
 * pack's `observed=` date and pinned per field; recognition outlives the fade. Nothing here changes the frequency
 * dictionary, the corpus prior, learned tables or correction scores.
 *
 * The file is `current_words.txt`'s shape without the previous-word column: `#` header lines, then
 * `key<TAB>display<TAB>score<TAB>kind`. A bundled pack is parsed [strict]ly (any bad line rejects the whole pack, as
 * the shared vocabulary does). An imported one is parsed leniently: the columns after the word are optional, so a
 * plain word list, one word per line, is a pack too, and a line that can't be used is counted and left out.
 */
class WordPack private constructor(
    val id: String,
    /** [LanguageProfile.code] of the language the pack belongs to. */
    val language: String,
    val name: String,
    /** Days since 1970-01-01 of the `observed=` date; null for a pack with no officeholders and no date. */
    val observedDay: Long?,
    private val entries: Map<String, Entry>,
    /**
     * Each kind's words, ordered by score so a prefix finds the strongest first. Kept apart so a kind that a prefix is
     * too short for, or that has faded, never crowds the others out of the prefix's pool.
     */
    private val byKind: Map<Kind, WordDictionary>,
    /** Letters a kind needs typed before its words are completed (`min-prefix=`); [MIN_PREFIX] for a kind not named. */
    private val minPrefixes: Map<Kind, Int>,
    val sha256: String,
) {
    enum class Kind(val id: String, val fades: Boolean) {
        TERM("term", false), DRUG("drug", false), OFFICE("office", false), OFFICEHOLDER("officeholder", true);

        companion object {
            fun of(id: String): Kind? = entries.firstOrNull { it.id == id }
        }
    }

    class Entry(val word: String, val display: String, val score: Int, val kind: Kind)

    val size: Int get() = entries.size
    val words: Set<String> get() = entries.keys
    fun count(kind: Kind): Int = entries.values.count { it.kind == kind }

    operator fun contains(word: String): Boolean = word in entries
    fun entry(word: String): Entry? = entries[word]

    /** Percent of an officeholder's score left on [epochDay]: all of it for [FULL_DAYS], half until [END_DAYS], then none. */
    fun fade(epochDay: Long): Int {
        val observed = observedDay ?: return 0
        val age = epochDay - observed
        return when {
            age < 0 || age >= END_DAYS -> 0
            age >= FULL_DAYS -> 50
            else -> 100
        }
    }

    /** Typed letters [kind]'s words need before they are completed: the pack's `min-prefix=`, or [MIN_PREFIX]. */
    fun minPrefix(kind: Kind): Int = minPrefixes[kind] ?: MIN_PREFIX

    /** [entry]'s score on [epochDay]: officeholders faded, everything else as listed. */
    fun score(entry: Entry, epochDay: Long): Int = if (entry.kind.fades) entry.score * fade(epochDay) / 100 else entry.score

    /**
     * Words completing [key] (already normalized), strongest first, with their faded score: only the kinds whose
     * minimum [key]'s letters reach, and no officeholder once faded out.
     */
    internal fun candidates(key: String, epochDay: Long, pool: Int): List<Pair<String, Int>> {
        val letters = key.count { it.isLetter() }
        return byKind.entries.asSequence()
            .filter { (kind, _) -> letters >= minPrefix(kind) && (!kind.fades || fade(epochDay) > 0) }
            .flatMap { (_, words) -> words.completions(key, pool) }
            .mapNotNull { (word, _) -> entries[word]?.let { word to score(it, epochDay) } }
            .filter { it.second > 0 }.toList()
    }

    /** One line of the pack's identity for diagnostics: id, language, size and a short digest. */
    fun describe(): String = "$id/$language/${entries.size}/${sha256.take(12)}"

    /** The pack written back in its own file format: what an import keeps, so a later read is strict. */
    fun encode(sources: List<String> = emptyList()): String = buildString {
        append(FORMAT).append('\n')
        append("# id=").append(id).append('\n')
        append("# language=").append(language).append('\n')
        append("# name=").append(name).append('\n')
        observedDay?.let { append("# observed=").append(isoDate(it)).append('\n') }
        if (minPrefixes.isNotEmpty()) append("# min-prefix=").append(encodeMinPrefix(minPrefixes)).append('\n')
        sources.forEach { append("# ").append(it).append('\n') }
        entries.values.forEach { e ->
            append(e.word).append('\t').append(e.display).append('\t').append(e.score).append('\t').append(e.kind.id).append('\n')
        }
    }

    /** What a lenient read made of a file: the pack (null when nothing could be used), and the lines it left out. */
    class Import(val pack: WordPack?, val skipped: Int, val overLimit: Int)

    companion object {
        const val FORMAT = "# LocalType word pack v1"

        /** Completion starts at three typed letters at the least, as the shared vocabulary's does; a kind may ask more. */
        const val MIN_PREFIX = 3

        /** The most letters `min-prefix=` may ask for a kind. */
        const val MAX_MIN_PREFIX = 8

        /** An officeholder is completed at full score this many days from `observed=`, at half until [END_DAYS], then not at all. */
        const val FULL_DAYS = 60
        const val END_DAYS = 120

        /** Scores are editorial, on the shared vocabulary's 0..60 scale, below the commonest dictionary words. */
        const val MAX_SCORE = 60
        const val DEFAULT_SCORE = 30
        const val MAX_ENTRIES = 20_000
        const val MAX_CHARS = 1_048_576
        const val MAX_WORD = 48

        /** Pack ids: lowercase letters, digits and hyphens. Imported packs are prefixed so they never take a bundled id. */
        val ID = Regex("[a-z0-9][a-z0-9-]{0,39}")
        const val IMPORTED_PREFIX = "import-"

        private val ISO = Regex("\\d{4}-\\d{2}-\\d{2}")

        fun normalize(word: String) = AdaptiveNgramModel.normalize(word.trim())

        /** The [LanguageProfile] with words for [code], or null. */
        fun languageOf(code: String): LanguageProfile? = LanguageProfile.WITH_WORDS.firstOrNull { it.code == code }

        /**
         * A bundled pack, or an [imported] one read back from its own file: every header and line must be right, or the
         * whole pack is refused (as `current_words.txt` is). Only an imported pack's id starts with [IMPORTED_PREFIX].
         */
        fun strict(text: String, imported: Boolean = false): WordPack {
            require(text.length <= MAX_CHARS) { "pack too large" }
            val lines = text.removePrefix("﻿").lineSequence().toList()
            require(lines.firstOrNull() == FORMAT) { "not a LocalType word pack" }
            val header = headers(lines)
            val id = requireNotNull(header["id"]) { "no id" }.also { require(ID.matches(it) && it.startsWith(IMPORTED_PREFIX) == imported) { "bad id" } }
            val language = requireNotNull(header["language"]?.let(::languageOf)) { "no language with words" }
            val name = requireNotNull(header["name"]?.takeIf { it.isNotBlank() && it.length <= 80 }) { "no name" }
            val observed = header["observed"]?.let { CurrentVocabulary.epochDay(it) }
            val minPrefixes = header["min-prefix"]?.let { requireNotNull(parseMinPrefix(it, strict = true)) { "bad min-prefix" } }.orEmpty()
            val entries = LinkedHashMap<String, Entry>()
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val parts = line.split('\t')
                require(parts.size == 4) { "a line needs four columns" }
                val entry = requireNotNull(entry(parts[0], parts[1], parts[2].toIntOrNull(), Kind.of(parts[3]), language)) { "bad line" }
                require(entry.word == parts[0]) { "key not normalized" }
                require(entries.put(entry.word, entry) == null) { "duplicate" }
                require(entries.size <= MAX_ENTRIES) { "too many entries" }
            }
            require(entries.isNotEmpty()) { "empty pack" }
            require(observed != null || entries.values.none { it.kind.fades }) { "officeholders need observed=" }
            return build(id, language.code, name, observed, entries, minPrefixes, text)
        }

        /**
         * A pack chosen by the person. Headers are used where present; otherwise the id comes from [fallbackId], the
         * language from [fallbackLanguage], the name from [fallbackName], and officeholders count from [today]. A line
         * is a word alone or the full four columns' leading part; the first column's capitals become its display.
         */
        fun lenient(text: String, fallbackId: String, fallbackLanguage: String, fallbackName: String, today: Long): Import {
            val lines = text.removePrefix("﻿").lineSequence().toList()
            val header = headers(lines)
            val language = header["language"]?.let(::languageOf) ?: languageOf(fallbackLanguage) ?: LanguageProfile.ENGLISH
            val given = header["id"]?.lowercase()?.removePrefix(IMPORTED_PREFIX)?.takeIf { ID.matches(IMPORTED_PREFIX + it) }
            val id = IMPORTED_PREFIX + (given ?: fallbackId.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
                .trim('-').take(32).ifEmpty { "pack" })
            val name = header["name"]?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: fallbackName.take(80).ifBlank { id }
            val observed = header["observed"]?.takeIf { ISO.matches(it) }?.let { runCatching { CurrentVocabulary.epochDay(it) }.getOrNull() } ?: today
            // A person's own file may ask more letters for a kind; what can't be read asks nothing more than three.
            val minPrefixes = header["min-prefix"]?.let { parseMinPrefix(it, strict = false) }.orEmpty()
            val entries = LinkedHashMap<String, Entry>()
            var skipped = 0
            var over = 0
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val parts = line.split('\t').map { it.trim() }
                val typed = parts[0]
                val display = parts.getOrNull(1).orEmpty().ifEmpty { typed }
                val scoreText = parts.getOrNull(2).orEmpty()
                val kindText = parts.getOrNull(3).orEmpty()
                // A missing column takes its default; a column that is there but wrong leaves the line out.
                val score = if (scoreText.isEmpty()) DEFAULT_SCORE else scoreText.toIntOrNull()
                val kind = if (kindText.isEmpty()) Kind.TERM else Kind.of(kindText.lowercase())
                val entry = if (parts.size > 4) null else entry(typed, display, score, kind, language)
                when {
                    entry == null || entry.word in entries -> skipped++
                    entries.size >= MAX_ENTRIES -> over++
                    else -> entries[entry.word] = entry
                }
            }
            if (entries.isEmpty()) return Import(null, skipped, over)
            val pack = build(id, language.code, name, observed, entries, minPrefixes, text)
            return Import(pack, skipped, over)
        }

        private fun headers(lines: List<String>): Map<String, String> = lines.asSequence()
            .filter { it.startsWith("# ") && '=' in it }
            .map { it.removePrefix("# ").substringBefore('=').trim() to it.substringAfter('=').trim() }
            .filter { (key, _) -> key in setOf("id", "language", "name", "observed", "min-prefix") }
            .distinctBy { it.first }.toMap()

        /**
         * `min-prefix=drug:5,term:3`: letters each named kind needs typed, [MIN_PREFIX] to [MAX_MIN_PREFIX]. [strict]: one bad
         * or repeated pair refuses the whole value (null); otherwise bad pairs are left out.
         */
        fun parseMinPrefix(value: String, strict: Boolean): Map<Kind, Int>? {
            val out = LinkedHashMap<Kind, Int>()
            for (pair in value.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val kind = Kind.of(pair.substringBefore(':').trim().lowercase())
                val letters = pair.substringAfter(':', "").trim().toIntOrNull()
                val ok = ':' in pair && kind != null && letters != null && letters in MIN_PREFIX..MAX_MIN_PREFIX && kind !in out
                if (ok) out[kind!!] = letters!! else if (strict) return null
            }
            return out
        }

        fun encodeMinPrefix(minPrefixes: Map<Kind, Int>): String =
            Kind.entries.filter { it in minPrefixes }.joinToString(",") { "${it.id}:${minPrefixes.getValue(it)}" }

        /** One validated entry, or null: a single word of the language, not excluded, its display the same word. */
        private fun entry(typed: String, display: String, score: Int?, kind: Kind?, language: LanguageProfile): Entry? {
            val word = normalize(typed)
            if (kind == null || score == null || score !in 1..MAX_SCORE) return null
            if (word.length !in 2..MAX_WORD || !language.wordRule.word.matches(word)) return null
            if (SuggestionWordRules.isExcluded(word, language)) return null
            val shown = display.trim()
            if (normalize(shown) != word) return null
            return Entry(word, shown, score, kind)
        }

        private fun build(
            id: String, language: String, name: String, observed: Long?, entries: Map<String, Entry>, minPrefixes: Map<Kind, Int>,
            text: String,
        ): WordPack {
            val profile = languageOf(language) ?: LanguageProfile.ENGLISH
            // A dictionary of score-as-count keeps the strongest words first within any prefix, folded for accented languages.
            val byKind = entries.values.groupBy { it.kind }.mapValues { (_, kind) ->
                WordDictionary.parse(kind.asSequence().map { "${it.word}\t${it.score}" }, profile)
            }
            return WordPack(id, language, name, observed, entries.toMap(), byKind, minPrefixes.toMap(),
                hex(sha256(text.encodeToByteArray())))
        }

        /** The ISO date of [epochDay], as LocalDate.ofEpochDay(epochDay).toString(). */
        fun isoDate(epochDay: Long): String {
            // Civil from days (Hinnant), the inverse of CurrentVocabulary.epochDay.
            val z = epochDay + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
        }
    }
}

/**
 * The packs switched on for one field's language, with the day pinned at field start, so completion never reads a
 * clock while predicting. Empty when no pack is on: then every caller behaves exactly as before packs existed.
 */
class WordPacks private constructor(val packs: List<WordPack>, val epochDay: Long) {
    val isEmpty: Boolean get() = packs.isEmpty()

    /** The same packs on another day (each field pins its own). */
    fun at(epochDay: Long): WordPacks = if (packs.isEmpty() || epochDay == this.epochDay) this else WordPacks(packs, epochDay)

    /** Recognized: in any pack, faded or not. */
    operator fun contains(word: String): Boolean {
        if (packs.isEmpty()) return false
        val key = WordPack.normalize(word)
        return packs.any { key in it }
    }

    private fun entry(word: String): Pair<WordPack, WordPack.Entry>? {
        for (pack in packs) pack.entry(word)?.let { return pack to it }
        return null
    }

    /** Never an automatic correction target: every pack word is offered only. */
    fun offerOnly(word: String): Boolean = word in this

    /** The pack's display spelling ("Lipitor", "Schumer"), with a possessive kept; null when no pack has the word. */
    fun preferred(word: String): String? {
        if (packs.isEmpty()) return null
        val key = WordPack.normalize(word)
        entry(key)?.let { return it.second.display }
        val suffix = when { key.endsWith("'s") -> "'s"; key.endsWith("s'") -> "'"; else -> return null }
        return entry(key.dropLast(suffix.length))?.second?.display?.plus(suffix)
    }

    /**
     * Completions for a typed [prefix] of at least [WordPack.MIN_PREFIX] letters, strongest first; nothing for a
     * shorter or empty prefix, so a pack never fills the next-word slots. Expired officeholders are left out.
     */
    fun candidates(prefix: String): List<Pair<String, Int>> {
        if (packs.isEmpty()) return emptyList()
        val key = WordPack.normalize(prefix)
        if (key.count { it.isLetter() } < WordPack.MIN_PREFIX) return emptyList()
        val best = HashMap<String, Int>()
        for (pack in packs) for ((word, score) in pack.candidates(key, epochDay, POOL)) {
            if (score > (best[word] ?: 0)) best[word] = score
        }
        return best.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(POOL).map { it.key to it.value }
    }

    /** The score a pack word adds over its dictionary score, faded for officeholders; zero for anything else. */
    fun lift(word: String, dictionaryScore: Int): Int {
        val (pack, entry) = entry(word) ?: return 0
        return (pack.score(entry, epochDay) - dictionaryScore).coerceAtLeast(0)
    }

    /**
     * The pack words that may take the reserved slot beside Chime's words: those [candidates] that the n-gram ranking
     * itself put in its first [slots], so a pack word shows only where it beats everyday completions of the prefix.
     */
    fun reserved(prefix: String, ranked: List<String>, slots: Int = 3): List<String> {
        if (packs.isEmpty()) return emptyList()
        val top = ranked.take(slots).mapTo(HashSet(), AdaptiveNgramModel::normalize)
        return candidates(prefix).map { it.first }.filter { it in top }
    }

    /** Every pack word, for the corrector's offer-only scan. */
    fun words(): Set<String> = if (packs.size == 1) packs[0].words else packs.flatMapTo(HashSet()) { it.words }

    /** Ids and short digests, for diagnostics. */
    fun describe(): String = if (packs.isEmpty()) "none" else packs.joinToString(",") { it.describe() }

    companion object {
        /** How many pack words one prefix brings to the ranking at most. */
        const val POOL = 8
        val EMPTY = WordPacks(emptyList(), 0)

        fun of(packs: List<WordPack>, epochDay: Long): WordPacks = if (packs.isEmpty()) EMPTY else WordPacks(packs.toList(), epochDay)
    }
}
