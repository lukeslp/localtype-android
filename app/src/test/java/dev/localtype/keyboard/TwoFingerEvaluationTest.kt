package dev.localtype.keyboard

import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two-finger swipes against typing, through [TwoFingerReplay]'s copy of the view's pairing rules. Two thumbs typing
 * the MacKenzie and Soukoreff phrases run every time; recorded FUTO development swipes run when `LOCALTYPE_GESTURES`
 * names the prepared dev table (`LOCALTYPE_GESTURE_ROWS` takes the first N). Typing must give zero swipes, zero
 * activations and every key once, in order. Figures go to `build/reports/gesture-eval/two-finger*.txt`.
 */
class TwoFingerEvaluationTest {
    // The phrase set lives with the editor fixture, which the public source snapshot leaves out; there these tests skip.
    private val phraseFile = File("../verification/editor-fixture/phrases/mackenzie-soukoreff-2003.txt")
    private val phrases by lazy {
        org.junit.Assume.assumeTrue("MacKenzie and Soukoreff phrases not in this checkout", phraseFile.isFile)
        phraseFile.readLines().filter { it.isNotBlank() }
    }

    private fun percentile(values: List<Long>, p: Double): Long =
        if (values.isEmpty()) 0 else values.sorted()[((values.size - 1) * p).toInt()]

    private fun write(name: String, lines: List<String>) {
        val file = File("build/reports/gesture-eval/$name.txt")
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n") + "\n")
        println(lines.joinToString("\n") + "\nwritten to $file")
    }

    @Test
    fun `two thumbs typing never make a swipe and every key types once, in order`() {
        val lines = mutableListOf("Two thumbs typing ${phrases.size} MacKenzie-Soukoreff phrases, drawn timing, seed 17 per speed")
        for (speed in TwoFingerReplay.SPEEDS) {
            val random = Random(17)
            var presses = 0
            var overlaps = 0
            var pairs = 0
            val held = ArrayList<Long>()
            for (phrase in phrases) {
                val typed = TwoFingerReplay.typing(phrase, speed, random)
                presses += typed.size
                overlaps += typed.zipWithNext().count { (a, b) -> b.down < a.up }
                val touches = TwoFingerReplay.touches(typed)
                val on = TwoFingerReplay.run(touches, gestures = true)
                val off = TwoFingerReplay.run(touches, gestures = false)
                assertEquals("$phrase at ${speed.name}: no swipe", emptyList<TwoFingerSwipe>(), on.swipes)
                assertEquals("$phrase at ${speed.name}: no activation", 0, on.activations)
                assertEquals("$phrase at ${speed.name}: every key once, in order", typed.indices.toList(), on.typed)
                assertEquals("gestures off types the same keys", off.typed, on.typed)
                assertEquals(0, off.pairs)
                pairs += on.pairs
                held += on.held
            }
            lines += "%-8s %6d presses, %5d overlapping the one before, %5d held back (%.1f%%); later by median %d ms, 95th %d ms, most %d ms".format(
                speed.name, presses, overlaps, pairs, 100.0 * pairs / presses, percentile(held, 0.5), percentile(held, 0.95), held.maxOrNull() ?: 0)
            assertTrue(presses > 10_000)
        }
        write("two-finger-typing", lines)
    }

    @Test
    fun `drawn two-finger swipes are recognized in all four directions`() {
        val random = Random(23)
        val lines = mutableListOf("Drawn two-finger swipes, 500 a direction, seed 23 (not people's gestures)")
        var total = 0
        var right = 0
        for (direction in TwoFingerSwipe.entries) {
            var recognized = 0
            var typedSomething = 0
            repeat(500) {
                val result = TwoFingerReplay.run(TwoFingerReplay.touches(TwoFingerReplay.drawnSwipe(direction, random)))
                if (result.swipes == listOf(direction)) recognized++
                if (result.typed.isNotEmpty()) typedSomething++
                assertTrue("a swipe is never read as another direction", result.swipes.all { it == direction })
            }
            total += 500
            right += recognized
            lines += "%-6s recognized %d/500, typed a key in %d".format(direction.name.lowercase(), recognized, typedSomething)
        }
        write("two-finger-drawn", lines)
        assertTrue("drawn swipes fell below 95%: $right/$total", right >= total * 0.95)
    }

