package dev.localtype.keyboard

/**
 * What one language needs from the shared engine (docs/languages-route-a-2026-10-17.md): its
 * letters, its letter keys, which letters differ only by an accent, and its word lists. [ENGLISH]
 * reproduces the keyboard as it was before languages existed. [SPANISH] is experimental: its lists
 * have not been read by a native speaker, and the English-only parts of the keyboard (the next-word
 * model, proofreading, swipe, the contraction and article rules, the typing log) stay off while it
 * is chosen; [english] is the switch they check. [IPA] is a keyboard rather than a language: phonetic
 * symbols with no words, so nothing is suggested, corrected, capitalized or learned while it's chosen.
 */
class LanguageProfile private constructor(
    val code: String,
    /** The language's name in itself, for the chooser and the space bar. */
    val nativeName: String,
    /** Every letter a word may hold, in the order the corrector tries them as replacements. */
    letters: String,
    /** The letter keys, top row first, as the letters layout draws them. */
    val keyRows: List<String>,
    /** An accented letter and its bare letter. Empty for English, so nothing folds there. */
    val folds: Map<Char, Char>,
    /** What a hold on a letter offers before anything else, ahead of a top-row digit: "é" on e in Spanish. */
    val holdsFirst: Map<Char, List<String>>,
    /** Marks that open a question or exclamation ("¿", "¡"): a sentence still starts after them. */
    val openingMarks: String,
    /** The one-letter words the keyboard may offer and learn: "a" and "i" in English, "y", "o", "a", "e", "u" in Spanish. */
    val oneLetterWords: Set<String>,
    /** The suggestion list (`word<TAB>count`), its swear words (offered only with Suggest swear words), and every recognized word. */
    val wordsAsset: String,
    val profanityAsset: String,
    val lexiconAsset: String,
    /**
     * The corpus prior, if the language has one that cleared its bar (decision 3), and the weight it ships at
     * (`AdaptiveNgramModel.PriorWeights.weight`; floor and fade stay the defaults). Plain values, so the profile
     * needs nothing from the engine and the editor fixture can copy it alone.
     */
    val priorAsset: String?,
    val priorWeight: Float,
    /** False for a symbols keyboard ([IPA]): no word lists, and nothing suggested, corrected, capitalized or learned. */
    val words: Boolean = true,
    /** A row of keys above the letters ([IPA]'s commonest symbols); empty for a language. */
    val topRow: String = "",
    /** True when [holdsFirst] is the whole of a hold, in place of the accented letters ([IPA]'s related symbols). */
    val holdsOnly: Boolean = false,
    /**
     * A script with no capitals ([HEBREW]): no Shift key, Delete ends the top row, and the bottom row is all letters,
     * centered. The top row's letters start at its left edge, as [LetterGeometry.fromRows] expects.
     */
    val shiftless: Boolean = false,
    /** Written right to left ([HEBREW]); the editor lays the text out, so this only names the language's direction. */
    val rightToLeft: Boolean = false,
) {
    val letters: CharArray = letters.toCharArray()

    /** The language every English-only rule and model was built for. */
    val english: Boolean get() = this === ENGLISH

    /** Where the letter keys sit, as the letters layout draws them: [topRow], if there is one, above [keyRows]. */
    val geometry: LetterGeometry by lazy { LetterGeometry.fromRows(if (topRow.isEmpty()) keyRows else listOf(topRow) + keyRows) }

    /** The letter with any accent removed: "é" → "e", "ñ" → "n"; any other character is itself. */
    fun fold(c: Char): Char = folds[c] ?: c

    fun fold(word: String): String = if (folds.isEmpty()) word else buildString(word.length) { word.forEach { append(fold(it)) } }

    /**
     * The language a speech recognizer is asked for: the first of the phone's own languages, as BCP 47 tags,
     * that is this one with a region ("es-MX" on a Mexican phone), else [DEFAULT_SPEECH]'s. English stays
     * "en-US", as before.
     */
    fun speechTag(phoneLanguageTags: List<String>): String =
        if (english || !words) DEFAULT_SPEECH.getValue(ENGLISH.code)
        else phoneLanguageTags.firstOrNull { tag ->
            val parts = tag.split('-')
            parts.first() == code && parts.drop(1).any { (it.length == 2 && it.all(Char::isLetter)) || (it.length == 3 && it.all(Char::isDigit)) }
        } ?: DEFAULT_SPEECH[code] ?: DEFAULT_SPEECH.getValue(ENGLISH.code)

    override fun toString(): String = "LanguageProfile($code)"

    companion object {
        private val MACRONS = mapOf('ā' to 'a', 'ē' to 'e', 'ī' to 'i', 'ō' to 'o', 'ū' to 'u')
        private val MACRON_HOLDS = mapOf('a' to listOf("ā"), 'e' to listOf("ē"), 'i' to listOf("ī"), 'o' to listOf("ō"), 'u' to listOf("ū"))

        private fun welshFolds(): Map<Char, Char> = buildMap {
            listOf('a' to "âáàä", 'e' to "êéèë", 'i' to "îíìï", 'o' to "ôóòö", 'u' to "ûúùü", 'w' to "ŵẃẁẅ", 'y' to "ŷýỳÿ")
                .forEach { (bare, marked) -> marked.forEach { put(it, bare) } }
        }

        private val DEFAULT_SPEECH = mapOf("en" to "en-US", "es" to "es-ES", "pt" to "pt-BR", "he" to "he-IL",
            "cy" to "cy-GB", "ga" to "ga-IE", "mi" to "mi-NZ", "haw" to "haw-US")

        val ENGLISH = LanguageProfile(
            code = "en",
            nativeName = "English",
            letters = "abcdefghijklmnopqrstuvwxyz",
            keyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            folds = emptyMap(),
            holdsFirst = emptyMap(),
            openingMarks = "",
            oneLetterWords = setOf("a", "i"),
            wordsAsset = "words_en.txt",
            profanityAsset = "words_en_profanity.txt",
            lexiconAsset = "words_known.txt",
            priorAsset = "corpus_prior.bin",
            priorWeight = 20f,
        )

        /** Spanish QWERTY as Gboard and AOSP draw it, ñ on the home row; the acute vowels and ü are held. */
        val SPANISH = LanguageProfile(
            code = "es",
            nativeName = "Español",
            letters = "abcdefghijklmnopqrstuvwxyzñáéíóúü",
            keyRows = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
            folds = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n'),
            holdsFirst = mapOf('a' to listOf("á"), 'e' to listOf("é"), 'i' to listOf("í"), 'o' to listOf("ó"), 'u' to listOf("ú", "ü")),
            openingMarks = "¿¡",
            oneLetterWords = setOf("a", "e", "o", "u", "y"),
            wordsAsset = "words_es.txt",
            profanityAsset = "words_es_profanity.txt",
            lexiconAsset = "words_known_es.txt",
            // Cleared its bar on the test split on 2026-10-18 at weight 80, the weight dev chose
            // (docs/languages-route-a-2026-10-17.md, "The Spanish prior's protocol").
            priorAsset = "corpus_prior_es.bin",
            priorWeight = 80f,
        )

        /**
         * The International Phonetic Alphabet, for transcription: the QWERTY letters (most are IPA symbols themselves),
         * a row of the commonest other symbols above them, and on each letter's hold the symbols related to it. The
         * comma key types the primary stress mark, with the other stress, length and superscript marks and the main
         * combining diacritics on its hold.
         */
        val IPA = LanguageProfile(
            code = "ipa",
            nativeName = "IPA",
            letters = "abcdefghijklmnopqrstuvwxyz",
            keyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            folds = emptyMap(),
            holdsFirst = mapOf(
                // The top row's own holds.
                'ə' to listOf("ɚ", "ɘ", "ɜ", "ɝ", "ɞ"),
                'ɪ' to listOf("ɨ", "ʏ"),
                'ʊ' to listOf("ʉ", "ɯ"),
                'ɛ' to listOf("œ", "ɜ", "ɝ"),
                'æ' to listOf("ɐ", "a", "ɶ"),
                'ɑ' to listOf("ɒ", "ʌ"),
                'ɔ' to listOf("ɒ", "ɤ"),
                'ʃ' to listOf("ʒ", "ʂ", "ʐ", "ɕ", "ʑ"),
                'θ' to listOf("ð"),
                'ŋ' to listOf("ɲ", "ɴ", "ɳ"),
                // The letters: the symbols each one is the root of, or sounds like.
                'q' to listOf("ʔ", "ʕ", "ɢ"),
                'w' to listOf("ʍ", "ɯ", "ɰ", "ʷ"),
                'e' to listOf("ə", "ɛ", "ɘ", "ɜ", "ɞ", "ɤ", "ø", "œ"),
                'r' to listOf("ɹ", "ɾ", "ʁ", "ʀ", "ɽ", "ɻ", "ɺ"),
                't' to listOf("θ", "ʈ", "t͡ʃ", "t͡s"),
                'y' to listOf("ʏ", "ɥ", "ʎ", "ʝ"),
                'u' to listOf("ʊ", "ʉ", "ɯ", "ʌ"),
                'i' to listOf("ɪ", "ɨ", "ʏ"),
                'o' to listOf("ɔ", "ø", "ɵ", "œ", "ɶ", "ɒ", "ɤ"),
                'p' to listOf("ɸ", "ʘ"),
                'a' to listOf("æ", "ɑ", "ɐ", "ɒ", "ʌ", "ɶ"),
                's' to listOf("ʃ", "ʂ", "ɕ"),
                'd' to listOf("ð", "ɖ", "ɗ", "d͡ʒ", "d͡z"),
                'f' to listOf("ɸ"),
                'g' to listOf("ɡ", "ɣ", "ɢ", "ɠ", "ʛ"),
                'h' to listOf("ħ", "ɦ", "ʜ", "ɧ", "ʰ"),
                'j' to listOf("ʝ", "ɟ", "ʄ"),
                'k' to listOf("ʞ", "ǀ", "ǁ", "ǃ", "ǂ"),
                'l' to listOf("ɫ", "ɬ", "ɮ", "ɭ", "ʟ", "ʎ"),
                'z' to listOf("ʒ", "ʐ", "ʑ"),
                'x' to listOf("χ"),
                'c' to listOf("ç", "ɕ"),
                'v' to listOf("ʋ", "ⱱ", "ʌ"),
                'b' to listOf("β", "ɓ", "ʙ"),
                'n' to listOf("ŋ", "ɲ", "ɳ", "ɴ", "ɱ"),
                'm' to listOf("ɱ"),
            ),
            openingMarks = "",
            oneLetterWords = emptySet(),
            wordsAsset = "",
            profanityAsset = "",
            lexiconAsset = "",
            priorAsset = null,
            priorWeight = 0f,
            words = false,
            topRow = "əɪʊɛæɑɔʃθŋ",
            holdsOnly = true,
        )

        /**
         * Brazilian Portuguese first, experimental like Spanish: Android's Portuguese layout (plain QWERTY, no ç key), with ç
         * first on a hold of c and each vowel's accents first on its hold, in AOSP's Portuguese order. Its lists have not
         * been read by a native speaker. Its corpus prior cleared the bar written before it on the test split, at the weight
         * chosen on dev (decision 3, 2026-10-18).
         */
        val PORTUGUESE = LanguageProfile(
            code = "pt",
            nativeName = "Português",
            letters = "abcdefghijklmnopqrstuvwxyzáàâãçéêíóôõú",
            keyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            folds = mapOf('á' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'ç' to 'c', 'é' to 'e', 'ê' to 'e', 'í' to 'i',
                'ó' to 'o', 'ô' to 'o', 'õ' to 'o', 'ú' to 'u'),
            holdsFirst = mapOf('a' to listOf("á", "ã", "à", "â"), 'c' to listOf("ç"), 'e' to listOf("é", "ê"), 'i' to listOf("í"),
                'o' to listOf("ó", "õ", "ô"), 'u' to listOf("ú")),
            openingMarks = "",
            oneLetterWords = setOf("a", "e", "o", "é", "à"),
            wordsAsset = "words_pt.txt",
            profanityAsset = "words_pt_profanity.txt",
            lexiconAsset = "words_known_pt.txt",
            priorAsset = "corpus_prior_pt.bin",
            priorWeight = 80f,
        )

        /**
         * Hebrew, experimental like Spanish and Portuguese, in the Israeli standard layout as Gboard draws it: eight letters
         * and Delete on top, ten on the home row, nine below, with no Shift since Hebrew has no capitals. Its lists come
         * straight from subtitle counts ([model/hebrew_lists.py]) and have not been read by a native speaker; it has no
         * corpus prior yet, so suggestions come from the list and from the person's own typing. Asked for on 2026-10-22.
         */
        val HEBREW = LanguageProfile(
            code = "he",
            nativeName = "עברית",
            letters = "אבגדהוזחטיכךלמםנןסעפףצץקרשת",
            keyRows = listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ"),
            folds = emptyMap(),
            holdsFirst = emptyMap(),
            openingMarks = "",
            oneLetterWords = emptySet(),
            wordsAsset = "words_he.txt",
            profanityAsset = "words_he_profanity.txt",
            lexiconAsset = "words_known_he.txt",
            priorAsset = null,
            priorWeight = 0f,
            shiftless = true,
            rightToLeft = true,
        )

        /**
         * Welsh, experimental (2026-11-03): QWERTY as in Britain, with the to bach (â ê î ô û ŵ ŷ) first on each vowel's
         * hold, then the acute, grave and diaeresis, as Gboard's Welsh does. Lists from Welsh Wikipedia
         * (`model/small_language_lists.py`), not read by a native speaker; no corpus prior.
         */
        val WELSH = LanguageProfile(
            code = "cy",
            nativeName = "Cymraeg",
            letters = "abcdefghijklmnopqrstuvwxyzâêîôûŵŷáéíóúẃýàèìòùẁỳäëïöüẅÿ",
            keyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            folds = welshFolds(),
            holdsFirst = mapOf(
                'a' to listOf("â", "á", "à", "ä"), 'e' to listOf("ê", "é", "è", "ë"), 'i' to listOf("î", "í", "ì", "ï"),
                'o' to listOf("ô", "ó", "ò", "ö"), 'u' to listOf("û", "ú", "ù", "ü"), 'w' to listOf("ŵ", "ẃ", "ẁ", "ẅ"),
                'y' to listOf("ŷ", "ý", "ỳ", "ÿ"),
            ),
            openingMarks = "",
            oneLetterWords = setOf("a", "i", "y", "o", "â", "e"),
            wordsAsset = "words_cy.txt",
            profanityAsset = "words_cy_profanity.txt",
            lexiconAsset = "words_known_cy.txt",
            priorAsset = null,
            priorWeight = 0f,
        )

        /** Irish, experimental: QWERTY with each vowel's fada (á é í ó ú) first on its hold. Lists from Irish Wikipedia. */
        val IRISH = LanguageProfile(
            code = "ga",
            nativeName = "Gaeilge",
            letters = "abcdefghijklmnopqrstuvwxyzáéíóú",
            keyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            folds = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u'),
            holdsFirst = mapOf('a' to listOf("á"), 'e' to listOf("é"), 'i' to listOf("í"), 'o' to listOf("ó"), 'u' to listOf("ú")),
            openingMarks = "",
            oneLetterWords = setOf("a", "i", "é", "í", "ó"),
            wordsAsset = "words_ga.txt",
            profanityAsset = "words_ga_profanity.txt",
            lexiconAsset = "words_known_ga.txt",
            priorAsset = null,
            priorWeight = 0f,
        )

        /** Māori, experimental: QWERTY with each vowel's macron (ā ē ī ō ū) first on its hold. Lists from Māori Wikipedia. */
        val MAORI = LanguageProfile(
            code = "mi",
            nativeName = "Māori",
            letters = "abcdefghijklmnopqrstuvwxyzāēīōū",
            keyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            folds = MACRONS,
            holdsFirst = MACRON_HOLDS,
            openingMarks = "",
            oneLetterWords = setOf("a", "e", "i", "o", "ā", "ō"),
            wordsAsset = "words_mi.txt",
            profanityAsset = "words_mi_profanity.txt",
            lexiconAsset = "words_known_mi.txt",
            priorAsset = null,
            priorWeight = 0f,
        )

        /**
         * Hawaiian, experimental: QWERTY with the ʻokina (U+02BB, a letter) at the end of the home row, where Apple's
         * Hawaiian layout puts it beside L, and each vowel's kahakō (ā ē ī ō ū) first on its hold. Lists from the Hawaiian
         * Corpus Project's counts of 10.7 million words from Ulukau (CC0). The ʻokina is a consonant, so it never folds.
         */
        val HAWAIIAN = LanguageProfile(
            code = "haw",
            nativeName = "ʻŌlelo Hawaiʻi",
            letters = "abcdefghijklmnopqrstuvwxyzʻāēīōū",
            keyRows = listOf("qwertyuiop", "asdfghjklʻ", "zxcvbnm"),
            folds = MACRONS,
            holdsFirst = MACRON_HOLDS,
            openingMarks = "",
            oneLetterWords = setOf("a", "e", "i", "o", "ā"),
            wordsAsset = "words_haw.txt",
            profanityAsset = "words_haw_profanity.txt",
            lexiconAsset = "words_known_haw.txt",
            priorAsset = null,
            priorWeight = 0f,
        )

        /** The languages with words: each has its own lists, learning and Settings word cards. */
        val WITH_WORDS: List<LanguageProfile> = listOf(ENGLISH, SPANISH, PORTUGUESE, HEBREW, WELSH, IRISH, MAORI, HAWAIIAN)

        val ALL: List<LanguageProfile> = WITH_WORDS + IPA

        fun of(code: String?): LanguageProfile? = ALL.firstOrNull { it.code == code }
    }
}
