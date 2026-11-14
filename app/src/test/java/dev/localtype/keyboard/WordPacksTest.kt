package dev.localtype.keyboard

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The opt-in word packs: a switched-on pack's words are recognized, completed only from their kind's minimum of typed
 * letters (five for drug names in the medical pack, three for everything else), never offered
 * with nothing typed, never applied as an automatic fix, and officeholders fade from completion; with no pack on,
 * nothing changes. The bundled packs' own checks (slurs, licenses, evaluation) are in [BundledWordPacksTest].
 */
class WordPacksTest {
    private val observed = LocalDate.parse("2026-11-13").toEpochDay()

    /** A small pack in the bundled format, with each kind. warfarin and filibuster are in SCOWL already; the rest are not. */
    private val pack = WordPack.strict(
        """
        # LocalType word pack v1
        # id=test
        # language=en
        # name=Test pack
        # observed=2026-11-13
        # min-prefix=drug:5
        tirzepatide	tirzepatide	44	drug
        hydroxyzine	hydroxyzine	44	drug
        hydralazine	hydralazine	40	drug
        warfarin	warfarin	44	drug
        lipitor	Lipitor	42	drug
        tachycardia	tachycardia	36	term
        filibuster	filibuster	40	office
        thune	Thune	40	officeholder
        schumer	Schumer	44	officeholder
        """.trimIndent() + "\n",
    )
    private val packs = WordPacks.of(listOf(pack), observed)

