package dev.localtype.keyboard

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packs that ship: each reads strictly, matches the catalog, names every source with its license page and the date
 * it was read, and is recorded in the asset license inventory. Slurs are checked with every other list in
 * [BundledWordListsTest]; the corrector's gates with every pack on are in [CorrectorEvaluationTest].
 */
class BundledWordPacksTest {
    private fun text(bundled: WordPackCatalog.Bundled) = File("src/main/assets/${bundled.asset}").readText()

    @Test
    fun `every bundled pack reads strictly and matches its catalog entry`() {
        for (bundled in WordPackCatalog.BUNDLED) {
            val pack = WordPack.strict(text(bundled))
            assertEquals(bundled.id, pack.id)
            assertEquals(bundled.language, pack.language)
            assertTrue(bundled.id, pack.size in 100..WordPack.MAX_ENTRIES)
            assertTrue(bundled.id, pack.observedDay != null)
            // No everyday suggestion word is in a pack: a pack only adds words the keyboard didn't complete.
            val everyday = TypoCorpus.dictionary
            assertTrue(bundled.id, pack.words.none { it in everyday })
            assertTrue(bundled.id, pack.words.all { it.count(Char::isLetter) >= 4 })
        }
        val medical = WordPack.strict(text(WordPackCatalog.bundled("medical")!!))
        assertTrue(medical.count(WordPack.Kind.DRUG) > 1_000 && medical.count(WordPack.Kind.TERM) > 500)
        assertEquals(0, medical.count(WordPack.Kind.OFFICEHOLDER))
        for (word in listOf("lisinopril", "atorvastatin", "hydroxyzine", "hydralazine", "metformin", "tachycardia")) assertTrue(word, word in medical)
        // Luke, 2026-11-13: drug names from five typed letters (ECRI/ISMP's guidance for drug-name searches), the rest from three.
        assertEquals(5, medical.minPrefix(WordPack.Kind.DRUG))
        assertEquals(3, medical.minPrefix(WordPack.Kind.TERM))
        val day = java.time.LocalDate.parse("2026-11-13").toEpochDay()
        val medicalOn = WordPacks.of(listOf(medical), day)
        assertTrue(medicalOn.candidates("lisi").none { it.first == "lisinopril" })
        assertTrue(medicalOn.candidates("lisin").any { it.first == "lisinopril" })
        assertTrue(medicalOn.candidates("hydr").none { medical.entry(it.first)!!.kind == WordPack.Kind.DRUG })
        assertTrue(medicalOn.candidates("tac").any { it.first == "tachycardia" })
        assertTrue(medicalOn.candidates("ost").any { it.first == "osteoporosis" })
        val politics = WordPack.strict(text(WordPackCatalog.bundled("politics")!!))
        for (kind in WordPack.Kind.entries) assertEquals(kind.id, 3, politics.minPrefix(kind))
        val politicsOn = WordPacks.of(listOf(politics), day)
        assertTrue(politicsOn.candidates("clo").any { it.first == "cloture" })
        assertTrue(politicsOn.candidates("sch").any { it.first == "schumer" })
        assertTrue(politics.count(WordPack.Kind.OFFICEHOLDER) > 300 && politics.count(WordPack.Kind.OFFICE) > 50)
        for (word in listOf("cloture", "filibuster", "schumer", "thune", "sotomayor")) assertTrue(word, word in politics)
        assertEquals("Schumer", politics.entry("schumer")!!.display)
    }

    @Test
    fun `each pack header names its sources, license pages and the day they were read`() {
        val pins = File("../distribution/source/packs/sources.json").readText()
        for (bundled in WordPackCatalog.BUNDLED) {
            val header = text(bundled).lineSequence().takeWhile { it.startsWith("#") }.toList()
            val sources = header.filter { it.startsWith("# - ") }
            assertTrue(bundled.id, sources.size >= 3)
            for (line in sources) {
                assertTrue(line, Regex("https?://\\S+").findAll(line).count() >= 1)
                assertTrue(line, "read 2026-11-13" in line)
            }
            assertTrue(bundled.id, header.any { it.startsWith("# observed=") })
            assertTrue(bundled.id, "\"${bundled.id}\"" in pins)
        }
        assertTrue(text(WordPackCatalog.bundled("medical")!!).contains("NLM is not responsible for the product and does not endorse"))
        assertTrue(text(WordPackCatalog.bundled("medical")!!).contains("Source: MedlinePlus, National Library of Medicine."))
    }

    @Test
    fun `each pack is in the asset license inventory with its digest`() {
        // The public source carries the same inventory as ASSET-LICENSES.json at its root.
        val inventory = listOf(File("../distribution/source/asset-licenses.json"), File("../ASSET-LICENSES.json"))
            .first { it.isFile }.readText()
        for (bundled in WordPackCatalog.BUNDLED) {
            val digest = MessageDigest.getInstance("SHA-256").digest(File("src/main/assets/${bundled.asset}").readBytes())
                .joinToString("") { "%02x".format(it) }
            assertTrue(bundled.asset, "\"${bundled.asset}\"" in inventory)
            assertTrue("${bundled.asset} digest $digest", digest in inventory)
        }
    }
}
