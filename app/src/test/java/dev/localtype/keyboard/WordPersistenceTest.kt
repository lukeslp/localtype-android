package dev.localtype.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class WordPersistenceTest {
    private fun coordinator() = PersonalLearningCoordinator({ _, _, _ -> null }, { false })

    @Test fun `prose in address bars learns without opening autocorrect or the log field gate`() {
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        assertTrue(WordPersistence.field(TypingRules.allowsLearning(0, uri), true,
            TypingRules.wantsSuggestions(uri), TypingRules.predictsInAddressBar(uri)))
        assertFalse(TypingRules.wantsSuggestions(uri))
        assertFalse(TypingRules.allowsAutocorrect(uri))
        assertFalse(WordPersistence.field(TypingRules.allowsLearning(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, uri),
            true, false, true))
        assertFalse(WordPersistence.field(true, false, true, false))
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertFalse(WordPersistence.field(TypingRules.allowsLearning(0, password), false, false, false))
        assertTrue(DomainTyping.protected("github", ".com/path"))
        assertFalse(Expansions.proseRun("github.com/path"))
    }

    @Test fun `a foreign boundary cannot reach ngram tables and learning resumes with the next own word`() {
        val own = coordinator()
        val model = AdaptiveNgramModel(seedPhrases = emptyList(), sentenceStarters = emptyList())
        val before = "inventedsecret london "
        assertFalse(WordPersistence.pair(true, true, "london", own.context(before).lastOrNull()))
        own.complete(before, "see", "see", LearningOrigin.TYPED, true, true)
        val canLearn = WordPersistence.pair(true, true, "see", own.context(before + "see ").lastOrNull())
        assertTrue(canLearn)
        if (canLearn) model.accept("see", "you")
        assertEquals(0, model.pairCount("london", "see"))
        assertEquals(1, model.pairCount("see", "you"))
        assertFalse(WordPersistence.pair(true, false, null, null)) // pasted prefix, even at sentence start
        assertFalse(WordPersistence.pair(false, true, "see", "see"))
        assertTrue(WordPersistence.pair(true, true, null, null))
        assertTrue(WordPersistence.pair(true, true, "It’s", "it's"))
    }

    @Test fun `all ownership resets and recreation withdraw context even if the editor still contains it`() {
        for (reset in PersonalContextTrace.ResetReason.entries) {
            val own = coordinator()
            own.complete("", "hello", "hello", LearningOrigin.TYPED, true, true)
            val request = own.modelRequest("hello ", true)
            assertTrue(request.mayPersist)
            own.reset(reset)
            assertFalse(request.persists(own.generation))
            assertFalse(WordPersistence.pair(true, true, "hello", own.context("hello ").lastOrNull()))
        }
        assertFalse(coordinator().modelRequest("hello ", true).mayPersist)
    }

    @Test fun `request permission is frozen and stale lookahead does not damage live ownership`() {
        val own = coordinator()
        val unsafe = own.modelRequest("pasted ", true)
        own.complete("pasted ", "hello", "hello", LearningOrigin.TYPED, true, true)
        assertFalse(unsafe.persists(own.generation))
        assertFalse(own.modelRequest("pasted hello ", true).mayPersist)
        val generation = own.generation
        assertFalse(own.modelRequest("pasted hello tomorrow ", true).mayPersist)
        assertEquals(generation, own.generation)
        assertEquals(listOf("hello"), own.context("pasted hello "))
    }

    @Test fun `only the complete verified model input may persist not a matching final key`() {
        assertTrue(WordPersistence.modelContext("See you ", listOf("see", "you"), true))
        assertFalse(WordPersistence.modelContext("secret see you ", listOf("see", "you"), true))
        assertFalse(WordPersistence.modelContext("123 see you ", listOf("see", "you"), true))
        assertFalse(WordPersistence.modelContext("see you ", listOf("see", "you"), false))
        assertFalse(WordPersistence.modelContext("see you tomorrow ", listOf("see", "you"), true))
    }

    @Test fun `punctuation and undo cannot resurrect a prior completion for a repaired pair`() {
        val state = PersonalLearningState()
        val own = PersonalLearningCoordinator(state::record, state::undo)
        own.complete("", "hello", "hello", LearningOrigin.TYPED, true, true, sentenceEnded = true)
        assertTrue(own.context("hello. ").isEmpty())
        val repair = own.repair(emptyList(), "world", true)
        assertEquals(0, state.entries().size)
        own.undo(repair)
        assertTrue(own.context("hello. world ").isEmpty())
    }
}
