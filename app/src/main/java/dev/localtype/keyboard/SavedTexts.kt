package dev.localtype.keyboard

/**
 * Saved text: what the person chose to keep, newest first, at most [MAX] items. Only an explicit
 * Save adds anything (holding the paste chip, Save on a recent copy, or typing one in settings), and
 * saving text that's already here moves it to the top. [lastChange] lets the panel say "Saved" or
 * offer Undo for a removal, until the next change or until the panel closes ([forgetLastChange]).
 * An item can have a shortcut word ([codes]): typing that word offers the item in the strip. Main
 * thread only; the [Store] does the writing.
 */
class SavedTexts(private val store: Store) {
    /** Where saved text persists. [load] runs once, off the main thread; [save] gets the whole list after every change. */
    interface Store {
        fun load(): List<Entry>
        fun save(entries: List<Entry>)
    }

    /** A saved item as the file holds it: its text, and its shortcut word when it has one. */
    data class Entry(val text: String, val code: String? = null)

    /** The last change, for the panel's status row. A new object every time, so the panel can tell two saves apart. */
    sealed class Change {
        class Saved(val text: String) : Change()

        /** [index] is where it was and [code] the shortcut it had, so Undo puts both back. */
        class Removed(val text: String, val index: Int, val code: String? = null) : Change()
    }

    private val loaded = store.load().filter { fits(it.text) }.distinctBy { it.text }.take(MAX)

    /** Newest first. A damaged or hand-edited file loses its blanks, duplicates and overflow here. */
    var items: List<String> = loaded.map { it.text }
        private set

    /** Shortcut words by item, lowercase. A file with a malformed or repeated word loses that word, never the item. */
    var codes: Map<String, String> = LinkedHashMap<String, String>().also { map ->
        for (entry in loaded) {
            val code = entry.code?.let(::normalizeCode) ?: continue
            if (validCode(code) && code !in map.values) map[entry.text] = code
        }
    }
        private set

    var lastChange: Change? = null
        private set

    /** Called after every change to [items], including Remove all in settings while the panel is open. */
    var onChange: (() -> Unit)? = null

    /** Keeps [text] at the top. False, and nothing changes, for blank text or text longer than [MAX_CHARS]. */
    fun save(text: String): Boolean {
        if (!fits(text)) return false
        update(listOf(text) + items.filter { it != text }, Change.Saved(text))
        return true
    }

    fun remove(text: String) {
        val index = items.indexOf(text)
        if (index < 0) return
        val code = codes[text]
        codes = codes - text
        update(items.filterIndexed { i, _ -> i != index }, Change.Removed(text, index, code))
    }

    /** Puts the last removal back where it was, with its shortcut. False when anything else changed since, or the panel closed. */
    fun undo(): Boolean {
        val removed = lastChange as? Change.Removed ?: return false
        val restored = items.filter { it != removed.text }.toMutableList()
        restored.add(removed.index.coerceAtMost(restored.size), removed.text)
        if (removed.code != null && removed.code !in codes.values) codes = codes + (removed.text to removed.code)
        update(restored, change = null)
        return true
    }

    /**
     * Adds saved items from a copy made on another phone, below the ones already here and in the copy's order, up
     * to [MAX]. An item already saved is left where it is. A shortcut comes with its item when it is valid and no
     * item here has that word. Returns how many items were added.
     */
    fun merge(incoming: List<Entry>): Int {
        val next = items.toMutableList()
        var taken = codes
        var added = 0
        for (entry in incoming) {
            if (!fits(entry.text) || entry.text in next) continue
            if (next.size >= MAX) break
            next += entry.text
            added++
            val code = entry.code?.let(::normalizeCode) ?: continue
            if (validCode(code) && code !in taken.values) taken = taken + (entry.text to code)
        }
        if (added == 0) return 0
        codes = taken
        update(next, change = null)
        return added
    }

    /** Settings' Remove all saved text. */
    fun clear() {
        if (items.isEmpty()) return
        update(emptyList(), change = null)
    }

    /** What [setCode] did, so settings can say why a word was not taken. */
    enum class CodeResult { SET, CLEARED, INVALID, TAKEN, NOT_SAVED }

    /**
     * Gives the saved [text] the shortcut word [code], or takes its shortcut away when [code] is blank. A
     * shortcut is one word of [MIN_CODE] to [MAX_CODE] letters, stored lowercase, and belongs to one item.
     */
    fun setCode(text: String, code: String): CodeResult {
        if (text !in items) return CodeResult.NOT_SAVED
        val word = normalizeCode(code)
        if (word.isEmpty()) {
            if (text in codes) {
                codes = codes - text
                update(items, change = null)
            }
            return CodeResult.CLEARED
        }
        if (!validCode(word)) return CodeResult.INVALID
        if (codes[text] == word) return CodeResult.SET
        if (word in codes.values) return CodeResult.TAKEN
        codes = codes + (text to word)
        update(items, change = null)
        return CodeResult.SET
    }

    /** The saved item whose shortcut is exactly [typed], whatever its case; null when there is none. */
    fun expansion(typed: String): String? {
        if (codes.isEmpty()) return null
        val word = normalizeCode(typed)
        return codes.entries.firstOrNull { it.value == word }?.key
    }

    /** Whether [word] is some item's shortcut: such a word is the person's own and is never corrected. */
    fun isCode(word: String): Boolean = codes.isNotEmpty() && normalizeCode(word) in codes.values

