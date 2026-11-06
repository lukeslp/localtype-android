package dev.localtype.keyboard

/** Missing apostrophes in forms the word lists also recognize as words. */
object Contractions {
    private val ALWAYS = mapOf(
        "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "im" to "i'm", "ive" to "i've", "youre" to "you're", "theyre" to "they're",
        "youve" to "you've", "weve" to "we've", "theyve" to "they've",
        "youll" to "you'll", "theyll" to "they'll",
        "thats" to "that's", "theres" to "there's", "heres" to "here's",
        "cant" to "can't",
        "wont" to "won't",
        "whats" to "what's",
        "hes" to "he's",
        "shes" to "she's",
    )
    private val AMBIGUOUS = mapOf("ill" to "i'll", "id" to "i'd", "lets" to "let's")
    private val CONJUNCTIONS = setOf("and", "but", "so", "then")

    /**
     * [word] and [previous] are normalized like dictionary words. Ambiguous forms are offered
     * everywhere, but only applied at a sentence start, after a comma or after a conjunction.
     * Other real words, including "its", "were", "well", "shell" and "hell", have no entry.
     */
    fun propose(
        word: String,
        previous: String?,
        sentenceStart: Boolean,
        afterComma: Boolean = false,
    ): Corrector.Correction? {
        ALWAYS[word]?.let { return Corrector.Correction(it, automatic = true) }
        val fix = AMBIGUOUS[word] ?: return null
        return Corrector.Correction(fix, automatic = sentenceStart || afterComma || previous in CONJUNCTIONS)
    }
}
