package dev.localtype.keyboard

/**
 * A deterministic report of what the engine does with the keyboard's own assets: the priors' digests,
 * corrections of fixed typos in English and Spanish, next-word predictions with each prior, the learning
 * codecs' round trips and Chime's first prediction. The Android build writes it from a unit test and the
 * iOS build from a simulator run (engine/src/iosSimulatorArm64Main); the two must be identical, which shows
 * the shared engine behaves the same on both. [read] returns an asset's bytes by file name.
 */
object EngineSelfCheck {
    fun report(read: (String) -> ByteArray): String = buildString {
        fun lines(name: String) = read(name).decodeToString().lineSequence()

        val english = WordDictionary.parse(lines("words_en.txt") + lines("words_en_modern.txt"))
        val englishKnown = Lexicon.parse(lines("words_known.txt"))
        val spanish = WordDictionary.parse(lines("words_es.txt"), LanguageProfile.SPANISH)
        val spanishKnown = Lexicon.parse(lines("words_known_es.txt"))
        appendLine("dictionaries en=${english.size} es=${spanish.size} lexicons en=${englishKnown.size} es=${spanishKnown.size}")

        val priorEn = CorpusPrior.read(read(CorpusPrior.ASSET))
        val priorEs = CorpusPrior.read(read("corpus_prior_es.bin"))
        appendLine("prior en ${priorEn.sha256} words=${priorEn.size} contexts=${priorEn.contexts}")
        appendLine("prior es ${priorEs.sha256} words=${priorEs.size} contexts=${priorEs.contexts}")

        val enCorrector = Corrector({ english }, lexicon = { englishKnown })
        for (typo in listOf("teh", "becuase", "recieve", "wnat", "dont", "im", "hte", "thsi", "goign", "tomorow", "definately", "keybaord")) {
            appendLine("en correct $typo -> ${enCorrector.correct(typo)} / suggest ${enCorrector.suggest(typo)}")
        }
        val esCorrector = Corrector({ spanish }, lexicon = { spanishKnown }, language = { LanguageProfile.SPANISH })
        for (typo in listOf("tambien", "manana", "aqui", "grscias", "senor", "cancion", "dificil", "nino", "esta", "dont")) {
            appendLine("es correct $typo -> ${esCorrector.correct(typo)}")
        }

        val enModel = AdaptiveNgramModel(dictionary = english, prior = priorEn)
        for (context in listOf("", "I am ", "thank ", "see you ", "what do you th")) {
            appendLine("en predict \"$context\" -> ${enModel.predict(context, 3)}")
        }
        val es = LanguageProfile.SPANISH
        val esModel = AdaptiveNgramModel(seedPhrases = emptyList(), dictionary = spanish, sentenceStarters = emptyList(), prior = priorEs,
            priorWeights = AdaptiveNgramModel.PriorWeights(weight = es.priorWeight), openingMarks = es.openingMarks, oneLetterWords = es.oneLetterWords)
        for (context in listOf("", "muchas ", "buenos ", "Hola. ¿qu", "perro y gat")) {
            appendLine("es predict \"$context\" -> ${esModel.predict(context, 3)}")
        }

        val journal = LearnedPairJournal.increment("good", "morning") + LearnedPairJournal.increment("buenos", "días") +
            LearnedPairJournal.compact(listOf(Triple("see", "you", 3)))
        val decoded = LearnedPairJournal.decode(journal)
        appendLine("journal ${hex(sha256(journal))} records=${decoded.records} damaged=${decoded.damaged} entries=${decoded.state.entries()}")
        val personal = PersonalLearningState(oneLetterWords = es.oneLetterWords)
        personal.record(listOf("perro"), "y", LearningOrigin.TYPED)
        personal.record(listOf("perro"), "y", LearningOrigin.EXPLICIT_KEEP)
        val snapshot = PersonalLearningCodec.encode(personal.entries().map { it.copy(lastObserved = 7) }, es.oneLetterWords)
        appendLine("personal ${hex(sha256(snapshot))} decoded=${PersonalLearningCodec.decode(snapshot, es.oneLetterWords).size}")

        appendLine("case ${TextRules.applyCase("hello", "He", false)} ${TextRules.applyCase("i'm", "", false)} ${TextRules.applyCase("world", "WO", false)}")
        appendLine("graphemes ${listOf("a👍🏽", "é", "🇪🇸", "👩‍💻", "ñ").map { TextRules.lastGraphemeLength(it) }}")
        appendLine("geometry ñ=${es.geometry.center('ñ')} z=${es.geometry.center('z')}")

        val chime = CifgModel.load(read("cifg_int8.bin")).newSession()
        appendLine("chime \"I am going to the \" -> ${chime.predict("I am going to the ", 5).map { it.word }}")
    }
}