    /** The panel closed: its "Saved" or Undo row goes with it. */
    fun forgetLastChange() {
        lastChange = null
    }

    /** [copies] without the ones already saved, so a copy shows in one section only. */
    fun unsaved(copies: List<String>): List<String> = copies.filter { it !in items }

    private var wordsNow: Set<String>? = null

    /**
     * Every word in every saved text, as the word model spells words: what must not become the first word of a
     * learned pair unless the person typed it there ([SavedWords]). Worked out once per change.
     */
    val words: Set<String> get() = wordsNow ?: SavedWords.of(items).also { wordsNow = it }

    private fun update(next: List<String>, change: Change?) {
        items = next.take(MAX)
        wordsNow = null
        // An item that left the list, by removal, Remove all or the cap, takes its shortcut with it.
        if (codes.isNotEmpty()) codes = codes.filterKeys { it in items }
        lastChange = change
        store.save(items.map { Entry(it, codes[it]) })
        onChange?.invoke()
    }

    companion object {
        const val MAX = 50

        /** The paste chip's limit too; a longer copy is neither offered nor kept. */
        const val MAX_CHARS = 5_000

        fun fits(text: String): Boolean = text.isNotBlank() && text.length <= MAX_CHARS

        const val MIN_CODE = 2
        const val MAX_CODE = 24

        /** A shortcut as it is stored and compared: trimmed and lowercase. */
        fun normalizeCode(code: String): String = code.trim().lowercase(java.util.Locale.ROOT)

        /**
         * Letters only. The keyboard reads the word being typed with its own word rule, where a digit or a
         * symbol ends the word, so a shortcut with one in it could never be typed as a single word.
         */
        fun validCode(code: String): Boolean = code.length in MIN_CODE..MAX_CODE && code.all { it.isLetter() }

        private val WHITESPACE = Regex("\\s+")

        /**
         * [text] for a list row or a label: line breaks and other whitespace runs become one space,
         * and past [maxChars] it ends in an ellipsis, cut between characters a person sees, never
         * inside an emoji. Only for display; inserting uses the text exactly as saved.
         */
        fun preview(text: String, maxChars: Int): String {
            val flat = text.trim().replace(WHITESPACE, " ")
            if (flat.length <= maxChars) return flat
            // A cut can land inside an emoji sequence, where the first character dropped is only a
            // piece of it. So cut further on and drop whole characters back to the limit; the
            // drops are back in step long before they reach it.
            var cut = flat.take(maxChars + CUT_SLACK)
            while (cut.length > maxChars) cut = TypingRules.dropLastGrapheme(cut)
            return cut.trimEnd() + "…"
        }

        /** Longer than any emoji sequence, so [preview]'s drops are back in step by the limit. */
        private const val CUT_SLACK = 64
    }
}

/**
 * Saved text's file format: a version line, then one item per line, newest first. Backslash, line
 * feed and carriage return are escaped (`\\`, `\n`, `\r`), so an item can hold any text, line
 * breaks included; tabs, emoji and everything else are stored as they are. An item with a shortcut
 * word ends in `\c` and the word. No earlier build wrote that escape, so a file from before
 * shortcuts reads as it always did, and a file with none is byte for byte what was written before.
 * Pure, so the file is tested without Android.
 */
object SavedTextCodec {
    const val HEADER = "localtype-saved-text 1"

    /** Items without shortcuts, as every build before shortcuts wrote them. */
    fun encode(items: List<String>): String = encodeEntries(items.map { SavedTexts.Entry(it) })

    fun encodeEntries(entries: List<SavedTexts.Entry>): String = buildString {
        append(HEADER).append('\n')
        for (entry in entries) {
            append(escape(entry.text))
            if (entry.code != null) append(CODE_ESCAPE).append(entry.code)
            append('\n')
        }
    }

    /** The items' texts in [file], shortcuts left out; empty for anything that isn't this format. */
    fun decode(file: String): List<String> = decodeEntries(file).map { it.text }

    /** The items in [file]; empty for anything that isn't this format. A file saved with CRLF line ends still reads. */
    fun decodeEntries(file: String): List<SavedTexts.Entry> {
        val lines = file.split('\n').map { it.removeSuffix("\r") }
        if (lines.first() != HEADER) return emptyList()
        return lines.drop(1).filter { it.isNotEmpty() }.map(::entry)
    }

    /** Splits a line at its shortcut escape, when what follows is a shortcut; anything else is text, as before. */
    private fun entry(line: String): SavedTexts.Entry {
        var i = 0
        while (i < line.length - 1) {
            if (line[i] != '\\') {
                i++
                continue
            }
            if (line[i + 1] == 'c') {
                val code = line.substring(i + 2)
                if (SavedTexts.validCode(code)) return SavedTexts.Entry(unescape(line.substring(0, i)), code)
            }
            // Past the escaped character, so the second backslash of a pair is never read as a new escape.
            i += 2
        }
        return SavedTexts.Entry(unescape(line))
    }

    private const val CODE_ESCAPE = "\\c"

    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    /** Lenient: an unknown escape keeps the escaped character, and a lone backslash at the end stays. */
    private fun unescape(line: String): String = buildString(line.length) {
        var i = 0
        while (i < line.length) {
            val c = line[i++]
            if (c != '\\' || i == line.length) {
                append(c)
                continue
            }
            when (val next = line[i++]) {
                'n' -> append('\n')
                'r' -> append('\r')
                else -> append(next)
            }
        }
    }
}
