package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NeuralContextTest {
    @Test
    fun `reading from the sentence start preserves tokenizer context and prefix`() {
        for (text in listOf("hello there ", "Done. hello t", "Wait?!  hello ", "thanks\nhello t", "Wait… now ", "Done。 now ", "What？ now ", "Done！ now ", "price 3.5 dollars ", "at 12.30.45 now ", "Wait‥ now ", "Old. it costs 3.5 dollars n")) {
            assertEquals(text, CifgTokenizer.context(text), CifgTokenizer.context(NeuralContext.sentence(text)))
        }
        assertEquals(" hello t", NeuralContext.sentence("Done. hello t"))
        assertEquals("price 3.5 dollars ", NeuralContext.sentence("price 3.5 dollars "))
    }

    @Test
    fun `a growing sentence keeps the same start past the old short read bound`() {
        val text = "Old sentence. " + "word ".repeat(100)
        assertEquals(" " + "word ".repeat(100), NeuralContext.sentence(text))
        assertEquals(NeuralContext.sentence(text) + "next ", NeuralContext.sentence(text + "next "))
    }

    @Test
    fun `reads remain bounded without splitting a Unicode character at the start`() {
        val text = "prefix😀" + "w".repeat(NeuralContext.MAX_READ_CHARS - 1)
        val bounded = NeuralContext.sentence(text)
        assertEquals(NeuralContext.MAX_READ_CHARS - 1, bounded.length)
        assertFalse(bounded.first().isLowSurrogate())
    }
}
