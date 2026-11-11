package dev.localtype.keyboard

/** Explicit Unicode substitutions, never a font setting or a normal-typing transformation. */
object TextEffects {
    const val MAX_INPUT = 256
    const val MAX_OUTPUT = 2048
    enum class Style { SMALL_CAPS, SUPERSCRIPT, MONOSPACE, FULLWIDTH, CURSED }
    private val small = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘqʀꜱᴛᴜᴠᴡxʏᴢ".toList()
    private val raised = "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖqʳˢᵗᵘᵛʷˣʸᶻ".toList()
    private fun mark(cp: Int) = Character.getType(cp) in listOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    fun preview(original: String, style: Style): String? {
        if (original.isEmpty() || original.length > MAX_INPUT) return null
        val points = ArrayList<Int>()
        var i = 0
        var marks = 0
        while (i < original.length) {
            val cp = original.codePointAt(i)
            if (cp in 0xD800..0xDFFF || (Character.isISOControl(cp) && cp != 10 && cp != 9)) return null
            marks = if (mark(cp)) marks + 1 else 0
            if (marks > 2) return null
            points += cp
            i += Character.charCount(cp)
        }
        val out = StringBuilder()
        points.forEachIndexed { index, cp ->
            val lower = if (cp in 65..90) cp + 32 else cp
            when (style) {
                Style.SMALL_CAPS -> if (lower in 97..122) out.append(small[lower - 97]) else out.appendCodePoint(cp)
                Style.SUPERSCRIPT -> if (lower in 97..122) out.append(raised[lower - 97]) else out.appendCodePoint(cp)
                Style.MONOSPACE -> out.appendCodePoint(when (cp) { in 65..90 -> 0x1D670 + cp - 65; in 97..122 -> 0x1D68A + cp - 97; in 48..57 -> 0x1D7F6 + cp - 48; else -> cp })
                Style.FULLWIDTH -> out.appendCodePoint(when (cp) { 32 -> 0x3000; in 33..126 -> cp + 0xFEE0; else -> cp })
                Style.CURSED -> {
                    out.appendCodePoint(cp)
                    // Existing marks stay unchanged; never stack new marks on an already marked letter.
                    if (Character.isLetter(cp) && points.getOrNull(index + 1)?.let(::mark) != true) out.append("\u0301\u0332")
                }
            }
        }
        return out.toString().takeIf { it.length <= MAX_OUTPUT }
    }
}
