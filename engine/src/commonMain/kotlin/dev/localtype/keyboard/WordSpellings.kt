package dev.localtype.keyboard

/**
 * How the person writes a word they've taught the keyboard, when that isn't all lowercase: "LocalType", "iPhone",
 * "McKenzie", "NASA", "Charlotte". The learned pairs, added words and the log stay lowercase; this only changes how a
 * suggestion is shown. Asked for on 2026-10-29 ("can learned words preserve capitalization / camel case?").
 */
object WordSpellings {
    /** Room for every added word to keep its capitals ([AddedWords.MAX_WORDS]) beside 512 learned from typing ([trim]). */
    const val MAX_WORDS = 512 + AddedWords.MAX_WORDS
    private val LETTERS = Regex("\\p{L}+(?:['’]\\p{L}+)*")

    sealed interface Change {
        /** Show the word as [display] from now on. */
        data class Record(val display: String) : Change
        /** Typed in lowercase: show it in lowercase again. */
        data object Forget : Change
        /** Says nothing about the person's spelling (a capital at a sentence start, caps lock). */
        data object Unchanged : Change
    }

    /**
     * What a verified, typed word says about its spelling. [sentenceStart] and [capsLock] explain a capital away.
     * [common] marks an everyday lowercase word ("will", "it", "may"), whose capitals are more likely emphasis or a
     * name used once than how the person always writes it, so they never record a spelling.
     */
    fun change(raw: String, sentenceStart: Boolean, capsLock: Boolean, common: Boolean): Change {
        if (capsLock || !LETTERS.matches(raw)) return Change.Unchanged
        if (raw == raw.lowercase()) return if (sentenceStart) Change.Unchanged else Change.Forget
        val letters = raw.filter { it.isLetter() }
        val allCaps = letters.length >= 2 && letters.all { it.isUpperCase() }
        return when {
            common -> Change.Unchanged
            allCaps || raw.drop(1).any { it.isUpperCase() } -> Change.Record(raw)
            sentenceStart -> Change.Unchanged
            else -> Change.Record(raw)
        }
    }

    /** An added word keeps exactly the spelling typed into Settings, when it has a capital. */
    fun added(input: String): Change = input.trim().let { if (LETTERS.matches(it) && it != AdaptiveNgramModel.normalize(it)) Change.Record(it) else Change.Unchanged }

    /** [word] shown with the stored spelling of it, or of its stem before 's or ', or null when there is none. */
    fun display(spellings: Map<String, String>, word: String): String? {
        val key = AdaptiveNgramModel.normalize(word)
        spellings[key]?.let { return it }
        val suffix = when { key.endsWith("'s") -> "'s"; key.endsWith("s'") -> "'"; else -> return null }
        return spellings[key.dropLast(suffix.length)]?.plus(suffix)
    }

    /** One line per word, "word<TAB>Spelling"; a line whose spelling doesn't lowercase to its word is dropped. */
    fun encode(spellings: Map<String, String>): String = spellings.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    fun decode(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 2 && parts[1].isNotEmpty() && AdaptiveNgramModel.normalize(parts[1]) == parts[0] && LETTERS.matches(parts[1])) out[parts[0]] = parts[1]
        }
        while (out.size > MAX_WORDS) out.remove(out.keys.first())
        return out
    }

    /**
     * Makes room past [MAX_WORDS]: the oldest spelling goes, but never one [keep] names while another can go.
     * [keep] is the person's added words, whose capitals they typed on purpose; a spelling learned from typing
     * comes back the next time the word is typed that way.
     */
    private fun trim(out: LinkedHashMap<String, String>, keep: (String) -> Boolean) {
        while (out.size > MAX_WORDS) out.remove(out.keys.firstOrNull { !keep(it) } ?: out.keys.first())
    }

    /** [apply] for a list in its order, as an import records its capitals; null when nothing changed. */
    fun applyAll(spellings: Map<String, String>, changes: List<Pair<String, Change>>, keep: (String) -> Boolean = { false }): Map<String, String>? {
        val out = LinkedHashMap(spellings)
        var changed = false
        for ((word, change) in changes) {
            val key = AdaptiveNgramModel.normalize(word)
            when (change) {
                is Change.Record -> if (out[key] != change.display) { out.remove(key); out[key] = change.display; changed = true }
                Change.Forget -> if (out.remove(key) != null) changed = true
                Change.Unchanged -> Unit
            }
        }
        trim(out, keep)
        return if (changed) out else null
    }

    /**
     * [spellings] with [change] applied to [word]; the newest spelling moves to the end and the oldest leaves past
     * [MAX_WORDS], passing over the ones [keep] names.
     */
    fun apply(spellings: Map<String, String>, word: String, change: Change, keep: (String) -> Boolean = { false }): Map<String, String>? {
        val key = AdaptiveNgramModel.normalize(word)
        return when (change) {
            is Change.Record -> if (spellings[key] == change.display) null else LinkedHashMap(spellings).apply {
                remove(key)
                put(key, change.display)
                trim(this, keep)
            }
            Change.Forget -> if (key in spellings) LinkedHashMap(spellings).apply { remove(key) } else null
            Change.Unchanged -> null
        }
    }
}
