package dev.localtype.keyboard

import android.text.InputType

/** What a key does. Character keys carry their text; everything else is a command. */
sealed interface KeyAction {
    data class Text(val value: String) : KeyAction
    data object Shift : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object ToSymbols : KeyAction
    data object ToMoreSymbols : KeyAction
    data object ToLetters : KeyAction
    data object ToFieldLayout : KeyAction
    data object SwitchKeyboard : KeyAction
    /** Voice input, on the keys when Settings puts it there ([VoiceKey.KEYS]): it never edits text itself. */
    data object Voice : KeyAction
}

enum class KeyStyle { CHARACTER, MODIFIER, ACCENT, SPACE }

data class Key(
    val label: String,
    val action: KeyAction,
    val width: Float = 1f,
    val style: KeyStyle = KeyStyle.CHARACTER,
    /** Offered in a popup on long press; [holdHint] shows the first shortcut in the corner. */
    val alternates: List<String> = emptyList(),
    val spokenLabel: String = label,
)

/**
 * Where the voice key goes when voice input is offered: only in the tools row ([OFF], as from 0.5.38), in the strip
 * beside the tools handle ([STRIP]), or on the keys between the period and Return ([KEYS]).
 */
enum class VoiceKey {
    OFF, STRIP, KEYS;

    companion object {
        fun of(name: String?): VoiceKey = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/** How wide the space bar is: as standard, wider with narrower side keys, or widest with no comma key. */
enum class SpaceBar {
    STANDARD, WIDER, WIDEST;

    companion object {
        fun of(name: String?): SpaceBar = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/** Rows of keys; [rowHeights] scales each row against the standard key height. */
data class KeyboardLayout(val rows: List<List<Key>>, val rowHeights: List<Float> = List(rows.size) { 1f }) {
    enum class Mode { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMBER, PHONE, DATETIME }

    companion object {
        const val ROW_UNITS = 10f

        /** The number row is a little shorter than the letter rows, as on most phone keyboards. */
        const val NUMBER_ROW_HEIGHT = 0.8f

        fun build(
            mode: Mode,
            showSwitchKey: Boolean,
            numberRow: Boolean = false,
            inputType: Int = InputType.TYPE_CLASS_TEXT,
            language: LanguageProfile = LanguageProfile.ENGLISH,
            /** Drawn on the space bar when more than one language is on: the language being typed. */
            spaceLabel: String = "",
            /** How much of the bottom row the space bar takes ([KeyboardSettings.spaceBar]). */
            spaceBar: SpaceBar = SpaceBar.STANDARD,
            /** Preferred currency: £ takes $'s place on the symbols page and holds, and $ moves to £'s. */
            pound: Boolean = false,
            /** A voice key between the period and Return ([VoiceKey.KEYS], where voice input is offered). */
            voiceKey: Boolean = false,
            /** Common symbols first on Latin letter holds; existing accents and IPA remain in each popup. */
            commonSymbolHolds: Boolean = false,
        ): KeyboardLayout = when (mode) {
            Mode.LETTERS -> letters(showSwitchKey, digitsOnHold = !numberRow, inputType = inputType, language = language, spaceLabel = spaceLabel,
                commonSymbolHolds = commonSymbolHolds, pound = pound)
                .let { if (numberRow) withNumberRow(it, pound, language.digits) else it }.withVoiceKey(voiceKey).withSpaceBar(spaceBar)
            Mode.SYMBOLS -> symbols(showSwitchKey, inputType, language, spaceLabel, pound).withVoiceKey(voiceKey).withSpaceBar(spaceBar)
            Mode.MORE_SYMBOLS -> moreSymbols(showSwitchKey, inputType, language, spaceLabel, pound).withVoiceKey(voiceKey).withSpaceBar(spaceBar)
            Mode.NUMBER -> numberPad(showSwitchKey, inputType)
            Mode.PHONE -> phonePad(showSwitchKey)
            Mode.DATETIME -> dateTimePad(showSwitchKey, inputType)
        }

        /** Width the layer key and Return keep beside a wider space bar, in key widths (1.5 as standard). */
        const val NARROW_SIDE_KEY = 1.25f

        /**
         * Gives the space bar more of the bottom row. [SpaceBar.WIDER] narrows the layer key and Return to
         * [NARROW_SIDE_KEY]; [SpaceBar.WIDEST] also drops the plain comma key, whose comma stays on the period's
         * hold. Field keys (@, /, .com, a pad's 123, the IPA stress mark) always stay. The space bar takes what's left,
         * so the row still adds up to [ROW_UNITS]. Asked for twice by someone who kept landing on n instead of space.
         */
        internal fun KeyboardLayout.withSpaceBar(spaceBar: SpaceBar): KeyboardLayout {
            if (spaceBar == SpaceBar.STANDARD || rows.isEmpty()) return this
            val bottom = rows.last()
            if (bottom.none { it.action == KeyAction.Space }) return this
            val kept = bottom.filterNot { spaceBar == SpaceBar.WIDEST && it.action == KeyAction.Text(",") }.map {
                when (it.action) {
                    KeyAction.ToSymbols, KeyAction.ToLetters, KeyAction.Enter -> it.copy(width = minOf(it.width, NARROW_SIDE_KEY))
                    else -> it
                }
            }
            val others = kept.filter { it.action != KeyAction.Space }.sumOf { it.width.toDouble() }.toFloat()
            val row = kept.map { if (it.action == KeyAction.Space) it.copy(width = ROW_UNITS - others) else it }
            return copy(rows = rows.dropLast(1) + listOf(row))
        }

        /**
         * Puts a standard-width voice key right before Return in the bottom row, taken from the space bar, so the row
         * still adds up to [ROW_UNITS]. A row with no space bar is left alone (the pads never get one).
         */
        internal fun KeyboardLayout.withVoiceKey(voiceKey: Boolean): KeyboardLayout {
            if (!voiceKey || rows.isEmpty()) return this
            val bottom = rows.last()
            val space = bottom.indexOfFirst { it.action == KeyAction.Space }
            val enter = bottom.indexOfFirst { it.action == KeyAction.Enter }
            if (space < 0 || enter < 0) return this
            val mic = Key("", KeyAction.Voice, VOICE_KEY_WIDTH, KeyStyle.MODIFIER, spokenLabel = "Voice input")
            val row = bottom.mapIndexed { i, key -> if (i == space) key.copy(width = key.width - VOICE_KEY_WIDTH) else key }.toMutableList()
            row.add(enter, mic)
            return copy(rows = rows.dropLast(1) + listOf(row))
        }

        const val VOICE_KEY_WIDTH = 1f

        /** Four rows of three large cells, with commands kept in their own row below the grid. */
        private fun pad(rows: List<List<Key>>, showSwitchKey: Boolean) = KeyboardLayout(
            rows.map { row -> row.map { it.copy(width = ROW_UNITS / 3f) } } + listOf(buildList {
                add(Key("?123", KeyAction.ToSymbols, 3f, KeyStyle.MODIFIER, spokenLabel = "Symbols"))
                if (showSwitchKey) add(Key("🌐", KeyAction.SwitchKeyboard, 1f, KeyStyle.MODIFIER, spokenLabel = "Next keyboard"))
                add(backspace().copy(width = if (showSwitchKey) 3f else 4f))
                add(Key("↵", KeyAction.Enter, 3f, KeyStyle.ACCENT, spokenLabel = "Return"))
            }),
            listOf(1f, 1f, 1f, 1f, NUMBER_ROW_HEIGHT),
        )

        private fun numberPad(showSwitchKey: Boolean, inputType: Int): KeyboardLayout {
            val number = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER
            val signed = number && inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0
            val decimal = number && inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0
            // A web number box is always decimal and never signed, though it takes negative values, so a decimal field
            // without its own minus key keeps one on the hold of 0. Digits-only boxes (PINs, one-time codes) and number
            // passwords get no hold, so their pad stays plain.
            val minusOnZero = decimal && !signed && !TypingRules.isSensitive(inputType)
            return pad(
                listOf("123", "456", "789").map { row -> row.map { char(it.toString()) } } +
                    listOf(listOf(
                        if (signed) char("-") else spacer(1f),
                        char("0", if (minusOnZero) listOf("-") else emptyList()),
                        // The comma is the decimal mark in much of the world; a field that only takes the period drops it.
                        if (decimal) char(".", listOf(",")) else spacer(1f),
                    )),
                showSwitchKey,
            )
        }

        /**
         * Digits with the separators a date or a time is written with, in the two cells beside 0, by the field's
         * variation: a date gets "/" (holding "." and "-") and "-"; a time gets ":" alone; a date and time gets "/"
         * (holding "-" and ".") and ":". Letters for am and pm, and a space, are a layer away (ABC, ?123), and the
         * symbols page comes back here after a space.
         */
        private fun dateTimePad(showSwitchKey: Boolean, inputType: Int): KeyboardLayout {
            val (left, right) = when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_DATETIME_VARIATION_DATE -> char("/", listOf(".", "-")) to char("-", listOf("."))
                InputType.TYPE_DATETIME_VARIATION_TIME -> char(":") to spacer(1f)
                else -> char("/", listOf("-", ".")) to char(":")
            }
            return pad(
                listOf("123", "456", "789").map { row -> row.map { char(it.toString()) } } + listOf(listOf(left, char("0"), right)),
                showSwitchKey,
            )
        }

        /** One alternate per key keeps every phone symbol available through TalkBack's hold action too. */
        private fun phonePad(showSwitchKey: Boolean): KeyboardLayout {
            val holds = mapOf('0' to "+", '1' to "*", '3' to "#", '7' to "(", '9' to ")")
            fun digit(c: Char) = char(c.toString(), listOfNotNull(holds[c]))
            return pad(
                listOf("123", "456", "789").map { row -> row.map(::digit) } +
                    listOf(listOf(spacer(1f), digit('0'), spacer(1f))),
                showSwitchKey,
            )
        }

        /**
         * 1–0 above the letters. Holding a number gives the symbol that shares its key on a hardware
         * keyboard first (1 → !), then superscripts and fractions.
         */
        private fun withNumberRow(letters: KeyboardLayout, pound: Boolean = false, digits: String = "") = KeyboardLayout(
            listOf("1234567890".mapIndexed { index, digit ->
                // A script's own digit comes first on the hold ("۱" on 1 in Persian), ahead of the symbol.
                char(digit.toString(), listOfNotNull(digits.getOrNull(index)?.toString()) +
                    NUMBER_ALTERNATES[digit].orEmpty().let { if (pound && digit == '4') listOf("£") + it else it })
            }) + letters.rows,
            listOf(NUMBER_ROW_HEIGHT) + letters.rowHeights,
        )

        private val NUMBER_ALTERNATES: Map<Char, List<String>> = mapOf(
            '1' to listOf("!", "¹", "½", "⅓", "¼", "⅛"),
            '2' to listOf("@", "²", "⅔"),
            '3' to listOf("#", "³", "¾", "⅜"),
            '4' to listOf("$", "⁴"),
            '5' to listOf("%", "‰", "⅝"),
            '6' to listOf("^"),
            '7' to listOf("&", "⅞"),
            '8' to listOf("*", "∞"),
            '9' to listOf("("),
            '0' to listOf(")", "°", "ⁿ", "∅"),
        )

        /**
         * The language's three letter rows. With [digitsOnHold], holding a top-row letter types its
         * digit; off when a number row shows. The language's own accents come before the digit
         * ([LanguageProfile.holdsFirst]: é on e in Spanish), so a still hold types the accent. Common-symbol
         * mode overrides that order only when explicitly enabled, keeping every accent after it. A short
         * home row is centered with spacers, and Shift and Delete share what the bottom row's letters
         * leave, so English keeps its half-key and key-and-a-half indents, which [LetterGeometry.from]
         * reads back for the corrector.
         */
        private fun letters(showSwitchKey: Boolean, digitsOnHold: Boolean = true, inputType: Int, language: LanguageProfile, spaceLabel: String,
                            commonSymbolHolds: Boolean = false, pound: Boolean = false) = KeyboardLayout(
            language.keyRows.let { (top, home, bottom) ->
                // Eleven letters across (Arabic script) make each key that much narrower; ten leave it one unit wide.
                val unit = ROW_UNITS / language.columns
                val homePad = (language.columns - home.length) / 2f * unit
                val bottomPad = (language.columns - bottom.length) / 2f * unit
                fun letter(c: Char, alternates: List<String>) = char(c.toString(), alternates).let { if (unit == 1f) it else it.copy(width = unit) }
                fun holds(c: Char, digit: List<String> = emptyList()): List<String> {
                    val accents = LetterAccents.holds(language, c, digit)
                    val shortcut = if (c in TOP_ROW_SYMBOL_HOLDS) digit.firstOrNull() ?: TOP_ROW_SYMBOL_HOLDS[c] else COMMON_SYMBOL_HOLDS[c]
                    val symbol = shortcut?.takeIf { commonSymbolHolds && language.words && !language.holdsOnly }
                        ?.let { if (it == "$" && pound) "£" else it }
                    return if (symbol == null) accents else (listOf(symbol) + accents).distinct()
                }
                // A symbols keyboard's own row above the letters (IPA's commonest symbols), the width of the top row.
                val above = if (language.topRow.isEmpty()) emptyList() else listOf(language.topRow.map { char(it.toString(), holds(it)) })
                val topLetters = top.mapIndexed { index, c ->
                    // The script's own digit follows the Western one on the hold ("1", then "۱").
                    val digit = if (digitsOnHold && index < 10) listOfNotNull(((index + 1) % 10).toString(), language.digits.getOrNull(index)?.toString()) else emptyList()
                    letter(c, holds(c, digit))
                }
                above + listOf(
                    // A script with no capitals has no Shift: Delete takes the rest of the top row instead.
                    if (language.shiftless && top.length < language.columns) topLetters + backspace().copy(width = ROW_UNITS - top.length * unit) else topLetters,
                    (if (homePad > 0f) listOf(spacer(homePad)) else emptyList()) +
                        home.map { letter(it, holds(it)) } +
                        (if (homePad > 0f) listOf(spacer(homePad)) else emptyList()),
                    // With the top row full (Arabic script), Delete ends the bottom row and its letters start at the left edge.
                    if (language.deleteEndsBottomRow) bottom.map { letter(it, holds(it)) } + backspace().copy(width = ROW_UNITS - bottom.length * unit)
                    else if (language.shiftless) (if (bottomPad > 0f) listOf(spacer(bottomPad)) else emptyList()) +
                        bottom.map { letter(it, holds(it)) } + (if (bottomPad > 0f) listOf(spacer(bottomPad)) else emptyList())
                    else listOf(Key("⇧", KeyAction.Shift, bottomPad, KeyStyle.MODIFIER, spokenLabel = "Shift")) +
                        bottom.map { letter(it, holds(it)) } + backspace().copy(width = bottomPad),
                    bottomRow(Key("?123", KeyAction.ToSymbols, 1.5f, KeyStyle.MODIFIER, spokenLabel = "Symbols"), showSwitchKey, inputType, language, spaceLabel),
                )
            },
        )

        /** Home and bottom assignments stay fixed; the top row uses symbols when digits have their own row. */
        private val COMMON_SYMBOL_HOLDS = mapOf(
            'a' to "@", 's' to "#", 'd' to "$", 'f' to "_", 'g' to "&", 'h' to "-", 'j' to "+", 'k' to "(", 'l' to ")",
            'z' to "*", 'x' to "\"", 'c' to "'", 'v' to ":", 'b' to ";", 'n' to "!", 'm' to "?",
        )

        private val TOP_ROW_SYMBOL_HOLDS = "qwertyuiop".toList().zip(listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}")).toMap()

        private fun symbols(showSwitchKey: Boolean, inputType: Int, language: LanguageProfile, spaceLabel: String,
                            pound: Boolean = false) = KeyboardLayout(
            listOf(
                "1234567890".map { char(it.toString()) },
                listOf("@", "#", if (pound) "£" else "$", "_", "&", "-", "+", "(", ")", "/").map { char(it) },
                listOf(if (!language.words) Key("◌̃", KeyAction.ToMoreSymbols, 1.5f, KeyStyle.MODIFIER, spokenLabel = "Diacritics")
                    else Key("=\\<", KeyAction.ToMoreSymbols, 1.5f, KeyStyle.MODIFIER, spokenLabel = "More symbols")) +
                    listOf("*", "\"", "'", ":", ";", "!", "?").map { char(it, language.openingMark(it)) } + backspace(),
                bottomRow(abc(), showSwitchKey, inputType, language, spaceLabel),
            ).let(::withoutRepeatedHolds),
        )

        /**
         * On the ?123 page the comma's and period's holds offered @ & - ' " ? ! ; :, which are already keys there
         * (an audit, November 7, 2026). There they hold what the page lacks instead: dashes and angle quotes on the
         * comma, a middle dot and a bullet on the period. The period keeps its comma, which a widest space bar drops.
         */
        private fun withoutRepeatedHolds(rows: List<List<Key>>): List<List<Key>> {
            val onPage = rows.flatten().mapNotNullTo(HashSet()) { (it.action as? KeyAction.Text)?.value }
            val extras = mapOf("," to listOf("–", "—", "«", "»"), "." to listOf("·", "•"))
            return rows.map { row ->
                row.map { key ->
                    val more = extras[key.label] ?: return@map key
                    // Only a single repeated mark goes: a mail field's .com, .org and .net stay first on the period.
                    val kept = key.alternates.filter { it.length > 1 || it !in onPage || (key.label == "." && it == ",") }
                    key.copy(alternates = (kept + more).distinct())
                }
            }
        }

        /**
         * The page the =\< key promises. < and > close the math run after ÷ and ×, in the slots ¶ and ∆
         * had; those two are held on • and π, one alternate each, so TalkBack's hold action types them.
         */
        private fun moreSymbols(showSwitchKey: Boolean, inputType: Int, language: LanguageProfile, spaceLabel: String,
                                pound: Boolean = false): KeyboardLayout {
            if (!language.words) return diacritics(showSwitchKey, inputType, language, spaceLabel)
            val holds = mapOf("•" to "¶", "π" to "∆")
            return KeyboardLayout(
                listOf(
                    listOf("~", "`", "|", "•", "√", "π", "÷", "×", "<", ">").map { char(it, listOfNotNull(holds[it])) },
                    listOf(if (pound) "$" else "£", "€", "¥", "¢", "^", "°", "=", "{", "}", "\\").map { char(it) },
                    listOf(Key("?123", KeyAction.ToSymbols, 1.5f, KeyStyle.MODIFIER, spokenLabel = "Symbols")) +
                        listOf("%", "©", "®", "™", "✓", "[", "]").map { char(it) } + backspace(),
                    bottomRow(abc(), showSwitchKey, inputType, language, spaceLabel),
                ),
            )
        }

        /**
         * The IPA keyboard's page in place of the =\\< symbols: the IPA chart's diacritics, then tone. A combining mark
         * is drawn on a dotted circle and types the mark alone, after the symbol it modifies; a spacing mark types
         * itself. Its pair or its tones are on its hold. Asked for on 2026-10-28 by someone writing [œ̫˔] by hand.
         */
        private fun diacritics(showSwitchKey: Boolean, inputType: Int, language: LanguageProfile, spaceLabel: String): KeyboardLayout {
            fun mark(value: String, alternates: List<String> = emptyList()) =
                Key(if (Character.getType(value.codePointAt(0)) == Character.NON_SPACING_MARK.toInt()) "◌$value" else value,
                    KeyAction.Text(value), alternates = alternates, spokenLabel = spokenName(value))
            return KeyboardLayout(
                listOf(
                    // Phonation, place and release.
                    listOf("\u0325", "\u032C", "\u0324", "\u0330", "\u032A", "\u033A", "\u033B", "\u033C", "\u0303", "\u031A").map { mark(it) },
                    // Tongue height and position, rounding, syllabicity; raised and lowered hold their spacing forms.
                    listOf(mark("\u031D", listOf("˔")), mark("\u031E", listOf("˕"))) +
                        listOf("\u031F", "\u0320", "\u0308", "\u033D", "\u0339", "\u031C", "\u0329", "\u032F").map { mark(it) },
                    listOf(Key("?123", KeyAction.ToSymbols, 1.5f, KeyStyle.MODIFIER, spokenLabel = "Symbols")) + listOf(
                        mark("\u0318", listOf("\u0319")), mark("\u0334"), mark("\u032B"), mark("\u0361", listOf("\u035C")), mark("\u0306"),
                        mark("˥", listOf("˦", "˧", "˨", "˩")), mark("\u0301", listOf("\u030B", "\u0304", "\u0300", "\u030F", "\u030C", "\u0302")),
                    ) + backspace(),
                    bottomRow(abc(), showSwitchKey, inputType, language, spaceLabel),
                ),
            )
        }

        /** Spanish opens a question or exclamation with ¿ or ¡: offered on a hold of ? and !. */
        private fun LanguageProfile.openingMark(mark: String): List<String> = when {
            english -> emptyList()
            mark == "?" -> listOf("¿")
            mark == "!" -> listOf("¡")
            else -> emptyList()
        }

        private fun bottomRow(modeKey: Key, showSwitchKey: Boolean, inputType: Int, language: LanguageProfile, spaceLabel: String): List<Key> = buildList {
            add(modeKey)
            val fieldMode = TypingRules.layoutFor(inputType)
            add(when {
                !language.words -> char(STRESS, STRESS_HOLDS)
                fieldMode == Mode.NUMBER -> Key("123", KeyAction.ToFieldLayout, style = KeyStyle.MODIFIER, spokenLabel = "Number pad")
                fieldMode == Mode.PHONE -> Key("123", KeyAction.ToFieldLayout, style = KeyStyle.MODIFIER, spokenLabel = "Phone pad")
                fieldMode == Mode.DATETIME -> Key("123", KeyAction.ToFieldLayout, style = KeyStyle.MODIFIER, spokenLabel = "Date and time pad")
                TypingRules.isEmail(inputType) -> char("@")
                TypingRules.isUrl(inputType) -> char("/")
                else -> char(language.comma, (if (language.comma == ",") emptyList() else listOf(",")) + listOf("'", "\"", "-", "@", "&"))
            })
            // The comma sits beside ?123, as on Gboard, and the switch key beside the space bar: with the switch
            // key first, a thumb reaching for the comma kept changing keyboards (feedback after a month of use, 2026-10-20).
            if (showSwitchKey) add(Key("🌐", KeyAction.SwitchKeyboard, 1f, KeyStyle.MODIFIER, spokenLabel = "Next keyboard"))
            val url = TypingRules.isUrl(inputType)
            val email = TypingRules.isEmail(inputType)
            // Web and mail addresses get a .com key; in a mail field its hold has the other usual endings.
            // Persian's non-joiner key takes one unit of the space bar, as on Android's own Persian layout.
            add(Key(spaceLabel, KeyAction.Space, (if (showSwitchKey) 4f else 5f) - (if (url || email) 1f else 0f) - (if (language.joinerKey) 1f else 0f), KeyStyle.SPACE,
                spokenLabel = if (spaceLabel.isEmpty()) "Space" else "Space, $spaceLabel"))
            if (language.joinerKey) add(Key(JOINER_LABEL, KeyAction.Text(ZWNJ), 1f, KeyStyle.MODIFIER, alternates = listOf(ZWJ), spokenLabel = spokenName(ZWNJ)))
            if (url) add(char(".com"))
            if (email) add(char(".com", listOf(".org", ".net", ".edu", ".me")))
            val domains = if (TypingRules.isEmail(inputType)) listOf(".com", ".org", ".net") else emptyList()
            val marks = if (language.english) listOf("?", "!") else listOf("?", "¿", "!", "¡")
            add(char(".", when {
                !language.words -> PERIOD_HOLDS_IPA
                language.periodHolds.isNotEmpty() -> domains + language.periodHolds
                else -> domains + marks + listOf(",", ";", ":", "…", "'", "\"")
            }))
            add(Key("↵", KeyAction.Enter, 1.5f, KeyStyle.ACCENT, spokenLabel = "Return"))
        }

        private fun char(value: String, alternates: List<String> = emptyList()) =
            Key(value, KeyAction.Text(value), alternates = alternates, spokenLabel = spokenName(value))

        /** What a hold on each letter offers after the language's own accents; shared with the iPhone keyboard. */
        val ACCENTS: Map<Char, List<String>> = LetterAccents.ACCENTS

        /** The IPA keyboard's comma key types the primary stress mark; its hold has the other marks and the main diacritics. */
        private const val STRESS = "ˈ"
        private val STRESS_HOLDS = listOf("ˌ", "ʰ", "ʷ", "ʲ", "ˠ", "ˤ", "ⁿ", "ˡ", "ʼ", "˞", "\u0303", "\u0325", "\u0329", "\u032F", "\u032A", ",")

        /** Length, the syllable break and the brackets of a transcription. */
        private val PERIOD_HOLDS_IPA = listOf("ː", "ˑ", "|", "‖", "‿", "/", "[", "]", "?", "!", ",")

        /** What TalkBack says for each IPA symbol: its IPA name, so "type schwa" rather than an unreadable glyph. */
        private val IPA_NAMES: Map<String, String> = mapOf(
            "ə" to "schwa", "ɚ" to "rhotacized schwa", "ɘ" to "reversed e", "ɜ" to "reversed open e", "ɝ" to "rhotacized reversed open e",
            "ɞ" to "closed reversed open e", "ɪ" to "small capital I", "ɨ" to "barred i", "ʏ" to "small capital Y", "ʊ" to "upsilon",
            "ʉ" to "barred u", "ɯ" to "turned m", "ɛ" to "open e", "œ" to "o e ligature", "æ" to "ash", "ɐ" to "turned a",
            "ɶ" to "small capital O E", "ɑ" to "script a", "ɒ" to "turned script a", "ʌ" to "turned v", "ɔ" to "open o",
            "ɤ" to "ram's horn", "ʃ" to "esh", "ʒ" to "ezh", "ʂ" to "s with hook", "ʐ" to "z with retroflex hook", "ɕ" to "c with curl",
            "ʑ" to "z with curl", "θ" to "theta", "ð" to "eth", "ŋ" to "eng", "ɲ" to "n with left hook", "ɴ" to "small capital N",
            "ɳ" to "n with retroflex hook", "ɱ" to "m with hook", "ʔ" to "glottal stop", "ʕ" to "reversed glottal stop",
            "ɢ" to "small capital G", "ʍ" to "turned w", "ɰ" to "turned m with long leg", "ʷ" to "superscript w", "ø" to "o with stroke",
            "ɹ" to "turned r", "ɾ" to "fish-hook r", "ʁ" to "inverted small capital R", "ʀ" to "small capital R", "ɽ" to "r with tail",
            "ɻ" to "turned r with hook", "ɺ" to "turned long-leg r", "ʈ" to "t with retroflex hook", "t͡ʃ" to "t esh", "t͡s" to "t s",
            "ɥ" to "turned h", "ʎ" to "turned y", "ʝ" to "curly-tail j", "ɵ" to "barred o", "ɸ" to "phi", "ʘ" to "bilabial click",
            "ɖ" to "d with tail", "ɗ" to "d with hook", "d͡ʒ" to "d ezh", "d͡z" to "d z", "ɡ" to "script g", "ɣ" to "gamma",
            "ɠ" to "g with hook", "ʛ" to "small capital G with hook", "ħ" to "barred h", "ɦ" to "h with hook", "ʜ" to "small capital H",
            "ɧ" to "heng with hook", "ʰ" to "superscript h", "ɟ" to "barred dotless j", "ʄ" to "barred dotless j with hook",
            "ʞ" to "turned k", "ǀ" to "dental click", "ǁ" to "lateral click", "ǃ" to "alveolar click", "ǂ" to "palatal click",
            "ɫ" to "l with tilde", "ɬ" to "belted l", "ɮ" to "l ezh", "ɭ" to "l with retroflex hook", "ʟ" to "small capital L",
            "χ" to "chi", "ç" to "c cedilla", "ʋ" to "v with hook", "ⱱ" to "v with right hook", "β" to "beta", "ɓ" to "b with hook",
            "ʙ" to "small capital B", "ˈ" to "primary stress", "ˌ" to "secondary stress", "ː" to "long", "ˑ" to "half long",
            "ʼ" to "ejective", "ʲ" to "superscript j", "ˠ" to "superscript gamma", "ˤ" to "superscript reversed glottal stop",
            "ⁿ" to "superscript n", "ˡ" to "superscript l", "˞" to "rhotic hook", "\u0303" to "nasalized, combining tilde",
            "\u0325" to "voiceless, combining ring below", "\u0329" to "syllabic, combining vertical line below",
            "\u032F" to "non-syllabic, combining inverted breve below", "\u032A" to "dental, combining bridge below",
            "\u032C" to "voiced, combining caron below", "\u0324" to "breathy voiced, combining diaeresis below",
            "\u0330" to "creaky voiced, combining tilde below", "\u033A" to "apical, combining inverted bridge below",
            "\u033B" to "laminal, combining square below", "\u033C" to "linguolabial, combining seagull below",
            "\u031A" to "no audible release, combining left angle above", "\u031D" to "raised, combining up tack below",
            "\u031E" to "lowered, combining down tack below", "˔" to "raised, up tack", "˕" to "lowered, down tack",
            "\u031F" to "advanced, combining plus sign below", "\u0320" to "retracted, combining minus sign below",
            "\u0308" to "centralized, combining diaeresis", "\u033D" to "mid-centralized, combining x above",
            "\u0339" to "more rounded, combining right half ring below", "\u031C" to "less rounded, combining left half ring below",
            "\u0318" to "advanced tongue root, combining left tack below", "\u0319" to "retracted tongue root, combining right tack below",
            "\u0334" to "velarized or pharyngealized, combining tilde overlay", "\u032B" to "labialized, combining inverted double arch below",
            "\u0361" to "tie bar above", "\u035C" to "tie bar below", "\u0306" to "extra short, combining breve",
            "˥" to "extra high tone", "˦" to "high tone", "˧" to "mid tone", "˨" to "low tone", "˩" to "extra low tone",
            "\u0301" to "high tone, combining acute", "\u030B" to "extra high tone, combining double acute",
            "\u0304" to "mid tone, combining macron", "\u0300" to "low tone, combining grave",
            "\u030F" to "extra low tone, combining double grave", "\u030C" to "rising tone, combining caron",
            "\u0302" to "falling tone, combining circumflex",
            "|" to "minor group", "‖" to "major group", "‿" to "linking", "[" to "left bracket", "]" to "right bracket",
        )

        private fun abc() = Key("ABC", KeyAction.ToLetters, 1.5f, KeyStyle.MODIFIER, spokenLabel = "Letters")

        private fun backspace() = Key("⌫", KeyAction.Backspace, 1.5f, KeyStyle.MODIFIER, spokenLabel = "Delete")

        /** Invisible filler that centers the home row; touches on it go to the nearest real key. */
        private fun spacer(width: Float) = Key("", KeyAction.Text(""), width)

        /** The zero-width non-joiner Persian writes inside words, and the joiner on its key's hold. */
        const val ZWNJ = "\u200C"
        const val ZWJ = "\u200D"
        /** What the non-joiner key shows: two halves kept apart, since the character itself draws nothing. */
        const val JOINER_LABEL = "‹›"

        fun spokenName(value: String): String = when (value) {
            ZWNJ -> "Zero-width non-joiner"
            ZWJ -> "Zero-width joiner"
            "،" -> "Arabic comma"
            "؛" -> "Arabic semicolon"
            "؟" -> "Arabic question mark"
            "\u064B" -> "Fathatan"
            "\u064C" -> "Dammatan"
            "\u064D" -> "Kasratan"
            "\u064E" -> "Fatha"
            "\u064F" -> "Damma"
            "\u0650" -> "Kasra"
            "\u0651" -> "Shadda"
            "\u0652" -> "Sukun"
            "\u0640" -> "Tatweel"
            "," -> "Comma"
            "." -> "Period"
            ".com" -> "Dot com"
            ".org" -> "Dot org"
            ".net" -> "Dot net"
            "'" -> "Apostrophe"
            "\"" -> "Quote"
            "-" -> "Dash"
            "_" -> "Underscore"
            "/" -> "Slash"
            "\\" -> "Backslash"
            "&" -> "Ampersand"
            "@" -> "At"
            "#" -> "Hash"
            "*" -> "Asterisk"
            "+" -> "Plus"
            "=" -> "Equals"
            "<" -> "Less than"
            ">" -> "Greater than"
            "(" -> "Left parenthesis"
            ")" -> "Right parenthesis"
            "!" -> "Exclamation mark"
            "?" -> "Question mark"
            "¿" -> "Opening question mark"
            "¡" -> "Opening exclamation mark"
            ":" -> "Colon"
            ";" -> "Semicolon"
            "%" -> "Percent"
            "^" -> "Caret"
            "$" -> "Dollar sign"
            "£" -> "Pound sign"
            "ʻ" -> "ʻOkina"
            "ʼ" -> "Glottal stop"
            "°" -> "Degree sign"
            else -> IPA_NAMES[value] ?: value
        }
    }
}

val Key.longPress: String? get() = alternates.firstOrNull()

/** Hint only shortcuts, leaving accented letter faces and the dedicated number row uncluttered. */
val Key.holdHint: String? get() = longPress?.takeIf {
    it.first().isDigit() || (label.firstOrNull()?.isLetter() == true && !it.first().isLetterOrDigit()) ||
        label.firstOrNull()?.isLetterOrDigit() == false || width > 1f
}

/** True for layout filler that should never be drawn or announced. */
val Key.isSpacer: Boolean get() = action == KeyAction.Text("")

/**
 * The centers of [layout]'s single-letter keys, from the widths of the keys before them. Rows without a
 * letter key (the number row, the bottom row) are skipped and don't count as rows, so a number row above
 * the letters doesn't move them. `LanguageEngineTest` checks it agrees with [LetterGeometry.fromRows].
 */
fun LetterGeometry.Companion.from(layout: KeyboardLayout): LetterGeometry = LetterGeometry.of(buildMap {
    var row = 0
    for (keys in layout.rows) {
        if (keys.none { it.letter() != null }) continue
        var x = 0f
        for (key in keys) {
            key.letter()?.let { put(it, Touch(x + key.width / 2f, row + 0.5f)) }
            x += key.width
        }
        row++
    }
})

private fun Key.letter(): Char? = label.singleOrNull()?.takeIf { it.isLetter() && action == KeyAction.Text(label) }
