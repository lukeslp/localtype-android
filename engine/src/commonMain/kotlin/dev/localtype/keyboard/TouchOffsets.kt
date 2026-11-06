package dev.localtype.keyboard

/**
 * The person's mean finger-lift offset from each letter key's center, in [LetterGeometry] units,
 * for one letters layout. Three arrays hold a count and two means per letter key (26 for English,
 * 312 bytes of array contents); words and traces are never retained. The caller admits only real,
 * uncorrected words from fields that allow learning.
 */
class TouchOffsets(private val geometry: LetterGeometry = LetterGeometry.QWERTY) {
    data class Entry(val letter: Char, val count: Int, val dx: Float, val dy: Float)

    /** The letter keys in character order, so English keeps its a-to-z order. */
    private val keys: List<Char> = geometry.keys
    private val counts = IntArray(keys.size)
    private val meanX = FloatArray(keys.size)
    private val meanY = FloatArray(keys.size)

    /** Changes when learning, loading or forgetting changes the state, for the corrector's memo. */
    var revision: Long = 0
        private set

    fun count(letter: Char): Int = index(letter)?.let { counts[it] } ?: 0

    /** Zero for a key without samples or a character that isn't one of this layout's letter keys. */
    fun offset(letter: Char): Touch = index(letter)?.let { Touch(meanX[it], meanY[it]) } ?: Touch(0f, 0f)

    /** Adjusts a measured lift; this never changes the character the keyboard types. */
    fun correctedTouch(letter: Char, touch: Touch): Touch {
        val i = index(letter) ?: return touch
        if (counts[i] == 0) return touch
        return Touch(touch.x - meanX[i], touch.y - meanY[i])
    }

    /**
     * Adds the letter keys of one complete, aligned trace. A missing or nonfinite letter touch
     * rejects the whole trace, rather than learning from only the part that happened to be present.
     * Other slots (such as an apostrophe, or a letter typed by holding a key) do not teach an offset.
     * At Int.MAX_VALUE samples, a key stops learning rather than overflowing its count or losing the
     * running mean's meaning.
     */
    fun record(word: String, touches: List<Touch?>?): Boolean {
        if (word.isEmpty() || touches == null || touches.size != word.length) return false
        var hasLetters = false
        for (j in word.indices) {
            val i = index(word[j]) ?: continue
            hasLetters = true
            val touch = touches[j] ?: return false
            if (!touch.x.isFinite() || !touch.y.isFinite()) return false
            val center = geometry.center(keys[i]) ?: return false
            if (!(touch.x - center.x).isFinite() || !(touch.y - center.y).isFinite()) return false
        }
        if (!hasLetters) return false

        var changed = false
        for (j in word.indices) {
            val i = index(word[j]) ?: continue
            if (counts[i] == Int.MAX_VALUE) continue
            val touch = touches[j]!!
            val center = geometry.center(keys[i])!!
            val count = counts[i] + 1
            // Double intermediates keep opposite finite extremes from overflowing the update.
            meanX[i] = (meanX[i].toDouble() + ((touch.x - center.x).toDouble() - meanX[i]) / count).toFloat()
            meanY[i] = (meanY[i].toDouble() + ((touch.y - center.y).toDouble() - meanY[i]) / count).toFloat()
            counts[i] = count
            changed = true
        }
        if (changed) revision++
        return changed
    }

    /** A detached snapshot for the preference store, containing only keys with samples. */
    fun entries(): List<Entry> = counts.indices.mapNotNull { i ->
        counts[i].takeIf { it > 0 }?.let { Entry(keys[i], it, meanX[i], meanY[i]) }
    }

    /** Replaces the state with a saved snapshot; malformed entries teach nothing. */
    fun load(entries: Iterable<Entry>) {
        val loadedCounts = IntArray(keys.size)
        val loadedX = FloatArray(keys.size)
        val loadedY = FloatArray(keys.size)
        for (entry in entries) {
            val i = index(entry.letter) ?: continue
            if (entry.count <= 0 || !entry.dx.isFinite() || !entry.dy.isFinite()) continue
            loadedCounts[i] = entry.count
            loadedX[i] = entry.dx
            loadedY[i] = entry.dy
        }
        if (counts.contentEquals(loadedCounts) && meanX.contentEquals(loadedX) && meanY.contentEquals(loadedY)) return
        loadedCounts.copyInto(counts)
        loadedX.copyInto(meanX)
        loadedY.copyInto(meanY)
        revision++
    }

    fun clear() {
        if (counts.all { it == 0 }) return
        counts.fill(0)
        meanX.fill(0f)
        meanY.fill(0f)
        revision++
    }

    /** The samples as text, one key a line ("e\t12\t0.03\t-0.11"), for a host that keeps them in a file. */
    fun encode(): String = entries().joinToString("") { "${it.letter}\t${it.count}\t${it.dx}\t${it.dy}\n" }

    companion object {
        /** What [encode] wrote; a malformed line is skipped, and [load] drops what isn't a key or is out of range. */
        fun decode(text: String): List<Entry> = text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4 || parts[0].length != 1) return@mapNotNull null
            val count = parts[1].toIntOrNull() ?: return@mapNotNull null
            val dx = parts[2].toFloatOrNull() ?: return@mapNotNull null
            val dy = parts[3].toFloatOrNull() ?: return@mapNotNull null
            Entry(parts[0][0], count, dx, dy)
        }.toList()
    }

    /**
     * The key's slot. A capital counts as its letter only when the two map to each other both ways
     * ("A" and "a", "Ñ" and "ñ"), so a character such as Turkish "İ" never lands on "i".
     */
    private fun index(letter: Char): Int? {
        val key = when {
            letter.isUpperCase() -> letter.lowercaseChar().takeIf { it.uppercaseChar() == letter } ?: return null
            else -> letter
        }
        return keys.binarySearch(key).takeIf { it >= 0 }
    }
}
