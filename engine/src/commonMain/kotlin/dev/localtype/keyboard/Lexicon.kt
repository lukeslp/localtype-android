package dev.localtype.keyboard

/**
 * A large read-only set of known words (about 130k), stored as one string plus offsets instead of
 * one String object per word, so it costs a few megabytes. Used to recognize real but uncommon
 * words, which must never be "corrected" and may be offered as correction targets. Words that came
 * out of order (a short list added after the sorted one) are kept apart in [extra], sorted.
 */
class Lexicon private constructor(private val chars: String, private val starts: IntArray, private val extra: List<String>) {
    val size: Int get() = starts.size + extra.size

    operator fun contains(word: String): Boolean = inRun(word) || extra.binarySearch(word) >= 0

    private fun inRun(word: String): Boolean {
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareAt(mid, word)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return true
            }
        }
        return false
    }

    private fun compareAt(index: Int, word: String): Int {
        val start = starts[index]
        val end = (if (index + 1 < starts.size) starts[index + 1] else chars.length) - 1
        val length = end - start
        val shared = minOf(length, word.length)
        for (i in 0 until shared) {
            val diff = chars[start + i] - word[i]
            if (diff != 0) return diff
        }
        return length - word.length
    }

    companion object {
        val EMPTY = Lexicon("", IntArray(0), emptyList())

        /**
         * Lines are words; blanks and `#` comments are skipped. Input need not be sorted, but sorted input (as the
         * bundled lists are) is read in one pass that keeps no String per word, rather than holding a list of all
         * 130k words, a set to drop repeats and a sorted copy at once, which a keyboard extension's small memory
         * limit can't spare. Lines out of order are kept apart as [extra]. [expectedChars], the length of the text
         * the lines come from, sizes the one string at once instead of letting it double as it grows.
         */
        fun parse(lines: Sequence<String>, expectedChars: Int = 0): Lexicon {
            val chars = StringBuilder(expectedChars)
            var starts = IntArray(1024)
            var count = 0
            var last: String? = null
            val outOfOrder = ArrayList<String>()
            for (line in lines) {
                val word = line.trim().lowercase()
                if (word.isEmpty() || word.startsWith("#")) continue
                val order = last?.let { word.compareTo(it) } ?: 1
                when {
                    order < 0 -> outOfOrder.add(word)
                    order > 0 -> {
                        if (count == starts.size) starts = starts.copyOf(count * 2)
                        starts[count++] = chars.length
                        chars.append(word).append('\n')
                        last = word
                    }
                }
            }
            val run = Lexicon(chars.toString(), starts.copyOf(count), emptyList())
            if (outOfOrder.isEmpty()) return run
            return Lexicon(run.chars, run.starts, outOfOrder.distinct().filterNot { run.inRun(it) }.sorted())
        }
    }
}
