package dev.localtype.keyboard

import dev.localtype.keyboard.FoundationModelStatus.INITIALIZING
import dev.localtype.keyboard.FoundationModelStatus.MODEL_FILE_MISSING
import dev.localtype.keyboard.FoundationModelStatus.NOT_SUPPORTED
import dev.localtype.keyboard.FoundationModelStatus.READY
import dev.localtype.keyboard.FoundationModelStatus.SYSTEM_MODEL_DOWNLOADABLE
import dev.localtype.keyboard.FoundationModelStatus.UNAVAILABLE
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelChatTest {
    private fun person(text: String) = ChatLine(ChatLine.Kind.PERSON, text)
    private fun model(text: String) = ChatLine(ChatLine.Kind.MODEL, text, "Gemma")
    private fun note() = ChatLine(ChatLine.Kind.NOTE, "", "Gemma")

    /** Answers with [statuses] in turn (the last one repeats) and [answer] for every prompt. */
    private class FakeProvider(
        override val label: String,
        vararg statuses: FoundationModelStatus,
        private val answer: String? = null,
    ) : FoundationModelProvider {
        private val queue = ArrayDeque(statuses.toList())
        var statusCalls = 0
        val prompts = mutableListOf<String>()

        override suspend fun status(): FoundationModelStatus {
            statusCalls++
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }

        override suspend fun predict(context: String, limit: Int, avoid: List<String>): List<String> = emptyList()

        override suspend fun generate(prompt: String): String? {
            prompts += prompt
            return answer
        }
    }

    @Test
    fun `prompt lists the conversation oldest first and leaves out notes`() {
        val prompt = ModelChat.prompt(listOf(person("hi"), model("Hello!"), person("tell me a joke"), note(), person("still there?")))
        val conversation = prompt.substringAfter("\n\n")
        assertEquals("Person: hi\nYou: Hello!\nPerson: tell me a joke\nPerson: still there?", conversation)
        assertTrue(prompt.startsWith("You are a friendly assistant built into LocalType"))
    }

    @Test
    fun `prompt keeps the newest lines that fit`() {
        val lines = (1..40).flatMap { listOf(person("question number $it, padded out a little"), model("answer number $it, also padded")) }
        val prompt = ModelChat.prompt(lines, maxChars = 800)
        assertTrue(prompt.length <= 800)
        assertTrue(prompt.endsWith("You: answer number 40, also padded"))
        assertFalse(prompt.contains("question number 1,"))
        // Whole lines only, in order.
        val kept = prompt.substringAfter("\n\n").lines()
        assertEquals(kept.sortedBy { Regex("\\d+").find(it)!!.value.toInt() * 2 + if (it.startsWith("You")) 1 else 0 }, kept)
    }

    @Test
    fun `the latest message is always kept, cut to fit when it must be`() {
        val long = "x".repeat(5_000)
        val prompt = ModelChat.prompt(listOf(person("earlier"), person(long)), maxChars = 1_000)
        assertTrue(prompt.length <= 1_000)
        assertTrue(prompt.substringAfter("\n\n").startsWith("Person: xxx"))
        assertFalse(prompt.contains("earlier"))
    }

    @Test
    fun `replies lose speaker labels, invented next lines and Markdown`() {
        assertEquals("Hello there.", ModelChat.cleanReply("You: Hello there."))
        assertEquals("Sure, here it is.", ModelChat.cleanReply("Gemma: Sure, here it is.\nPerson: thanks\nYou: welcome"))
        assertEquals("A bold idea.", ModelChat.cleanReply("A **bold** idea."))
        assertEquals("Title\nBody", ModelChat.cleanReply("## Title\nBody"))
        assertEquals("One\n\nTwo", ModelChat.cleanReply("One\n\n\n\nTwo"))
        assertNull(ModelChat.cleanReply("   "))
        assertNull(ModelChat.cleanReply(null))
        assertNull(ModelChat.cleanReply("Person: I'll answer for you"))
    }

    @Test
    fun `availability prefers ready, then loading, then failed`() {
        assertEquals(ModelChat.Availability(ModelChat.State.READY, 1), ModelChat.availability(listOf(INITIALIZING, READY)))
        assertEquals(ModelChat.Availability(ModelChat.State.READY, 0), ModelChat.availability(listOf(READY)))
        assertEquals(ModelChat.Availability(ModelChat.State.LOADING, 1), ModelChat.availability(listOf(UNAVAILABLE, INITIALIZING)))
        assertEquals(ModelChat.Availability(ModelChat.State.FAILED, 1), ModelChat.availability(listOf(NOT_SUPPORTED, UNAVAILABLE)))
        assertEquals(ModelChat.Availability(ModelChat.State.NOT_SET_UP), ModelChat.availability(listOf(NOT_SUPPORTED, MODEL_FILE_MISSING)))
        assertEquals(ModelChat.Availability(ModelChat.State.NOT_SET_UP), ModelChat.availability(listOf(SYSTEM_MODEL_DOWNLOADABLE, MODEL_FILE_MISSING)))
        assertEquals(ModelChat.Availability(ModelChat.State.NOT_SET_UP), ModelChat.availability(emptyList()))
    }

    @Test
    fun `check stops at the first ready model, so Gemma is never asked to load`() = runBlocking {
        val nano = FakeProvider("Gemini Nano", READY)
        val gemma = FakeProvider("Gemma", INITIALIZING)
        assertEquals(ModelChat.Availability(ModelChat.State.READY, 0), ModelChat.check(listOf(nano, gemma)))
        assertEquals(0, gemma.statusCalls)
    }

    @Test
    fun `reply skips models that are not ready and falls through an empty answer`() = runBlocking {
        val missing = FakeProvider("Missing", MODEL_FILE_MISSING, answer = "never")
        val blank = FakeProvider("Gemini Nano", READY, answer = "  ")
        val gemma = FakeProvider("Gemma", READY, answer = "You: Hi! How can I help?")
        val reply = ModelChat.reply(listOf(missing, blank, gemma), listOf(person("hello")))
        assertEquals(ChatLine(ChatLine.Kind.MODEL, "Hi! How can I help?", "Gemma"), reply)
        assertTrue(missing.prompts.isEmpty())
        assertEquals(1, blank.prompts.size)
        assertTrue(gemma.prompts.single().endsWith("Person: hello"))
    }

    @Test
    fun `reply is null when no model answers`() = runBlocking {
        assertNull(ModelChat.reply(listOf(FakeProvider("Gemma", READY, answer = null)), listOf(person("hello"))))
        assertNull(ModelChat.reply(listOf(FakeProvider("Gemma", NOT_SUPPORTED)), listOf(person("hello"))))
    }

    @Test
    fun `answer waits while a model loads, then asks it`() = runBlocking {
        val gemma = FakeProvider("Gemma", INITIALIZING, INITIALIZING, READY, answer = "Ready now.")
        val reply = ModelChat.answer(listOf(FakeProvider("Gemini Nano", NOT_SUPPORTED), gemma), listOf(person("hi")), pollMs = 0)
        assertEquals("Ready now.", reply?.text)
        assertEquals("Gemma", reply?.model)
    }

    @Test
    fun `answer gives up when loading outlasts the wait`() = runBlocking {
        val gemma = FakeProvider("Gemma", INITIALIZING, answer = "too late")
        assertNull(ModelChat.answer(listOf(gemma), listOf(person("hi")), waitMs = 5, pollMs = 0))
        assertTrue(gemma.prompts.isEmpty())
    }

    @Test
    fun `checking stops polling once nothing is loading`() = runBlocking {
        val gemma = FakeProvider("Gemma", INITIALIZING, READY)
        val seen = mutableListOf<ModelChat.State>()
        val last = ModelChat.checkUntilSettled(listOf(gemma), pollMs = 0) { seen += it.state }
        assertEquals(listOf(ModelChat.State.LOADING, ModelChat.State.READY), seen)
        assertEquals(ModelChat.State.READY, last.state)
        assertEquals(2, gemma.statusCalls)
    }

    @Test
    fun `checking a load that never settles stops after the wait`() = runBlocking {
        val gemma = FakeProvider("Gemma", INITIALIZING)
        val seen = mutableListOf<ModelChat.State>()
        val last = ModelChat.checkUntilSettled(listOf(gemma), waitMs = 3, pollMs = 1) { seen += it.state }
        // Checks at 0, 1, 2 and 3 ms of waiting, then gives up still loading.
        assertEquals(List(4) { ModelChat.State.LOADING }, seen)
        assertEquals(ModelChat.State.LOADING, last.state)
    }

    @Test
    fun `five quick taps open the chat and the count starts over`() {
        val taps = SecretTaps(needed = 5, gapMs = 1_500)
        assertEquals(listOf(4, 3, 2, 1, 0), (0 until 5).map { taps.tap(1_000L + it * 300) })
        assertEquals(4, taps.tap(3_000))
    }

    @Test
    fun `a pause starts the count over`() {
        val taps = SecretTaps(needed = 5, gapMs = 1_500)
        assertEquals(4, taps.tap(0))
        assertEquals(3, taps.tap(1_000))
        assertEquals(4, taps.tap(3_000))
        assertEquals(3, taps.tap(4_500))
    }
}
