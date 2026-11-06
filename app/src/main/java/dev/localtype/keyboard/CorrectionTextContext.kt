package dev.localtype.keyboard

/** Same context rules as AdaptiveNgramModel, scanning only the two trailing word spans. */
internal data class CorrectionTextContext(val previous: String?, val sentenceStart: Boolean) {
    companion object {
        fun capture(typedContext: String): CorrectionTextContext {
            var end = wordStart(typedContext, typedContext.length) ?: typedContext.length
            while (end > 0 && typedContext[end - 1] in " \t") end--
            val sentenceStart = end == 0 || typedContext[end - 1] in ".!?\n"
            // Preserve the existing UTF-16 last-character guard, including supplementary letters.
            val previous = if (end > 0 && typedContext[end - 1].isLetter()) {
                wordStart(typedContext, end)?.let { AdaptiveNgramModel.normalize(typedContext.substring(it, end)) }
            } else null
            return CorrectionTextContext(previous, sentenceStart)
        }

        private fun wordStart(text: String, end: Int): Int? {
            var begin = end
            while (begin > 0) {
                val cp = Character.codePointBefore(text, begin)
                if (!Character.isLetter(cp) && cp != '\''.code && cp != '’'.code && cp != '-'.code) break
                begin -= Character.charCount(cp)
            }
            // A word must begin with a letter; leading quotes and hyphens are not part of it.
            while (begin < end) {
                val cp = Character.codePointAt(text, begin)
                if (Character.isLetter(cp)) return begin
                begin += Character.charCount(cp)
            }
            return null
        }
    }
}
