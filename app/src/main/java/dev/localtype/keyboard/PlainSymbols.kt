package dev.localtype.keyboard

/** A small local catalog. Text and emoji presentation are distinct, explicit insertions. */
object PlainSymbols {
    enum class Kind { TEXT, EMOJI }
    data class Entry(val text: String, val en: String, val es: String, val pt: String, val kind: Kind = Kind.TEXT) {
        fun name(language: String) = when (language) { "es" -> es; "pt" -> pt; else -> en }
    }
    private fun text(value: String, en: String, es: String, pt: String) = Entry(value, en, es, pt)
    val entries = listOf(
        text("™", "trademark tm", "marca comercial tm", "marca comercial tm"),
        text("©", "copyright c", "derechos de autor c", "direitos autorais c"),
        text("®", "registered trademark r", "marca registrada r", "marca registrada r"),
        text("℠", "service mark", "marca de servicio", "marca de serviço"),
        text("℗", "sound recording copyright", "derechos de grabación", "direitos de gravação"),
        text("§", "section", "sección", "seção"), text("¶", "paragraph", "párrafo", "parágrafo"),
        text("№", "number", "número", "número"), text("†", "dagger", "cruz", "cruz"),
        text("—", "em dash", "raya", "travessão"), text("–", "en dash", "semirraya", "meia risca"),
        text("…", "ellipsis", "puntos suspensivos", "reticências"), text("•", "bullet", "viñeta", "marcador"),
        text("«", "left angle quotation", "comillas de apertura", "aspas de abertura"),
        text("»", "right angle quotation", "comillas de cierre", "aspas de fechamento"),
        text("€", "euro", "euro", "euro"), text("£", "pound", "libra", "libra"),
        text("¥", "yen", "yen", "iene"), text("¢", "cent", "centavo", "centavo"),
        text("₹", "rupee", "rupia", "rupia"), text("₿", "bitcoin", "bitcoin", "bitcoin"),
        text("±", "plus minus", "más menos", "mais menos"), text("×", "multiplication", "multiplicación", "multiplicação"),
        text("÷", "division", "división", "divisão"), text("≠", "not equal", "distinto", "diferente"),
        text("≈", "approximately equal", "aproximadamente igual", "aproximadamente igual"),
        text("≤", "less than or equal", "menor o igual", "menor ou igual"),
        text("≥", "greater than or equal", "mayor o igual", "maior ou igual"),
        text("∞", "infinity", "infinito", "infinito"), text("√", "square root", "raíz cuadrada", "raiz quadrada"),
        text("∑", "sum sigma", "suma sigma", "soma sigma"), text("π", "pi", "pi", "pi"),
        text("Δ", "delta", "delta", "delta"), text("µ", "micro", "micro", "micro"),
        text("°", "degree", "grado", "grau"), text("½", "one half", "un medio", "um meio"),
        text("←", "left arrow", "flecha izquierda", "seta esquerda"),
        text("→", "right arrow implies logic", "flecha derecha implica lógica", "seta direita implica lógica"),
        text("↑", "up arrow", "flecha arriba", "seta acima"), text("↓", "down arrow", "flecha abajo", "seta abaixo"),
        // Logic and sets. The first words are what TalkBack reads after the sign, so they are its name in that language.
        text("∀", "for all universal quantifier logic", "para todo cuantificador universal lógica", "para todo quantificador universal lógica"),
        text("∃", "there exists existential quantifier logic", "existe cuantificador existencial lógica", "existe quantificador existencial lógica"),
        text("∄", "there does not exist not exists logic", "no existe lógica", "não existe lógica"),
        text("¬", "not negation logic", "no negación lógica", "não negação lógica"),
        text("∧", "and conjunction logic", "y conjunción lógica", "e conjunção lógica"),
        text("∨", "or disjunction logic", "o disyunción lógica", "ou disjunção lógica"),
        text("↔", "left right arrow if and only if iff logic", "flecha izquierda derecha si y solo si lógica", "seta esquerda direita se e somente se lógica"),
        text("⇒", "implies double right arrow logic", "implica flecha doble derecha lógica", "implica seta dupla direita lógica"),
        text("⇔", "if and only if iff double arrow logic", "si y solo si flecha doble lógica", "se e somente se seta dupla lógica"),
        text("∈", "element of in set", "pertenece a elemento de conjunto", "pertence a elemento de conjunto"),
        text("∉", "not an element of not in set", "no pertenece a conjunto", "não pertence a conjunto"),
        text("⊂", "subset set", "subconjunto conjunto", "subconjunto conjunto"),
        text("⊆", "subset or equal set", "subconjunto o igual conjunto", "subconjunto ou igual conjunto"),
        text("∪", "union set", "unión conjunto", "união conjunto"),
        text("∩", "intersection set", "intersección conjunto", "interseção conjunto"),
        text("∅", "empty set", "conjunto vacío", "conjunto vazio"),
        text("⊤", "top true tautology logic", "verdadero tautología lógica", "verdadeiro tautologia lógica"),
        text("⊥", "bottom false contradiction perpendicular logic", "falso contradicción perpendicular lógica", "falso contradição perpendicular lógica"),
        text("⊢", "proves turnstile logic", "demuestra deducción lógica", "demonstra dedução lógica"),
        text("⊨", "entails models double turnstile logic", "consecuencia semántica lógica", "consequência semântica lógica"),
        text("✓", "check mark", "marca de verificación", "marca de seleção"),
        text("♩", "quarter note music", "negra música", "semínima música"),
        text("♪", "eighth note music", "corchea música", "colcheia música"),
        text("♫", "beamed notes music", "corcheas unidas música", "colcheias unidas música"),
        text("♭", "flat music", "bemol música", "bemol música"), text("♮", "natural music", "becuadro música", "bequadro música"),
        text("♯", "sharp music", "sostenido música", "sustenido música"),
        text("𝄞", "treble clef music", "clave de sol música", "clave de sol música"),
        text("𝄢", "bass clef music", "clave de fa música", "clave de fá música"),
        text("ə", "schwa IPA phonetic", "schwa AFI fonética", "schwa AFI fonética"),
        text("ɛ", "open e IPA phonetic", "e abierta AFI fonética", "e aberto AFI fonética"),
        text("ŋ", "eng IPA phonetic", "eng AFI fonética", "eng AFI fonética"),
        text("ʃ", "esh IPA phonetic", "esh AFI fonética", "esh AFI fonética"),
        text("ʒ", "ezh IPA phonetic", "ezh AFI fonética", "ezh AFI fonética"),
        text("θ", "theta IPA phonetic", "theta AFI fonética", "theta AFI fonética"),
        text("ˈ", "primary stress IPA", "acento primario AFI", "acento primário AFI"),
        Entry("™\uFE0F", "trademark emoji tm", "marca comercial emoji tm", "marca comercial emoji tm", Kind.EMOJI),
        Entry("©\uFE0F", "copyright emoji c", "derechos de autor emoji c", "direitos autorais emoji c", Kind.EMOJI),
        Entry("®\uFE0F", "registered trademark emoji r", "marca registrada emoji r", "marca registrada emoji r", Kind.EMOJI),
    )
    fun search(query: String): List<Entry> {
        val bounded = LocalPanelInput.bound(query, 64)
        val terms = EmojiSearch.normalize(bounded).split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return entries
        return entries.filter { entry ->
            val names = EmojiSearch.normalize("${entry.en} ${entry.es} ${entry.pt}")
            entry.text == bounded || terms.all { it in names }
        }
    }
}

/** Keys borrowed by a panel never enter the app's edit, learning or logging paths. */
object LocalPanelInput {
    fun bound(text: String, limit: Int): String {
        val end = text.length.coerceAtMost(limit)
        return text.substring(0, if (end > 0 && text[end - 1].isHighSurrogate()) end - 1 else end)
    }
    fun edit(text: String, action: KeyAction, limit: Int): String? = when (action) {
        is KeyAction.Text -> if (action.value.any { it.isISOControl() }) text else bound(text + action.value, limit)
        KeyAction.Space -> bound("$text ", limit)
        KeyAction.Backspace -> TypingRules.dropLastGrapheme(text)
        else -> null
    }
}
