package dev.localtype.keyboard

/** Pure helpers for picking the sentence to proofread and showing what changed. */
object Proofreading {
    /** The sentence to check and where it starts in [beforeCursor], or null if there's nothing to check. */
    data class Target(val start: Int, val sentence: String, val trailing: String)

    fun target(beforeCursor: String): Target? {
        val body = beforeCursor.trimEnd()
        if (body.count { it.isLetter() } < 2) return null
        val trailing = beforeCursor.substring(body.length)
        // Start after the previous sentence end (punctuation followed by whitespace) or a line break.
        var start = 0
        for (i in body.length - 2 downTo 0) {
            val c = body[i]
            if (c == '\n' || (c in ".!?" && body[i + 1].isWhitespace())) {
                start = i + 1
                break
            }
        }
        while (start < body.length && body[start].isWhitespace()) start++
        val sentence = body.substring(start)
        return if (sentence.count { it.isLetter() } < 2) null else Target(start, sentence, trailing)
    }

    data class Segment(val text: String, val changed: Boolean)

    /** Word-level diff of [corrected] against [original]; changed runs are marked for highlighting. */
    fun diff(original: String, corrected: String): List<Segment> {
        val a = original.split(' ').filter { it.isNotEmpty() }
        val b = corrected.split(' ').filter { it.isNotEmpty() }
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val segments = mutableListOf<Segment>()
        var i = 0
        var j = 0
        while (j < b.size) {
            if (i < a.size && a[i] == b[j]) {
                segments += Segment(b[j], changed = false); i++; j++
            } else if (i < a.size && lcs[i + 1][j] >= lcs[i][j + 1]) {
                i++ // dropped word; the corrected text simply doesn't include it
            } else {
                segments += Segment(b[j], changed = true); j++
            }
        }
        return segments
    }

    fun changedCount(original: String, corrected: String): Int =
        diff(original, corrected).count { it.changed } + maxOf(0, original.split(' ').count { it.isNotEmpty() } - corrected.split(' ').count { it.isNotEmpty() })
}
