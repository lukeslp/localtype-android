package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRequestGateTest {
    private var now = 0L
    private val gate = ModelRequestGate { now }

    @Test
    fun `only one request runs at a time`() {
        val started = gate.begin()
        assertFalse(gate.canRequest())
        now += 300
        gate.succeed(started)
        assertTrue(gate.canRequest())
        assertEquals(300L, gate.averageLatencyMs)
    }

    @Test
    fun `repeated failures cool the model down`() {
        repeat(ModelRequestGate.FAILURES_BEFORE_COOL_DOWN) { gate.begin(); gate.fail() }
        assertFalse(gate.canRequest())
        now += ModelRequestGate.COOL_DOWN_MS
        assertTrue(gate.canRequest())
    }

    @Test
    fun `readiness is cached briefly and dropped after a failure`() {
        gate.recordStatus(FoundationModelStatus.READY)
        now += ModelRequestGate.STATUS_TTL_MS - 1
        assertEquals(FoundationModelStatus.READY, gate.cachedStatus())
        gate.begin(); gate.fail()
        assertNull(gate.cachedStatus())
    }

    @Test
    fun `a loading model is not cached as its status`() {
        gate.recordStatus(FoundationModelStatus.INITIALIZING)
        assertNull(gate.cachedStatus())
    }

    @Test
    fun `slow models wait for a longer pause`() {
        assertEquals(ModelRequestGate.BASE_IDLE_MS, gate.idleDelayMs())
        val started = gate.begin()
        now += 10_000
        gate.succeed(started)
        assertEquals(ModelRequestGate.MAX_IDLE_MS, gate.idleDelayMs())
    }
}
