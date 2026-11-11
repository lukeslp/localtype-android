package dev.localtype.keyboard

/**
 * A person's keyboard in one file, for moving to another phone: the parts they chose out of settings, their word
 * lists, saved text and what the keyboard has learned. Plain text, so it can be read before it is trusted.
 *
 * A part that is null was left out of the copy; a part that is present and empty was chosen and held nothing.
 * This class only carries values. Each store checks what it is given with its own rules before taking any of it,
 * so a hand-edited copy can put nothing into the keyboard that typing or settings could not.
 */
data class KeyboardCopy(
    val versionName: String,
    val versionCode: Int,
    /** The day it was saved, yyyy-MM-dd, for the person reading the summary. */
    val made: String,
    val settings: Settings? = null,
    val words: List<Words>? = null,
    val saved: List<Saved>? = null,
    val learned: Learned? = null,
) {
    /** [type] is 'b', 'i' or 's': how the value is stored. A boolean's value is "1" or "0". */
    data class Setting(val type: Char, val key: String, val value: String)
    data class Tone(val base: String, val tone: Int)
    data class Settings(val values: List<Setting>, val tones: List<Tone> = emptyList(), val defaultTone: Int? = null)

    /** [display] is the word as the person spells it, when that differs from the stored lowercase word. */
    data class Added(val word: String, val display: String? = null)
    data class Words(val language: String, val added: List<Added>, val blocked: List<String>)

    data class Saved(val text: String, val code: String? = null)

    data class Pair(val previous: String, val next: String, val count: Int)
    data class Context(val context: List<String>, val word: String, val observations: Int, val explicit: Int, val observed: Long)
    data class Spelling(val word: String, val display: String)
    data class LearnedLanguage(val language: String, val pairs: List<Pair>, val contexts: List<Context>, val spellings: List<Spelling>)
    data class Site(val host: String, val count: Int, val day: Long)
    data class Learned(val languages: List<LearnedLanguage>, val sites: List<Site> = emptyList(), val emoji: List<String> = emptyList())

    val addedCount: Int get() = words.orEmpty().sumOf { it.added.size }
    val blockedCount: Int get() = words.orEmpty().sumOf { it.blocked.size }
    val pairCount: Int get() = learned?.languages.orEmpty().sumOf { it.pairs.size }

    sealed interface Read {
        data class Ok(val copy: KeyboardCopy) : Read
        /** Some other file. */
        data object NotACopy : Read
        /** Saved by a later LocalType in a format this one does not know. */
        data class Newer(val format: Int) : Read
        /** A copy that was cut short or changed so that its lines no longer add up. */
        data object Damaged : Read
    }

    companion object {
        const val FORMAT = 1
        const val HEADER = "localtype-copy"
        /**
         * Above the largest copy the stores' own bounds allow, with every language full: about 76,500 rows and
         * 2 MB for each. A real copy is a small fraction of this.
         */
        const val MAX_BYTES = 32 * 1024 * 1024
        const val MAX_LINES = 1_200_000
        /** A row's field as written. A saved text of 5,000 characters is at most 20,000 with every one escaped. */
        private const val MAX_FIELD_UNITS = 32_768
        private const val MAX_CONTEXT_WORDS = 3

        fun encode(copy: KeyboardCopy): String {
            val lines = ArrayList<String>()
            lines += row(HEADER, FORMAT.toString())
            lines += row("app", copy.versionName, copy.versionCode.toString())
            lines += row("made", copy.made)
            copy.settings?.let { settings ->
                lines += row("part", "settings")
                settings.values.forEach { lines += row(it.type.toString(), it.key, it.value) }
                settings.tones.forEach { lines += row("tone", it.base, it.tone.toString()) }
                settings.defaultTone?.let { lines += row("tones", it.toString()) }
            }
            // Present even with no word in any language, so the part reads back as chosen and empty.
            if (copy.words != null) lines += row("part", "words")
            copy.words?.forEach { words ->
                lines += row("part", "words", words.language)
                words.added.forEach { lines += if (it.display == null) row("a", it.word) else row("a", it.word, it.display) }
                words.blocked.forEach { lines += row("x", it) }
            }
            copy.saved?.let { saved ->
                lines += row("part", "saved")
                saved.forEach { lines += if (it.code == null) row("t", it.text) else row("t", it.text, it.code) }
            }
            copy.learned?.let { learned ->
                // Present even when nothing was learned, so the part reads back as chosen and empty.
                lines += row("part", "learned")
                learned.languages.forEach { language ->
                    lines += row("part", "learned", language.language)
                    language.pairs.forEach { lines += row("p", it.previous, it.next, it.count.toString()) }
                    language.contexts.forEach {
                        lines += row("c", it.context.joinToString(" "), it.word, it.observations.toString(), it.explicit.toString(), it.observed.toString())
                    }
                    language.spellings.forEach { lines += row("w", it.word, it.display) }
                }
                lines += row("part", "sites")
                learned.sites.forEach { lines += row("h", it.host, it.count.toString(), it.day.toString()) }
                lines += row("part", "emoji")
                learned.emoji.forEach { lines += row("e", it) }
            }
            lines += row("end", lines.size.toString())
            return lines.joinToString("\n", postfix = "\n")
        }

        fun decode(text: String): Read {
            if (text.length > MAX_BYTES) return Read.Damaged
            // Decided from the first line alone, before anything is made for each line of a file that is not a copy.
            if (!text.startsWith("$HEADER\t")) return Read.NotACopy
            val head = fields(text.substring(0, text.indexOf('\n').let { if (it < 0) text.length else it }).removeSuffix("\r"))
            if (head == null || head.size != 2) return Read.NotACopy
            val format = head[1].toIntOrNull() ?: return Read.NotACopy
            if (format > FORMAT) return Read.Newer(format)
            if (format < 1) return Read.Damaged
            // Lines are counted, then read one at a time; the file is never held as a list of them.
            val trailing = text.endsWith('\n')
            val count = text.count { it == '\n' } + if (trailing) 0 else 1
            if (count > MAX_LINES || count < 2) return Read.Damaged

            var versionName = ""
            var versionCode = 0
            var made = ""
            var settings: MutableList<Setting>? = null
            val tones = ArrayList<Tone>()
            var defaultTone: Int? = null
            val words = LinkedHashMap<String, kotlin.Pair<MutableList<Added>, MutableList<String>>>()
            var saved: MutableList<Saved>? = null
            var wordsSeen = false
            var learnedSeen = false
            val learned = LinkedHashMap<String, Triple<MutableList<Pair>, MutableList<Context>, MutableList<Spelling>>>()
            val sites = ArrayList<Site>()
            val emoji = ArrayList<String>()
            var part = ""
            var language = ""

            var index = 0
            var start = 0
            while (start < text.length) {
                val stop = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                val line = text.substring(start, if (stop > start && text[stop - 1] == '\r') stop - 1 else stop)
                start = stop + 1
                val position = index++
                if (position == 0) continue
                val row = fields(line) ?: return Read.Damaged
                if (position == count - 1) {
                    // The last line counts the ones before it: a copy cut short in transit is refused whole.
                    if (row.size != 2 || row[0] != "end" || row[1].toIntOrNull() != count - 1) return Read.Damaged
                    continue
                }
                if (row.isEmpty()) continue
                val tag = row[0]
                when {
                    tag == "app" && row.size == 3 -> { versionName = row[1]; versionCode = row[2].toIntOrNull() ?: 0 }
                    tag == "made" && row.size == 2 -> made = row[1]
                    tag == "part" && row.size >= 2 -> {
                        part = row[1]
                        language = row.getOrElse(2) { "" }
                        when (part) {
                            "settings" -> if (settings == null) settings = ArrayList()
                            "words" -> {
                                wordsSeen = true
                                if (language.isNotEmpty()) words.getOrPut(language) { kotlin.Pair(ArrayList(), ArrayList()) }
                            }
                            "saved" -> if (saved == null) saved = ArrayList()
                            "learned" -> {
                                learnedSeen = true
                                if (language.isNotEmpty()) learned.getOrPut(language) { Triple(ArrayList(), ArrayList(), ArrayList()) }
                            }
                            "sites", "emoji" -> learnedSeen = true
                        }
                    }
                    // A row this version does not know, in a part it does or does not: left out, never guessed at.
                    part == "settings" -> when {
                        tag.length == 1 && tag[0] in "bis" && row.size == 3 -> settings?.add(Setting(tag[0], row[1], row[2]))
                        tag == "tone" && row.size == 3 -> row[2].toIntOrNull()?.let { tones += Tone(row[1], it) }
                        tag == "tones" && row.size == 2 -> defaultTone = row[1].toIntOrNull()
                    }
                    part == "words" -> words[language]?.let { (added, blocked) ->
                        when {
                            tag == "a" && row.size in 2..3 -> added += Added(row[1], row.getOrNull(2))
                            tag == "x" && row.size == 2 -> blocked += row[1]
                        }
                    }
                    part == "saved" -> if (tag == "t" && row.size in 2..3) saved?.add(Saved(row[1], row.getOrNull(2)))
                    part == "learned" -> learned[language]?.let { (pairs, contexts, spellings) ->
                        when {
                            tag == "p" && row.size == 4 -> row[3].toIntOrNull()?.let { pairs += Pair(row[1], row[2], it) }
                            tag == "c" && row.size == 6 -> {
                                val context = row[1].split(' ').filter { it.isNotEmpty() }
                                val observations = row[3].toIntOrNull()
                                val explicit = row[4].toIntOrNull()
                                val observed = row[5].toLongOrNull()
                                if (context.size in 1..MAX_CONTEXT_WORDS && observations != null && explicit != null && observed != null) {
                                    contexts += Context(context, row[2], observations, explicit, observed)
                                }
                            }
                            tag == "w" && row.size == 3 -> spellings += Spelling(row[1], row[2])
                        }
                    }
                    part == "sites" -> if (tag == "h" && row.size == 4) {
                        val count = row[2].toIntOrNull()
                        val day = row[3].toLongOrNull()
                        if (count != null && day != null) sites += Site(row[1], count, day)
                    }
                    part == "emoji" -> if (tag == "e" && row.size == 2) emoji += row[1]
                }
            }
            return Read.Ok(
                KeyboardCopy(
                    versionName = versionName,
                    versionCode = versionCode,
                    made = made,
                    settings = settings?.let { Settings(it, tones, defaultTone) },
                    words = if (!wordsSeen) null else words.map { (code, lists) -> Words(code, lists.first, lists.second) },
                    saved = saved,
                    learned = if (!learnedSeen) null else Learned(
                        learned.map { (code, lists) -> LearnedLanguage(code, lists.first, lists.second, lists.third) }, sites, emoji,
                    ),
                ),
            )
        }

        /**
         * Whether [word] is one the learned stores could hold from typing in [language]: already in the stored
         * lowercase form, shaped like a word, within the stores' size, and not an excluded word. A [follower] is
         * a word that can be offered, so a single letter must be one of the language's one-letter words.
         */
        fun learnedWord(word: String, language: LanguageProfile, follower: Boolean): Boolean =
            PersonalLearningCodec.validWordSize(word) && AdaptiveNgramModel.normalize(word) == word && WordRule.STORED.matches(word) &&
                (!follower || word.length >= 2 || word in language.oneLetterWords) && !SuggestionWordRules.isExcluded(word, language)

        private fun row(vararg fields: String): String = fields.joinToString("\t") { escape(it) }

        /** Tabs and line ends are the format's own; the word model's markers are control characters. */
        private fun escape(field: String): String {
            if (field.none { it == '\\' || it < ' ' || it == '\u007f' }) return field
            val out = StringBuilder(field.length + 8)
            for (char in field) {
                when {
                    char == '\\' -> out.append("\\\\")
                    char == '\t' -> out.append("\\t")
                    char == '\n' -> out.append("\\n")
                    char == '\r' -> out.append("\\r")
                    char < ' ' || char == '\u007f' -> out.append("\\x").append(char.code.toString(16).padStart(2, '0'))
                    else -> out.append(char)
                }
            }
            return out.toString()
        }

        /** The line's fields, or null when an escape in it is not one [escape] writes or a field is too long. */
        private fun fields(line: String): List<String>? {
            if (line.isEmpty()) return emptyList()
            val out = ArrayList<String>(4)
            for (raw in line.split('\t')) {
                if (raw.length > MAX_FIELD_UNITS) return null
                if ('\\' !in raw) { out += raw; continue }
                val field = StringBuilder(raw.length)
                var index = 0
                while (index < raw.length) {
                    val char = raw[index]
                    if (char != '\\') { field.append(char); index++; continue }
                    when (raw.getOrNull(index + 1)) {
                        '\\' -> { field.append('\\'); index += 2 }
                        't' -> { field.append('\t'); index += 2 }
                        'n' -> { field.append('\n'); index += 2 }
                        'r' -> { field.append('\r'); index += 2 }
                        'x' -> {
                            // Two hex digits and nothing else: toInt alone would also take a sign.
                            val code = raw.substring(minOf(index + 2, raw.length), minOf(index + 4, raw.length))
                                .takeIf { digits -> digits.length == 2 && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }?.toInt(16)
                            if (code == null || (code >= 0x20 && code != 0x7f)) return null
                            field.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                }
                out += field.toString()
            }
            return out
        }
    }
}
