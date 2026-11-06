package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeuralRequestStateTest {
    @Test
    fun `the same pending context queues only once and the answer stays cached`() {
        val state = NeuralRequestState()
        val request = state.request("hello ")!!
        assertNull(state.request("hello "))
        assertNull(state.answer("hello "))
        assertTrue(state.complete(request, "hello ", listOf("there")))
        assertEquals(listOf("there"), state.answer("hello "))
        assertNull(state.answer("hello t"))
        assertNull(state.request("hello "))
    }

    @Test
    fun `a changed editor context rejects an answer without a generation change`() {
        val state = NeuralRequestState()
        val request = state.request("hello ")!!
        assertFalse(state.accepts(request, "hello t"))
        assertFalse(state.complete(request, "hello t", listOf("there")))
        assertNull(state.answer("hello "))
    }

    @Test
    fun `an append invalidates the pending answer but keeps the worker session`() {
        val state = NeuralRequestState()
        val first = state.request("hello ")!!
        state.invalidate()
        val second = state.request("hello t")!!
        assertEquals(first.sessionGeneration, second.sessionGeneration)
        assertTrue(second.generation > first.generation)
        assertFalse(state.complete(first, "hello ", listOf("there")))
        assertTrue(state.complete(second, "hello t", listOf("today")))
        assertEquals(listOf("today"), state.answer("hello t"))
    }

    @Test
    fun `new field forget and nonappend reset reject even identical old context`() {
        val state = NeuralRequestState()
        val first = state.request("hello ")!!
        state.reset()
        val second = state.request("hello ")!!
        assertEquals(first.sessionGeneration + 1, second.sessionGeneration)
        assertFalse(state.complete(first, "hello ", listOf("old")))
        assertTrue(state.complete(second, "hello ", listOf("new")))
        state.reset()
        assertNull(state.answer("hello "))
        assertTrue(state.request("hello ")!!.sessionGeneration > second.sessionGeneration)
    }

    @Test
    fun `a newer request supersedes an old request even without invalidate`() {
        val state = NeuralRequestState()
        val first = state.request("hello ")!!
        val second = state.request("hello t")!!
        assertFalse(state.complete(first, "hello ", listOf("old")))
        assertTrue(state.complete(second, "hello t", listOf("new")))
        assertFalse(state.complete(first, "hello ", listOf("old")))
        assertEquals(listOf("new"), state.answer("hello t"))
    }

    @Test
    fun `an empty result is cached and a completion copies its list`() {
        val state = NeuralRequestState()
        val first = state.request("hello ")!!
        assertTrue(state.complete(first, "hello ", emptyList()))
        assertEquals(emptyList<String>(), state.answer("hello "))
        assertNull(state.request("hello "))
        state.invalidate()
        val second = state.request("hello ")!!
        val words = mutableListOf("there")
        assertTrue(state.complete(second, "hello ", words))
        words.clear()
        assertEquals(listOf("there"), state.answer("hello "))
    }

    @Test
    fun `a callback rejected during a proposal allows the unchanged context to retry`() {
        val state = NeuralRequestState()
        val first = state.request("hello ")!!
        state.abandon(first)
        val retry = state.request("hello ")!!
        assertEquals(first.sessionGeneration, retry.sessionGeneration)
        assertFalse(state.complete(first, "hello ", listOf("old")))
        assertTrue(state.complete(retry, "hello ", listOf("there")))
    }

    @Test
    fun `abandoning an old callback preserves a newer pending request and its answer`() {
        val state = NeuralRequestState()
        val first = state.request("hello ")!!
        val second = state.request("hello t")!!
        state.abandon(first)
        assertNull(state.request("hello t"))
        assertTrue(state.complete(second, "hello t", listOf("today")))
        state.abandon(first)
        assertEquals(listOf("today"), state.answer("hello t"))
    }
}
