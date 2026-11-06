package dev.localtype.keyboard

/** What holding a letter offers, on the Android letters layout and on the iPhone keyboard. */
object LetterAccents {
    /**
     * Accented Latin letters first, then IPA symbols that visually or phonetically relate to the
     * letter. Long-press a letter to pick one.
     */
    val ACCENTS: Map<Char, List<String>> = mapOf(
        'a' to listOf("á", "à", "â", "ä", "ã", "å", "ā", "æ", "ɑ", "ɐ", "ə", "ɒ"),
        'b' to listOf("ß", "β", "ɓ", "ʙ"),
        'c' to listOf("ç", "ć", "č", "ĉ", "ɕ"),
        'd' to listOf("ď", "đ", "ð", "ɖ", "ɗ"),
        'e' to listOf("é", "è", "ê", "ë", "ē", "ę", "ė", "ɛ", "ə", "ɘ", "ɜ"),
        'f' to listOf("ƒ", "ɸ"),
        'g' to listOf("ğ", "ĝ", "ġ", "ɡ", "ɣ", "ɠ", "ɢ"),
        'h' to listOf("ĥ", "ħ", "ɦ", "ɥ", "ʜ"),
        'i' to listOf("í", "ì", "î", "ï", "ī", "į", "ı", "ɪ", "ɨ"),
        'j' to listOf("ĵ", "ʝ", "ɟ"),
        'k' to listOf("ķ", "ǩ"),
        'l' to listOf("ł", "ľ", "ĺ", "ļ", "ɫ", "ɬ", "ɭ", "ʟ"),
        'm' to listOf("ɱ"),
        'n' to listOf("ñ", "ń", "ň", "ņ", "ŋ", "ɲ", "ɳ", "ɴ"),
        'o' to listOf("ó", "ò", "ô", "ö", "ő", "õ", "ø", "ō", "œ", "ɔ", "ɵ", "ʊ"),
        'p' to listOf("þ", "ɸ"),
        'q' to listOf("ʔ", "ʕ"),
        'r' to listOf("ř", "ŕ", "ɹ", "ɾ", "ʁ", "ʀ", "ɽ"),
        's' to listOf("ś", "š", "ş", "ș", "ß", "ʃ", "ʂ"),
        't' to listOf("ť", "ţ", "ț", "þ", "θ", "ʈ"),
        'u' to listOf("ú", "ù", "û", "ü", "ű", "ū", "ů", "ų", "ʊ", "ʉ", "ʌ"),
        'v' to listOf("ʋ", "ʌ"),
        'w' to listOf("ŵ", "ʍ", "ɯ"),
        'x' to listOf("χ"),
        'y' to listOf("ý", "ÿ", "ŷ", "ʏ", "ɥ"),
        'z' to listOf("ž", "ź", "ż", "ʒ", "ʐ", "ʑ"),
    )

    /**
     * A hold on [letter] in [language]: the language's own accents first ([LanguageProfile.holdsFirst]:
     * é on e in Spanish), so a still hold types the accent, then [digit] if the key has one, then [ACCENTS].
     * A symbols keyboard with [LanguageProfile.holdsOnly] (IPA) offers its own list and the digit alone.
     */
    fun holds(language: LanguageProfile, letter: Char, digit: List<String> = emptyList()): List<String> {
        if (language.holdsOnly) return (language.holdsFirst[letter].orEmpty() + digit).distinct()
        val first = language.holdsFirst[letter] ?: return digit + ACCENTS[letter].orEmpty()
        return (first + digit + ACCENTS[letter].orEmpty()).distinct()
    }
}
