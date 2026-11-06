package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class CorrectionTextContextTest {
    @Test fun matchesExistingRulesAtUnicodeAndPunctuationBoundaries() {
        val contexts = listOf("", "hi", "hello ", "hello\t", "done. ", "what?", "go!\n", "hi, ",
            "friend’s ", "parents' ", "well-being ", "'quoted ", "--word ", "123 ", "👨‍👩‍👧 ",
            "café ", "e\u0301 ", "𐐀abc ", "abc𐐀 ", "\uD800", "\uDC00", "\u00A0", "\r", "\n")
        for (context in contexts) for (word in listOf("", "qpalzm", "don't", "parents'", "𐐀", "’--", "123", "🐙")) {
            assertEquivalent(context + word)
        }
    }

    @Test fun matchesExistingRulesForDeterministicMixedText() {
        val random = Random(71)
        val pieces = listOf("alpha", "beta", "𐐀", "é", "中", "'", "’", "-", " ", "\t", "\n", ".", ",", "!", "?", "123", "🐙", "\u0301", "\uD800")
        repeat(2000) {
            assertEquivalent(buildString { repeat(random.nextInt(80)) { append(pieces[random.nextInt(pieces.size)]) } })
        }
    }

    private fun assertEquivalent(text: String) {
        val actual = CorrectionTextContext.capture(text)
        assertEquals(text, AdaptiveNgramModel.lastCompletedWord(text), actual.previous)
        assertEquals(text, AdaptiveNgramModel.isSentenceStart(text), actual.sentenceStart)
    }
}
