package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BritishSpellingTest {
    private val assets = File("src/main/assets")
    private val spelling = File(assets, BritishSpelling.ASSET).useLines { BritishSpelling.parse(it) }

    private fun words(name: String) = File(assets, name).readLines()
        .filter { it.isNotEmpty() && !it.startsWith("#") }.mapTo(HashSet()) { it.substringBefore('\t') }

    @Test
    fun `common American spellings come out British`() {
        val expected = mapOf(
            "color" to "colour", "favorite" to "favourite", "organize" to "organise", "realized" to "realised",
            "center" to "centre", "theater" to "theatre", "traveling" to "travelling", "gray" to "grey",
            "defense" to "defence", "analyze" to "analyse", "jewelry" to "jewellery", "mom" to "mum",
        )
        expected.forEach { (us, gb) -> assertEquals(us, gb, spelling.of(us)) }
    }

    @Test
    fun `words whose British spelling depends on the meaning stay as they are`() {
        // Software is a program in Britain too, check and tire are also verbs, a meter also measures.
        for (word in listOf("program", "check", "tire", "meter", "license", "practice", "among", "learned", "the", "colour")) {
            assertEquals(word, spelling.of(word))
        }
    }

    @Test
    fun `capitals and possessives are kept`() {
        assertEquals("Colour", spelling.of("Color"))
        assertEquals("COLOUR", spelling.of("COLOR"))
        assertEquals("neighbour's", spelling.of("neighbor's"))
        assertEquals("Neighbour’s", spelling.of("Neighbor’s"))
        assertEquals("", spelling.of(""))
    }

    @Test
    fun `two words that read the same once spelled British show once`() {
        val strip = BritishSpelling.distinct(listOf("color", "colour", "colors").map(spelling::of))
        assertEquals(listOf("colour", "colours"), strip)
        assertEquals(listOf("", "colour", ""), BritishSpelling.distinct(listOf("", "colour", "")))
    }

    @Test
    fun `every pair is a word the strip offers and a word the corrector accepts`() {
        val offerable = words("words_en.txt") + words("words_en_modern.txt")
        val known = words("words_known.txt")
        val lines = File(assets, BritishSpelling.ASSET).readLines().filter { !it.startsWith("#") }
        assertTrue(lines.size in 150..400)
        lines.forEach { line ->
            val (us, gb) = line.split('\t')
            assertTrue(line, us in offerable)
            assertTrue(line, gb in known)
        }
        assertEquals(lines.size, spelling.size)
    }

    @Test
    fun `parse skips comments and malformed lines`() {
        val parsed = BritishSpelling.parse(sequenceOf("# header", "", "color\tcolour", "broken", "\tx", "y\t", "same\tsame"))
        assertEquals(1, parsed.size)
        assertEquals("colour", parsed.of("color"))
        assertEquals("color", BritishSpelling.EMPTY.of("color"))
    }
}
