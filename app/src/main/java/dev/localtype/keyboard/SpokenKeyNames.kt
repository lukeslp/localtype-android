package dev.localtype.keyboard

/**
 * The phone's language for the names TalkBack hears on the keys. [KeyboardLayout] and [KeyboardView] name keys in
 * English, and that English stays the source (the iPhone's names are generated from [KeyboardLayout.spokenName]'s
 * table); this maps each English name the keyboard owns to a string resource, so Spanish and Portuguese phones hear
 * their own words and every other language hears the English, as before. `SpokenKeyNamesTest` holds the English
 * resources equal to these keys.
 *
 * Not translated: letters, IPA names, the Arabic-script marks and the zero-width joiners, ʻokina and Navajo's glottal
 * stop, which pass through unchanged.
 */
object SpokenKeyNames {
    val ids: Map<String, Int> = mapOf(
        // Keys that act.
        "Shift" to R.string.key_name_shift,
        "Shift, on" to R.string.key_name_shift_on,
        "Shift, off" to R.string.key_name_shift_off,
        "Caps lock, on" to R.string.key_name_caps_lock_on,
        "Caps lock, off" to R.string.key_name_caps_lock_off,
        "Delete" to R.string.key_name_delete,
        "Space" to R.string.key_name_space,
        "Return" to R.string.key_name_return,
        "Go" to R.string.key_name_go,
        "Search" to R.string.key_name_search,
        "Send" to R.string.key_name_send,
        "Next" to R.string.key_name_next,
        "Done" to R.string.key_name_done,
        "Back" to R.string.key_name_back,
        "Symbols" to R.string.key_name_symbols,
        "Letters" to R.string.key_name_letters,
        "More symbols" to R.string.key_name_more_symbols,
        "Diacritics" to R.string.key_name_diacritics,
        "Number pad" to R.string.key_name_number_pad,
        "Phone pad" to R.string.key_name_phone_pad,
        "Date and time pad" to R.string.key_name_date_time_pad,
        "Next keyboard" to R.string.key_name_next_keyboard,
        "Voice input" to R.string.key_name_voice_input,
        // Symbols, as KeyboardLayout.spokenName names them.
        "Comma" to R.string.key_name_comma,
        "Period" to R.string.key_name_period,
        "Dot com" to R.string.key_name_dot_com,
        "Dot org" to R.string.key_name_dot_org,
        "Dot net" to R.string.key_name_dot_net,
        "Dot edu" to R.string.key_name_dot_edu,
        "Dot me" to R.string.key_name_dot_me,
        "Apostrophe" to R.string.key_name_apostrophe,
        "Quote" to R.string.key_name_quote,
        "Dash" to R.string.key_name_dash,
        "Underscore" to R.string.key_name_underscore,
        "Slash" to R.string.key_name_slash,
        "Backslash" to R.string.key_name_backslash,
        "Vertical bar" to R.string.key_name_vertical_bar,
        "Ampersand" to R.string.key_name_ampersand,
        "At" to R.string.key_name_at,
        "Hash" to R.string.key_name_hash,
        "Asterisk" to R.string.key_name_asterisk,
        "Plus" to R.string.key_name_plus,
        "Equals" to R.string.key_name_equals,
        "Less than" to R.string.key_name_less_than,
        "Greater than" to R.string.key_name_greater_than,
        "Left parenthesis" to R.string.key_name_left_parenthesis,
        "Right parenthesis" to R.string.key_name_right_parenthesis,
        "Exclamation mark" to R.string.key_name_exclamation_mark,
        "Question mark" to R.string.key_name_question_mark,
        "Opening question mark" to R.string.key_name_opening_question_mark,
        "Opening exclamation mark" to R.string.key_name_opening_exclamation_mark,
        "Colon" to R.string.key_name_colon,
        "Semicolon" to R.string.key_name_semicolon,
        "Percent" to R.string.key_name_percent,
        "Caret" to R.string.key_name_caret,
        "Dollar sign" to R.string.key_name_dollar_sign,
        "Pound sign" to R.string.key_name_pound_sign,
        "Degree sign" to R.string.key_name_degree_sign,
        "Grave accent" to R.string.key_name_grave_accent,
        "Tilde" to R.string.key_name_tilde,
        "Left brace" to R.string.key_name_left_brace,
        "Right brace" to R.string.key_name_right_brace,
        "Left bracket" to R.string.key_name_left_bracket,
        "Right bracket" to R.string.key_name_right_bracket,
        "Left angle quote" to R.string.key_name_left_angle_quote,
        "Right angle quote" to R.string.key_name_right_angle_quote,
        "En dash" to R.string.key_name_en_dash,
        "Em dash" to R.string.key_name_em_dash,
        "Ellipsis" to R.string.key_name_ellipsis,
        "Middle dot" to R.string.key_name_middle_dot,
        "Bullet" to R.string.key_name_bullet,
        "Paragraph sign" to R.string.key_name_paragraph_sign,
        "Euro sign" to R.string.key_name_euro_sign,
        "Yen sign" to R.string.key_name_yen_sign,
        "Cent sign" to R.string.key_name_cent_sign,
        "Copyright sign" to R.string.key_name_copyright_sign,
        "Registered sign" to R.string.key_name_registered_sign,
        "Trademark sign" to R.string.key_name_trademark_sign,
        "Check mark" to R.string.key_name_check_mark,
        "Superscript one" to R.string.key_name_superscript_one,
        "Superscript two" to R.string.key_name_superscript_two,
        "Superscript three" to R.string.key_name_superscript_three,
        "Superscript four" to R.string.key_name_superscript_four,
        "One half" to R.string.key_name_one_half,
        "One third" to R.string.key_name_one_third,
        "Two thirds" to R.string.key_name_two_thirds,
        "One quarter" to R.string.key_name_one_quarter,
        "Three quarters" to R.string.key_name_three_quarters,
        "One eighth" to R.string.key_name_one_eighth,
        "Three eighths" to R.string.key_name_three_eighths,
        "Five eighths" to R.string.key_name_five_eighths,
        "Seven eighths" to R.string.key_name_seven_eighths,
        "Per mille sign" to R.string.key_name_per_mille_sign,
        "Multiplication sign" to R.string.key_name_multiplication_sign,
        "Division sign" to R.string.key_name_division_sign,
        "Square root" to R.string.key_name_square_root,
        "Pi" to R.string.key_name_pi,
        "Delta" to R.string.key_name_delta,
        "Infinity" to R.string.key_name_infinity,
        "Empty set" to R.string.key_name_empty_set,
    )

    /** "Space, Español": the space bar with the language it types, when more than one is on. */
    private const val SPACE_WITH = "Space, "

    /** "Capital A": a letter key with Shift on. */
    private const val CAPITAL = "Capital "

    /**
     * [english] in the phone's language: [text] reads a resource, [format] one with a single %1$s. A name this table
     * doesn't own comes back unchanged.
     */
    fun resolve(english: String, text: (Int) -> String, format: (Int, String) -> String): String {
        ids[english]?.let { return text(it) }
        if (english.startsWith(SPACE_WITH)) return format(R.string.key_name_space_with, english.removePrefix(SPACE_WITH))
        if (english.startsWith(CAPITAL) && english.length > CAPITAL.length) return format(R.string.key_name_capital, english.removePrefix(CAPITAL))
        return english
    }
}
