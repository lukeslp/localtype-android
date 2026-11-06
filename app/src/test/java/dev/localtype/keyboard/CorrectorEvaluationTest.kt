package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Catch rate and wrong fixes over fixed typo sets, so tuning can't quietly get worse. At 0.4.2 the
 * corrector fixed 128 of the real typos with 6 wrong, 72 of the simulated ones with 58 wrong, and
 * changed 6 of the guard words.
 */
class CorrectorEvaluationTest {
    private val corrector = Corrector({ TypoCorpus.dictionary }, lexicon = { TypoCorpus.lexicon })

    private data class Tally(val fixed: Int, val wrong: Int)

    private fun tally(
        cases: List<Pair<String, String>>,
        engine: Corrector = corrector,
        touches: (Int) -> List<Touch?>? = { null },
    ): Tally {
        var fixed = 0
        var wrong = 0
        cases.forEachIndexed { i, (typed, meant) ->
            when (engine.correct(typed, touches(i))) {
                null -> Unit
                meant -> fixed++
                else -> wrong++
            }
        }
        return Tally(fixed, wrong)
    }

    @Test
    fun `real typos`() {
        val result = tally(TypoCorpus.real)
        println("Real typos (${TypoCorpus.real.size}): $result")
        assertTrue("$result", result.fixed >= 134 && result.wrong <= 1)
    }

    @Test
    fun `simulated thumbs with tap positions`() {
        val samples = TypoCorpus.simulated(400)
        val result = tally(samples.map { it.typed to it.meant }) { samples[it].touches }
        println("Simulated (${samples.size}): $result")
        assertTrue("$result", result.fixed >= 176 && result.wrong <= 34)
    }

    @Test
    fun `learned offsets improve a thumb biased down and right without changing real typo results`() {
        val offsets = TouchOffsets()
        // These correctly typed real words cover all 26 keys. Their +0.2 lifts remain on each key,
        // so calibration learns ordinary successful typing, not the answers to the typo samples.
        "the quick brown fox jumps over the lazy dog".split(' ').forEach { word ->
            val touches = word.map { KeyGeometry.touchOn(it, 0.2f, 0.2f)!! }
            assertEquals(word, touches.map(KeyGeometry::nearest).joinToString(""))
            assertEquals(null, corrector.correct(word, touches))
            assertTrue(offsets.record(word, touches))
        }
        val calibrated = Corrector(
            { TypoCorpus.dictionary },
            lexicon = { TypoCorpus.lexicon },
            touchOffsets = { offsets },
        )
        val samples = biasedThumbSamples(400)
        val cases = samples.map { it.typed to it.meant }
        val before = tally(cases) { samples[it].touches }
        val after = tally(cases, calibrated) { samples[it].touches }
        println("Biased thumb +0.2 right/down (${samples.size}): before=$before; learned offsets=$after")
        assertTrue("before=$before; after=$after", after.fixed >= 155 && after.wrong <= 29 && after.fixed > before.fixed && after.wrong <= before.wrong)

        // Real typos have no recorded lift positions. Learning must not invent offsets for them.
        val realBefore = tally(TypoCorpus.real)
        val realAfter = tally(TypoCorpus.real, calibrated)
        println("Real typos with learned offsets (${TypoCorpus.real.size}): before=$realBefore; after=$realAfter")
        assertEquals(realBefore, realAfter)
        assertTrue("$realAfter", realAfter.fixed >= 134 && realAfter.wrong <= 1)
    }

    /** The existing thumb simulation's seed, spread and skip/double rates, with a +0.2 lift bias. */
    private fun biasedThumbSamples(count: Int): List<TypoCorpus.Sample> {
        val random = Random(7)
        val pool = TypoCorpus.dictionary.allWords().filter { word ->
            word.length >= 3 && word.all { it in 'a'..'z' } && TypoCorpus.dictionary.score(word) >= 45
        }
        val samples = ArrayList<TypoCorpus.Sample>()
        fun gaussian(): Float {
            val u = random.nextDouble().coerceAtLeast(1e-9)
            val v = random.nextDouble()
            return (kotlin.math.sqrt(-2 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)).toFloat()
        }
        while (samples.size < count) {
            val meant = pool[random.nextInt(pool.size)]
            val typed = StringBuilder()
            val touches = ArrayList<Touch>()
            for (letter in meant) {
                val roll = random.nextFloat()
                if (roll < 0.02f) continue
                repeat(if (roll > 0.98f) 2 else 1) {
                    val landing = KeyGeometry.touchOn(letter, gaussian() * 0.42f + 0.2f, gaussian() * 0.42f + 0.2f)!!
                    val touch = Touch(landing.x.coerceIn(0f, 10f), landing.y.coerceIn(0f, 2.99f))
                    touches += touch
                    // The bias changes the measured lift and hit-test together. Calibration only
                    // rescoring these same taps cannot change the letters the person typed.
                    typed.append(KeyGeometry.nearest(touch))
                }
            }
            val word = typed.toString()
            if (word == meant || word.length < 2 || word in TypoCorpus.dictionary || word in TypoCorpus.lexicon) continue
            samples += TypoCorpus.Sample(word, meant, touches)
        }
        return samples
    }

    @Test
    fun `guard words stay as typed`() {
        val unknown = TypoCorpus.guard.filter { it !in TypoCorpus.dictionary && it !in TypoCorpus.lexicon }
        val changed = unknown.mapNotNull { word -> corrector.correct(word)?.let { "$word→$it" } }
        println("Guard words in neither list: ${unknown.size}, changed: $changed; recognized words: ${TypoCorpus.lexicon.size}")
        assertTrue("$changed", changed.size <= 3)
    }
}
