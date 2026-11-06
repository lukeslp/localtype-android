package dev.localtype.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The corrector on the bundled Portuguese lists, with typos made from the lists themselves, measured as Spanish was. The
 * bars were written on 2026-10-18 before the first run, the same as Spanish's: missing accents at least 85% fixed and at
 * most 3% wrong, one slip onto a neighbouring key at least 60% fixed and at most 10% wrong, and at most 0.5% of correctly
 * typed common words changed. No real Portuguese typos are in it yet, and no tap positions.
 */
class CorrectorPortugueseTest {
    private val portuguese = LanguageProfile.PORTUGUESE
    private val ranked: List<String> = File("src/main/assets/words_pt.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }
    private val words = WordDictionary.parse(File("src/main/assets/words_pt.txt").readLines().asSequence(), portuguese)
    private val lexicon = Lexicon.parse(File("src/main/assets/words_known_pt.txt").readLines().asSequence())
    private val corrector = Corrector({ words }, lexicon = { lexicon }, language = { portuguese })

    private fun known(word: String) = word in words || word in lexicon

    private data class Tally(val cases: Int, val fixed: Int, val wrong: Int) {
        val fixedShare get() = fixed.toDouble() / cases
        val wrongShare get() = wrong.toDouble() / cases
        override fun toString() = "$cases cases, $fixed fixed (${"%.1f".format(fixedShare * 100)}%), $wrong wrong (${"%.1f".format(wrongShare * 100)}%)"
    }

    private fun tally(cases: List<Pair<String, String>>): Tally {
        var fixed = 0
        var wrong = 0
        for ((typed, meant) in cases) when (corrector.correct(typed)) {
            null -> Unit
            meant -> fixed++
            else -> wrong++
        }
        return Tally(cases.size, fixed, wrong)
    }

    private fun <T> spread(items: List<T>, count: Int): List<T> =
        if (items.size <= count) items else List(count) { items[it * items.size / count] }

    @Test
    fun `missing accents`() {
        val top = ranked.take(10_000)
        // Only spellings that stand for one word, and whose bare form is not a word itself.
        val byBare = top.filter { w -> w.any { it in portuguese.folds } }.groupBy { portuguese.fold(it) }
        val cases = byBare.filter { (bare, group) -> group.size == 1 && !known(bare) }.map { (bare, group) -> bare to group.single() }
        val result = tally(spread(cases, 400))
        println("Portuguese missing accents: $result (of ${cases.size} unambiguous in the top 10,000; ${byBare.count { it.value.size > 1 }} ambiguous left out)")
        assertTrue("$result", result.fixedShare >= 0.85 && result.wrongShare <= 0.03)
    }

    @Test
    fun `one slip onto a neighbouring key`() {
        val random = Random(20261018)
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val cases = ArrayList<Pair<String, String>>()
        for (word in spread(ranked.take(10_000).filter { it.length in 4..10 && it.all { c -> c in letters } }, 600)) {
            val at = random.nextInt(word.length)
            val near = letters.filter { it != word[at] && Corrector.areNeighbors(word[at], it, portuguese) }
            if (near.isEmpty()) continue
            val typed = word.substring(0, at) + near[random.nextInt(near.length)] + word.substring(at + 1)
            if (!known(typed)) cases += typed to word
            if (cases.size == 400) break
        }
        val result = tally(cases)
        println("Portuguese neighbouring-key slips: $result")
        assertTrue("$result", result.fixedShare >= 0.60 && result.wrongShare <= 0.10)
    }

    @Test
    fun `common words typed right stay as typed`() {
        val common = ranked.take(3_000).filter { it.length >= 2 }
        val changed = common.filter { corrector.correct(it) != null }
        println("Portuguese common words changed: ${changed.size} of ${common.size}")
        assertTrue("${changed.size} of ${common.size} changed", changed.size <= common.size * 0.005)
    }
}
