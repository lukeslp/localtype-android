package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggedFieldGateTest {
    @Test
    fun `a field opens at once when the dictionary is ready`() {
        val gate = LoggedFieldGate()
        assertTrue(gate.start(wantsLog = true, dictionaryReady = true, restart = false))
        assertFalse(gate.pending)
        assertNull(gate.published())
    }

    @Test
    fun `a field waits for the load and opens when it publishes`() {
        val gate = LoggedFieldGate()
        assertFalse(gate.start(wantsLog = true, dictionaryReady = false, restart = true))
        assertTrue(gate.pending)
        assertEquals(true, gate.published())
        // Only once.
        assertNull(gate.published())
    }

    @Test
    fun `a field that closed before the load published never opens`() {
        val gate = LoggedFieldGate()
        gate.start(wantsLog = true, dictionaryReady = false, restart = false)
        gate.finish()
        assertNull(gate.published())
    }

    @Test
    fun `a newer field replaces the waiting one`() {
        val gate = LoggedFieldGate()
        gate.start(wantsLog = true, dictionaryReady = false, restart = true)
        assertFalse(gate.start(wantsLog = false, dictionaryReady = false, restart = false))
        assertNull(gate.published())
        gate.start(wantsLog = true, dictionaryReady = false, restart = false)
        assertEquals(false, gate.published())
    }

    @Test
    fun `dictionary publication waits for learned pairs and opens once`() {
        val gate = LoggedFieldGate()
        assertFalse(gate.start(wantsLog = true, dictionaryReady = false, restart = true, learnedReady = false))

        assertNull(gate.published(dictionaryReady = true, learnedReady = false))
        assertTrue(gate.pending)
        assertEquals(true, gate.published(dictionaryReady = true, learnedReady = true))
        assertFalse(gate.pending)
        assertNull(gate.published(dictionaryReady = true, learnedReady = true))
    }

    @Test
    fun `learned pair publication waits for dictionary and opens once`() {
        val gate = LoggedFieldGate()
        assertFalse(gate.start(wantsLog = true, dictionaryReady = false, restart = false, learnedReady = false))

        assertNull(gate.published(dictionaryReady = false, learnedReady = true))
        assertTrue(gate.pending)
        assertEquals(false, gate.published(dictionaryReady = true, learnedReady = true))
        assertFalse(gate.pending)
        assertNull(gate.published(dictionaryReady = true, learnedReady = true))
    }

    @Test
    fun `closing after either first publication prevents the second from opening a field`() {
        listOf(true, false).forEach { dictionaryFirst ->
            val gate = LoggedFieldGate()
            gate.start(wantsLog = true, dictionaryReady = false, restart = true, learnedReady = false)
            assertNull(gate.published(dictionaryReady = dictionaryFirst, learnedReady = !dictionaryFirst))
            gate.finish()

            assertFalse(gate.pending)
            assertNull(gate.published(dictionaryReady = true, learnedReady = true))
        }
    }
}
