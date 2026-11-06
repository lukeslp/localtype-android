package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelBudgetTest {
    private var now = 0L
    private val budget = ModelBudget { now }
    private val normal = ModelBudget.PowerState(powerSave = false, batteryPercent = 80, charging = false, thermalSevere = false)

    @Test
    fun `power and heat pause automatic work`() {
        assertEquals("thermal", budget.blockReason(normal.copy(thermalSevere = true), lookahead = false))
        assertEquals("power saver", budget.blockReason(normal.copy(powerSave = true), lookahead = false))
        assertEquals("low battery", budget.blockReason(normal.copy(batteryPercent = 15), lookahead = false))
        assertNull(budget.blockReason(normal.copy(batteryPercent = 15, charging = true), lookahead = false))
    }

    @Test
    fun `a busy minute stops lookahead first, then everything, then recovers`() {
        repeat(ModelBudget.MAX_LOOKAHEAD_PER_MINUTE) { budget.record() }
        assertEquals("lookahead cap", budget.blockReason(normal, lookahead = true))
        assertNull(budget.blockReason(normal, lookahead = false))
        repeat(ModelBudget.MAX_PER_MINUTE - ModelBudget.MAX_LOOKAHEAD_PER_MINUTE) { budget.record() }
        assertEquals("per-minute cap", budget.blockReason(normal, lookahead = false))
        now += ModelBudget.WINDOW_MS + 1
        assertNull(budget.blockReason(normal, lookahead = true))
    }
}