    /** Recorded swipes: alone, with the other thumb tapping during them, and two at once (a stress case, reported only). */
    @Test
    fun `recorded swipes with a tap from the other thumb never make a swipe`() {
        val path = System.getenv("LOCALTYPE_GESTURES")?.trim().orEmpty()
        assumeTrue("set LOCALTYPE_GESTURES to the prepared FUTO dev table", path.isNotEmpty())
        val file = File(path)
        assumeTrue("$file is not a file", file.isFile)
        // The held-out split is scored once, for swipe typing, after its bar is agreed; this check never reads it.
        assertTrue("only the development table may be used here", "test" !in file.name)
        val rows = System.getenv("LOCALTYPE_GESTURE_ROWS")?.trim()?.toIntOrNull() ?: Int.MAX_VALUE
        val swipes = GestureEvaluation.read(file, rows).mapNotNull { TwoFingerReplay.recorded(it, 1_000) }.toList()
        val offsets = listOf(-60L, 0L, 60L, 120L, 180L, 240L)
        var alone = 0
        var tapped = 0
        var pairs = 0
        val held = ArrayList<Long>()
        for (swipe in swipes) {
            val single = TwoFingerReplay.run(TwoFingerReplay.touches(listOf(swipe)))
            assertEquals(0, single.pairs + single.activations + single.swipes.size)
            assertEquals(listOf(0), single.typed)
            alone++
            for (offset in offsets) {
                val down = swipe.down + offset
                if (offset >= 0 && down >= swipe.up) continue
                // The other thumb taps a home-row key on the far side of the keyboard.
                val x = if (swipe.x0 < 5f) 7.5f else 2.5f
                val y = 1.5f * TwoFingerReplay.ROW
                val tap = TwoFingerReplay.Press(down, down + 90, x, y, x + 0.05f, y + 0.05f)
                val result = TwoFingerReplay.run(TwoFingerReplay.touches(listOf(swipe, tap)))
                assertEquals("swipe at offset $offset: no swipe", emptyList<TwoFingerSwipe>(), result.swipes)
                assertEquals("swipe at offset $offset: no activation", 0, result.activations)
                assertEquals("swipe at offset $offset: both type, in order", listOf(0, 1), result.typed)
                tapped++
                pairs += result.pairs
                held += result.held
            }
        }
        // Two recorded swipes at once is not typing; it shows how selective the rules are on real, curved paths.
        var stressRuns = 0
        var stressActive = 0
        var stressSwipes = 0
        for ((a, b) in swipes.zipWithNext()) for (offset in listOf(0L, 100L, 200L)) {
            val shift = a.down + offset - b.down
            val shifted = TwoFingerReplay.Press(b.down + shift, b.up + shift, b.x0, b.y0, b.x1, b.y1,
                b.path.map { (t, x, y) -> Triple(t + shift, x, y) })
            val result = TwoFingerReplay.run(TwoFingerReplay.touches(listOf(a, shifted)))
            stressRuns++
            if (result.activations > 0) stressActive++
            stressSwipes += result.swipes.size
        }
        write("two-finger-recorded-${file.nameWithoutExtension}" + (if (rows != Int.MAX_VALUE) "-first$rows" else ""), listOf(
            "${file.name}: ${swipes.size} recorded swipes",
            "alone: $alone, none paired, none a swipe",
            "with an other-thumb tap at ${offsets.joinToString()} ms: $tapped cases, 0 activations, 0 swipes, every key typed once in order",
            "  pairs formed $pairs; held keys later by median ${percentile(held, 0.5)} ms, 95th ${percentile(held, 0.95)} ms",
            "stress, two recorded swipes at once at 0, 100, 200 ms: $stressRuns cases, $stressActive activated, $stressSwipes read as a two-finger swipe",
        ))
        assertTrue(alone > 0)
    }
}
