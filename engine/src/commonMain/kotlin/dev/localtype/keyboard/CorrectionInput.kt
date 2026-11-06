package dev.localtype.keyboard

/** Main-thread snapshot consumed on the existing worker. No editor or learning callbacks. */
data class CorrectionInput(
    val typed: String,
    val contextBeforeWord: String,
    val previous: String?,
    val sentenceStart: Boolean,
    val afterComma: Boolean,
    val dictionary: WordDictionary,
    val lexicon: Lexicon,
    val protectedWords: Set<String>,
    val kept: Set<String>,
    val blocked: Set<String>,
    val counts: Map<String, Int>,
    val offsets: List<TouchOffsets.Entry>,
    val touches: List<Touch?>?,
    /** The language typed in: its keys score the slips, and the English-only fixes stay out of others. */
    val language: LanguageProfile = LanguageProfile.ENGLISH,
)
