package dev.localtype.keyboard

/**
 * Words the keyboard recognizes but never offers: typed on purpose they are left alone, but no bundled list, prior,
 * model or learned count puts them in the strip (one the person added in settings still completes), and the corrector
 * never turns another word into one. The small
 * languages get the same effect from their lists (a `not_offered` word goes only into `words_known_<code>.txt`);
 * English's frequency list is pinned by digest, so its words are named here instead of being moved out of the file.
 */
object UnofferedWords {
    /**
     * Dropped-g spellings from the subtitle frequency list (`words_en.txt`): every entry that ends in "in", whose "-ing"
     * form is a real word and that SCOWL (`words_known.txt`) does not list itself, checked by eye. Names and words that
     * only look like one (jin, min, qin, rin, palin, larkin, tintin) stay offered. `UnofferedWordsTest` holds the rule.
     */
    val ENGLISH: Set<String> = setOf(
        "askin", "bein", "callin", "comin", "cryin", "dancin", "darlin", "doin", "drinkin", "drivin", "eatin",
        "fallin", "feelin", "freakin", "friggin", "gettin", "givin", "goin", "hangin", "havin", "keepin", "kiddin",
        "killin", "leavin", "livin", "lookin", "lovin", "lyin", "makin", "mornin", "morphin", "motherfuckin", "movin",
        "nothin", "playin", "puttin", "rockin", "runnin", "sayin", "seein", "shootin", "singin", "sittin", "smokin",
        "somethin", "startin", "stayin", "talkin", "tellin", "thinkin", "tryin", "waitin", "walkin", "watchin", "workin",
    )

    /** [language]'s unoffered words, compared with normalized (lowercase) words. */
    fun forLanguage(language: LanguageProfile): Set<String> = if (language.english) ENGLISH else emptySet()
}
