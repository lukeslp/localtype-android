package dev.localtype.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * No bundled list may hold a slur, or it can turn up as a completion or a correction whatever the
 * swear setting. The slurs are kept here as SHA-256 digests of their lowercase forms, so the words
 * themselves never appear in the source. Every list is checked against the digests every language
 * shares; a language's own lists (`_es` in the name) against that language's as well.
 */
class BundledWordListsTest {
    @Test
    fun `no bundled list contains a slur`() {
        val lists = File("src/main/assets").listFiles { file -> file.extension == "txt" }.orEmpty().sortedBy { it.name }
        assertTrue(lists.map { it.name }.containsAll(listOf("words_en.txt", "words_known.txt", "emoji_en.txt", "emoji_inventory.txt",
            "words_es.txt", "words_es_profanity.txt", "words_known_es.txt",
            "words_pt.txt", "words_pt_profanity.txt", "words_known_pt.txt",
            "words_he.txt", "words_he_profanity.txt", "words_known_he.txt",
            "words_cy.txt", "words_known_cy.txt", "words_ga.txt", "words_known_ga.txt",
            "words_mi.txt", "words_known_mi.txt", "words_haw.txt", "words_known_haw.txt") +
            LanguageProfile.EXTRA.flatMap { listOf(it.wordsAsset, it.profanityAsset, it.lexiconAsset) }))
        var checked = 0
        var checkedSpanish = 0
        var checkedPortuguese = 0
        var checkedHebrew = 0
        val found = ArrayList<String>()
        for (list in lists) {
            val spanish = list.name.endsWith("_es.txt") || list.name.contains("_es_")
            val portuguese = list.name.endsWith("_pt.txt") || list.name.contains("_pt_")
            val hebrew = list.name.endsWith("_he.txt") || list.name.contains("_he_")
            val own = when {
                spanish -> SuggestionWordRules.EXCLUDED_HASHES_ES
                portuguese -> SuggestionWordRules.EXCLUDED_HASHES_PT
                hebrew -> SuggestionWordRules.EXCLUDED_HASHES_HE
                // The languages added later name their lists by code alone.
                else -> LanguageProfile.EXTRA.firstOrNull { list.name.endsWith("_${it.code}.txt") || list.name.contains("_${it.code}_") }
                    ?.let { SuggestionWordRules.forLanguage(it) }.orEmpty()
            }
            list.readLines().forEachIndexed { index, line ->
                if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
                // The entry before any tab, and each word of a phrase or hyphenated compound.
                val entry = line.substringBefore('\t').trim().lowercase(Locale.ROOT)
                val forms = listOf(entry) + entry.split(' ', '-').filter { it.isNotEmpty() }
                checked++
                if (spanish) checkedSpanish++
                if (portuguese) checkedPortuguese++
                if (hebrew) checkedHebrew++
                if (forms.any { sha256(it).let { hash -> hash in SuggestionWordRules.EXCLUDED_HASHES || hash in own } }) found += "${list.name}:${index + 1}"
            }
        }
        assertTrue(checked > 100_000)
        assertTrue(checkedSpanish > 100_000)
        assertTrue(checkedPortuguese > 100_000)
        assertTrue(checkedHebrew > 100_000)
        // Only the location is reported, never the word.
        assertTrue("Slurs at $found", found.isEmpty())
    }

    @Test
    fun `the keyboard's exclusion check matches the list scan`() {
        // A Spanish slur is excluded only in Spanish; the shared ones everywhere. The digests stand in for the words.
        val spanishOnly = SuggestionWordRules.EXCLUDED_HASHES_ES
        assertTrue(spanishOnly.size >= 40 && spanishOnly.none { it in SuggestionWordRules.EXCLUDED_HASHES })
        assertTrue(SuggestionWordRules.forLanguage(LanguageProfile.ENGLISH).isEmpty())
        assertTrue(SuggestionWordRules.forLanguage(LanguageProfile.SPANISH) === spanishOnly)
        val portugueseOnly = SuggestionWordRules.EXCLUDED_HASHES_PT
        assertTrue(portugueseOnly.size >= 40 && portugueseOnly.none { it in SuggestionWordRules.EXCLUDED_HASHES })
        assertTrue(SuggestionWordRules.forLanguage(LanguageProfile.PORTUGUESE) === portugueseOnly)
        val hebrewOnly = SuggestionWordRules.EXCLUDED_HASHES_HE
        assertTrue(hebrewOnly.size >= 30 && hebrewOnly.none { it in SuggestionWordRules.EXCLUDED_HASHES })
        assertTrue(SuggestionWordRules.forLanguage(LanguageProfile.HEBREW) === hebrewOnly)
        for ((language, own) in listOf(LanguageProfile.ARABIC to SuggestionWordRules.EXCLUDED_HASHES_AR,
            LanguageProfile.PERSIAN to SuggestionWordRules.EXCLUDED_HASHES_FA)) {
            assertTrue(language.code, own.size >= 8 && own.none { it in SuggestionWordRules.EXCLUDED_HASHES })
            assertTrue(language.code, SuggestionWordRules.forLanguage(language) === own)
        }
        assertTrue(SuggestionWordRules.forLanguage(LanguageProfile.IPA).isEmpty())
    }

    /** The scan above sees only the emoji inventory's first column; its names and keywords are words too (search will match them). */
    @Test
    fun `no emoji name or keyword is a slur`() {
        val found = ArrayList<Int>()
        var checked = 0
        File("src/main/assets/emoji_inventory.txt").readLines().forEachIndexed { index, line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val fields = line.split('\t')
            val phrases = listOf(fields[2]) + fields[3].split('|')
            val forms = phrases.flatMap { phrase ->
                val lower = phrase.lowercase(Locale.ROOT)
                listOf(lower) + lower.split(Regex("[^\\p{L}'’]+")).filter { it.isNotEmpty() }
            }
            checked++
            if (forms.any { sha256(it) in SuggestionWordRules.EXCLUDED_HASHES }) found += index + 1
        }
        assertTrue(checked > 1_900)
        assertTrue("Slurs at emoji_inventory.txt lines $found", found.isEmpty())
    }

    private fun sha256(word: String): String =
        MessageDigest.getInstance("SHA-256").digest(word.toByteArray()).joinToString("") { "%02x".format(it) }

}