    private fun corrector(withPacks: WordPacks = WordPacks.EMPTY) =
        Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon }, packs = { withPacks })

    private fun model(withPacks: WordPacks = WordPacks.EMPTY) = AdaptiveNgramModel(dictionary = TypoCorpus.dictionary, packs = withPacks)

    @Test
    fun `a typed pack word is known, so the corrector leaves it alone`() {
        // Without the pack, "thune" is a typo of an ordinary word; with it, the senator's name stays as typed.
        assertNotNull("precondition: thune is corrected without the pack", corrector().suggest("thune"))
        assertNull(corrector(packs).propose("thune"))
        assertNull(corrector(packs).propose("Thune"))
        assertNull(corrector(packs).propose("hydroxyzine"))
        assertNull(corrector(packs).propose("thune's"))
        val snapshot = corrector(packs).candidates("thune")
        assertEquals("known-or-learned", snapshot.guardReason)
        // Recognition outlives the officeholder fade.
        assertTrue("thune" in packs.at(observed + 400))
    }

    @Test
    fun `drug names complete from five letters and every other kind from three`() {
        assertEquals(5, pack.minPrefix(WordPack.Kind.DRUG))
        for (kind in listOf(WordPack.Kind.TERM, WordPack.Kind.OFFICE, WordPack.Kind.OFFICEHOLDER)) assertEquals(kind.id, 3, pack.minPrefix(kind))
        // Nothing from fewer than three letters, whatever the kind.
        assertTrue(packs.candidates("ti").isEmpty())
        assertTrue(packs.candidates("t'i").isEmpty())
        assertTrue(packs.candidates("ta").isEmpty())
        // A drug name waits for its fifth letter.
        for (prefix in listOf("tir", "tirz", "Tirz", "hyd", "hydr", "lip", "lipi")) {
            assertTrue("$prefix: ${packs.candidates(prefix)}", packs.candidates(prefix).none { pack.entry(it.first)!!.kind == WordPack.Kind.DRUG })
        }
        assertEquals("tirzepatide", packs.candidates("tirze").single().first)
        assertEquals("tirzepatide", packs.candidates("Tirze").single().first)
        assertEquals(setOf("hydroxyzine", "hydralazine"), packs.candidates("hydra").map { it.first }.toSet() + packs.candidates("hydro").map { it.first })
        assertEquals("lipitor", packs.candidates("lipit").single().first)
        // Terms, offices and officeholders from the third.
        assertEquals("tachycardia", packs.candidates("tac").single().first)
        assertEquals("filibuster", packs.candidates("fil").single().first)
        assertEquals(listOf("schumer"), packs.candidates("sch").map { it.first })
        assertEquals(listOf("thune"), packs.candidates("thu").map { it.first })
        val model = model(packs)
        assertFalse("tirzepatide" in model.predict("ti", 60))
        assertFalse("tirzepatide" in model.predict("I started tirz", 60))
        assertEquals("tirzepatide", model.predict("I started tirze").first())
        assertEquals("hydroxyzine", model.predict("hydrox").first())
        assertTrue("tachycardia" in model.predict("tac", 60))
        // The reserved slot beside Chime's words follows the same rule.
        assertTrue(packs.reserved("tirz", listOf("tirzepatide")).isEmpty())
        assertEquals(listOf("tirzepatide"), packs.reserved("tirze", listOf("tirzepatide")))
        // Display spelling comes from the pack, possessives kept.
        assertEquals("Lipitor", packs.preferred("lipitor"))
        assertEquals("Schumer's", packs.preferred("schumer's"))
        assertNull(packs.preferred("aspirin"))
    }

    @Test
    fun `pack words never fill the empty next-word slots`() {
        val model = model(packs)
        for (context in listOf("", "I take ", "Senator ", "Hello. ", "the drug ")) {
            val offered = model.predict(context, 60)
            assertTrue("$context: $offered", offered.none { it in packs })
            assertTrue(packs.candidates(AdaptiveNgramModel.activePrefix(context)).isEmpty())
            assertTrue(packs.reserved(AdaptiveNgramModel.activePrefix(context), offered).isEmpty())
        }
        // A pack word takes the slot beside Chime's only where the n-gram list itself ranks it in its first three.
        assertEquals(listOf("hydroxyzine"), packs.reserved("hydrox", model.predict("hydrox", 6)))
        assertTrue(packs.reserved("hyd", listOf("hydrogen", "hydrated", "hydrant")).isEmpty())
        val strip = NeuralRanking.rank(listOf("hydroxide", "hydroxyl", "hydroxy"), model.predict("hydrox", 6), emptyList(), "hydrox",
            emptySet(), shared = packs.reserved("hydrox", model.predict("hydrox", 6)))
        assertTrue(strip.toString(), "hydroxyzine" in strip)
    }

    @Test
    fun `a typo is never changed into a pack word, only offered`() {
        // Look-alike drug names: the nearest one is offered and nothing is applied on space.
        assertNull(corrector(packs).correct("hydroxyzne"))
        assertEquals("hydroxyzine", corrector(packs).suggest("hydroxyzne"))
        assertEquals(Corrector.Correction("hydralazine", automatic = false), corrector(packs).propose("hydralazne"))
        // warfarin is a SCOWL word: without the pack it is applied, with the pack on it is only offered.
        assertEquals("precondition", "warfarin", corrector().correct("warfaron"))
        assertNull(corrector(packs).correct("warfaron"))
        assertEquals("warfarin", corrector(packs).suggest("warfaron"))
        assertTrue(packs.offerOnly("Warfarin"))
        assertFalse(packs.offerOnly("water"))
        // An everyday fix is never taken by a pack word: the pack is only asked when no ordinary word is a candidate.
        val plain = corrector()
        val packed = corrector(packs)
        for ((typed, _) in TypoCorpus.real) assertEquals(typed, plain.correct(typed), packed.correct(typed))
        assertEquals("the", packed.correct("teh"))
    }

    @Test
    fun `officeholders fade like the news bonus while every other kind stays`() {
        for ((age, percent) in listOf(-1 to 0, 0 to 100, 59 to 100, 60 to 50, 119 to 50, 120 to 0, 400 to 0)) {
            val day = observed + age
            assertEquals("age $age", percent, pack.fade(day))
            val phase = packs.at(day)
            val schumer = phase.candidates("schu").firstOrNull { it.first == "schumer" }
            if (percent == 0) assertNull("age $age", schumer) else assertEquals("age $age", 44 * percent / 100, schumer?.second)
            assertEquals(44 * percent / 100, phase.lift("schumer", 0))
            // Jargon and drugs never expire, and recognition never does.
            assertEquals("filibuster", phase.candidates("filib").single().first)
            assertEquals(44, phase.lift("tirzepatide", 0))
            assertTrue("schumer" in phase)
            assertEquals("Schumer", phase.preferred("schumer"))
        }
        // Expired names leave the strip; the model with the same packs keeps everything else.
        assertFalse("schumer" in model(packs.at(observed + 120)).predict("schu", 20))
        assertEquals("schumer", model(packs).predict("schum").first())
    }

    @Test
    fun `with no pack on the ranking and the corrector are exactly as before`() {
        val plain = model()
        val empty = model(WordPacks.EMPTY)
        for (context in listOf("", "hello ", "I will ", "the", "for", "goo", "hydrox", "tirz", "schum", "metf")) {
            assertEquals(context, plain.predict(context, 6), empty.predict(context, 6))
        }
        for ((typed, _) in TypoCorpus.real) assertEquals(typed, corrector().propose(typed), corrector(WordPacks.EMPTY).propose(typed))
        assertTrue(WordPacks.EMPTY.candidates("hydrox").isEmpty())
        assertFalse("hydroxyzine" in WordPacks.EMPTY)
    }

    @Test
    fun `a bundled pack is refused whole for one bad line`() {
        val good = "# LocalType word pack v1\n# id=x\n# language=en\n# name=X\nzorpquill\tzorpquill\t30\tterm\n"
        assertEquals(1, WordPack.strict(good).size)
        assertEquals(3, WordPack.strict(good).minPrefix(WordPack.Kind.DRUG))
        val fives = WordPack.strict(good.replace("# name=X\n", "# name=X\n# min-prefix=drug:5,term:4\n"))
        assertEquals(5, fives.minPrefix(WordPack.Kind.DRUG))
        assertEquals(4, fives.minPrefix(WordPack.Kind.TERM))
        assertEquals(3, fives.minPrefix(WordPack.Kind.OFFICE))
        val bad = listOf(
            good.replace("# LocalType word pack v1\n", ""),
            good.replace("# id=x\n", ""),
            good.replace("# id=x", "# id=import-x"),
            good.replace("# language=en", "# language=xx"),
            good + "zorpquill\tzorpquill\t30\tterm\n",
            good + "Quillzorp\tQuillzorp\t30\tterm\n",
            good + "quillzorp\tQuillzorp\t61\tterm\n",
            good + "quillzorp\tquillzorp\t30\tdrugs\n",
            good + "two words\ttwo words\t30\tterm\n",
            good + "quillzorp\tquillzorp\t30\n",
            good + "quillzorp\tother\t30\tterm\n",
            // An officeholder needs the date the list was observed.
            good + "quillzorp\tQuillzorp\t30\tofficeholder\n",
            // min-prefix: a known kind, three to eight letters, each kind once.
            good.replace("# name=X\n", "# name=X\n# min-prefix=drug:2\n"),
            good.replace("# name=X\n", "# name=X\n# min-prefix=drug:9\n"),
            good.replace("# name=X\n", "# name=X\n# min-prefix=drugs:5\n"),
            good.replace("# name=X\n", "# name=X\n# min-prefix=drug5\n"),
            good.replace("# name=X\n", "# name=X\n# min-prefix=drug:5,drug:6\n"),
        )
        for (text in bad) {
            try {
                WordPack.strict(text)
                fail("accepted: $text")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `an imported file may be a plain word list, and lines that can't be used are left out`() {
        val today = observed + 3
        val text = "﻿Tirzepatide\nzorpquill\tZorpquill\n\nquillzorp\tquillzorp\t45\tdrug\n# a source note\n" +
            "two words\nabc123\nzorpquill\nbadscore\tbadscore\tlots\nbadkind\tbadkind\t30\twhatever\nx\n" +
            "Mamdanu\tMamdanu\t40\tofficeholder\n"
        val read = WordPack.lenient(text, fallbackId = "My List.txt", fallbackLanguage = "en", fallbackName = "My list", today = today)
        assertNotNull(read.pack)
        val imported = read.pack!!
        assertEquals("import-mylisttxt", imported.id)
        assertEquals("My list", imported.name)
        assertEquals("en", imported.language)
        assertEquals(setOf("tirzepatide", "zorpquill", "quillzorp", "mamdanu"), imported.words)
        assertEquals(6, read.skipped)
        assertEquals("Tirzepatide", imported.entry("tirzepatide")!!.display)
        assertEquals(WordPack.DEFAULT_SCORE, imported.entry("zorpquill")!!.score)
        assertEquals(WordPack.Kind.DRUG, imported.entry("quillzorp")!!.kind)
        // With no observed date, an imported officeholder counts from the day of the import.
        assertEquals(today, imported.observedDay)
        // What is stored is the pack format, read back strictly with the same words.
        val stored = WordPack.strict(imported.encode(listOf("imported 2026-11-16")), imported = true)
        assertEquals(imported.words, stored.words)
        // A person's file may ask more letters for a kind; a value that can't be used asks nothing more than three.
        val asked = WordPack.lenient("# min-prefix=drug:6,bogus:4,term:2\nquillzorp\tquillzorp\t30\tdrug\nzorpquill\n", "a", "en", "A", today).pack!!
        assertEquals(6, asked.minPrefix(WordPack.Kind.DRUG))
        assertEquals(3, asked.minPrefix(WordPack.Kind.TERM))
        val askedStored = WordPack.strict(asked.encode(), imported = true)
        assertEquals(6, askedStored.minPrefix(WordPack.Kind.DRUG))
        val askedPacks = WordPacks.of(listOf(askedStored), today)
        assertTrue(askedPacks.candidates("quill").isEmpty())
        assertEquals("quillzorp", askedPacks.candidates("quillz").single().first)
        assertEquals("zorpquill", askedPacks.candidates("zor").single().first)
        assertEquals(imported.id, stored.id)
        assertEquals("Tirzepatide", stored.entry("tirzepatide")!!.display)
        // Headers win over the fallbacks, and an imported id can never be a bundled one.
        val headed = WordPack.lenient("# id=medical\n# language=es\n# name=Mis palabras\nzorpquill\n", "other", "en", "Other", today).pack!!
        assertEquals("import-medical", headed.id)
        assertEquals("es", headed.language)
        assertEquals("Mis palabras", headed.name)
        // Nothing usable: no pack.
        assertNull(WordPack.lenient("two words\n123\n", "x", "en", "X", today).pack)
        // The bound on entries.
        val many = (0 until WordPack.MAX_ENTRIES + 5).joinToString("\n") { "zq" + it.toString(26).map { c -> 'a' + c.digitToInt(26) }.joinToString("") }
        val big = WordPack.lenient(many, "big", "en", "Big", today)
        assertEquals(WordPack.MAX_ENTRIES, big.pack!!.size)
        assertEquals(5, big.overLimit)
    }

    /**
     * Look-alike names from FDA's Name Differentiation Project (the tall-man list): with the bundled medical pack on,
     * both names of a pair are left as typed, and no slip of either is ever changed into a drug name, only offered. A
     * slip that is nearer an ordinary word may still be fixed to that word, as without the pack; those are printed.
     */
    @Test
    fun `look-alike drug names from FDA's tall-man list are never applied`() {
        val medical = WordPacks.of(listOf(WordPack.strict(File("src/main/assets/pack_medical_en.txt").readText())), observed)
        val packed = corrector(medical)
        val pairs = listOf(
            "acetazolamide" to "acetohexamide", "bupropion" to "buspirone", "chlorpromazine" to "chlorpropamide",
            "clomiphene" to "clomipramine", "cyclosporine" to "cycloserine", "daunorubicin" to "doxorubicin",
            "dimenhydrinate" to "diphenhydramine", "dobutamine" to "dopamine", "glipizide" to "glyburide",
            "hydralazine" to "hydroxyzine", "medroxyprogesterone" to "methylprednisolone", "nicardipine" to "nifedipine",
            "prednisone" to "prednisolone", "sulfadiazine" to "sulfisoxazole", "tolazamide" to "tolbutamide",
            "vinblastine" to "vincristine",
        )
        val checked = pairs.filter { (a, b) -> a in medical && b in medical }
        assertTrue("pairs in the pack: ${checked.size}", checked.size >= 10)
        var slips = 0
        val ordinary = ArrayList<String>()
        for ((a, b) in checked) for (name in listOf(a, b)) {
            assertNull(name, packed.propose(name))
            val middle = name.length / 2
            val typos = listOf(name.removeRange(middle, middle + 1),
                name.substring(0, middle) + name[middle + 1] + name[middle] + name.substring(middle + 2))
            for (typo in typos) {
                slips++
                val fix = packed.correct(typo) ?: continue
                assertFalse("$typo for $name became $fix", fix in medical)
                ordinary += "$typo→$fix"
            }
        }
        println("Look-alike pairs checked: ${checked.size} of ${pairs.size}; slips: $slips, none put into a drug name; " +
            "fixed to an ordinary word as without the pack: $ordinary")
    }

    @Test
    fun `iso dates round trip`() {
        for (date in listOf("1970-01-01", "2000-02-29", "2026-11-13", "2027-01-03", "2100-12-31")) {
            assertEquals(date, WordPack.isoDate(LocalDate.parse(date).toEpochDay()))
            assertEquals(LocalDate.parse(date).toEpochDay(), CurrentVocabulary.epochDay(WordPack.isoDate(LocalDate.parse(date).toEpochDay())))
        }
    }

    @Test
    fun `the settings screen has a switch for every bundled pack and an import`() {
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        assertTrue("KeyboardSettings.setPack(" in screen)
        assertTrue("WordPackCatalog.BUNDLED.forEach" in screen)
        assertTrue("REQUEST_WORD_PACK -> importPack(uri)" in screen)
        val strings = File("src/main/res/values/strings.xml").readText()
        for (bundled in WordPackCatalog.BUNDLED) {
            assertTrue(bundled.id, File("src/main/assets/${bundled.asset}").isFile)
            for (name in listOf("pack_${bundled.id}_title", "pack_${bundled.id}_summary", "pack_${bundled.id}_body")) {
                assertTrue(name, "name=\"$name\"" in strings)
            }
        }
        // Switches: bundled off by default, every bundled key travels in a copy, the import list stays on the phone.
        assertEquals("pack_medical", KeyboardSettings.packKey("medical"))
        assertTrue(WordPackCatalog.BUNDLED.all { KeyboardSettings.packKey(it.id) in KeyboardSettings.portableKeys })
    }
}
