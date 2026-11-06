package dev.localtype.keyboard

/**
 * Every emoji the panel offers, in Unicode order, with CLDR's English name and keywords and, where
 * there are any, the five skin-tone variants. Parsed once from `emoji_inventory.txt`, which
 * `scripts/build-emoji-inventory.py` builds from Unicode's emoji-test.txt and CLDR, and immutable
 * afterwards. Pure: the font check comes in as a lambda, so tests run without a phone.
 */
class EmojiInventory private constructor(
    /** Unicode group names in order ("Smileys & Emotion" … "Flags"); [Emoji.group] indexes this. */
    val groups: List<String>,
    /** Every base emoji the phone can draw, in Unicode order. */
    val emoji: List<Emoji>,
) {
    /**
     * One base emoji: [value] exactly as emoji-test.txt has it (U+FE0F included), CLDR's spoken
     * [name], and [tones], which is empty or the five single-tone variants from light to dark.
     */
    data class Emoji(val value: String, val name: String, val keywords: List<String>, val tones: List<String>, val group: Int) {
        /** The base for tone 0, else skin tone 1 (light) to 5 (dark); the base when there's no such tone. */
        fun variant(tone: Int): String = tones.getOrNull(tone - 1) ?: value
    }

    private val byKey: Map<String, Emoji> = emoji.associateBy { key(it.value) }

    val size: Int get() = emoji.size

    /** The base emoji [text] is, or is a tone variant of, whatever its U+FE0F; null when the panel doesn't offer it. */
    fun find(text: String): Emoji? = byKey[key(text)]

    companion object {
        val EMPTY = EmojiInventory(emptyList(), emptyList())
        const val TONE_COUNT = 5
        private const val GROUPS_LINE = "#groups\t"
        private const val FIRST_TONE = 0x1F3FB
        private const val VARIATION_SELECTOR = 0xFE0F

        /** [text] without skin-tone modifiers or U+FE0F, which is what identifies its base emoji. */
        fun key(text: String): String = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                if (codePoint != VARIATION_SELECTOR && toneIndex(codePoint) == 0) appendCodePoint(codePoint)
                i += Character.charCount(codePoint)
            }
        }

        /** The skin tone [text] carries, 1 (light) to 5 (dark), or 0 when it has none. */
        fun toneOf(text: String): Int {
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                toneIndex(codePoint).let { if (it > 0) return it }
                i += Character.charCount(codePoint)
            }
            return 0
        }

        private fun toneIndex(codePoint: Int): Int =
            if (codePoint in FIRST_TONE until FIRST_TONE + TONE_COUNT) codePoint - FIRST_TONE + 1 else 0

        /**
         * Parses `group<TAB>emoji<TAB>name<TAB>keywords<TAB>tones` rows after the `#groups` line.
         * An emoji [hasGlyph] rejects is left out, so a phone never shows one its fonts draw as a box
         * or as loose pieces; its tones go too if any one of them is rejected, since the picker
         * offers all five or none. Malformed rows are skipped.
         */
        fun parse(lines: Sequence<String>, hasGlyph: (String) -> Boolean = { true }): EmojiInventory {
            var groups = emptyList<String>()
            val emoji = ArrayList<Emoji>()
            for (line in lines) {
                if (line.startsWith(GROUPS_LINE)) {
                    groups = line.substring(GROUPS_LINE.length).split('\t')
                    continue
                }
                if (line.isBlank() || line.startsWith("#")) continue
                val fields = line.split('\t')
                if (fields.size < 5) continue
                val group = fields[0].toIntOrNull()?.takeIf { it in groups.indices } ?: continue
                val value = fields[1]
                if (value.isEmpty() || !hasGlyph(value)) continue
                val tones = fields[4].split(' ').filter { it.isNotEmpty() }
                    .takeIf { it.size == TONE_COUNT && it.all(hasGlyph) }.orEmpty()
                emoji += Emoji(value, fields[2], fields[3].split('|').filter { it.isNotEmpty() }, tones, group)
            }
            return EmojiInventory(groups, emoji)
        }
    }
}
