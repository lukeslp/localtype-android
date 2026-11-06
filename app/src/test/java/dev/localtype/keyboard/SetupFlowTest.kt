package dev.localtype.keyboard

import org.junit.Assert.*
import org.junit.Test

class SetupFlowTest {
    private val disabled = SetupStatus(false, false)
    private val enabled = SetupStatus(true, false)
    private val ready = SetupStatus(true, true)

    @Test fun `fresh installs start while upgrades keep settings and interrupted setup resumes`() {
        assertTrue(SetupFlow.shouldStart(false, false, false))
        assertFalse(SetupFlow.shouldStart(false, true, false))
        assertTrue(SetupFlow.shouldStart(false, true, true))
        assertFalse(SetupFlow.shouldStart(true, false, true))
    }

    @Test fun `welcome stays visible and resumes invalid state safely`() {
        assertEquals(SetupStep.WELCOME, SetupFlow.restore(null))
        assertEquals(SetupStep.WELCOME, SetupFlow.restore("REMOVED_STEP"))
        assertEquals(SetupStep.TRY, SetupFlow.restore("TRY"))
        assertEquals(SetupStep.WELCOME, SetupFlow.reconcile(SetupStep.WELCOME, ready))
    }

    @Test fun `setup waits for actual enable and selection rather than button taps`() {
        assertEquals(SetupStep.ENABLE, SetupFlow.next(SetupStep.WELCOME, disabled))
        assertEquals(SetupStep.ENABLE, SetupFlow.next(SetupStep.ENABLE, disabled))
        assertEquals(SetupStep.ENABLE, SetupFlow.reconcile(SetupStep.ENABLE, disabled))
        assertEquals(SetupStep.SELECT, SetupFlow.reconcile(SetupStep.ENABLE, enabled))
        assertEquals(SetupStep.SELECT, SetupFlow.next(SetupStep.SELECT, enabled))
        assertEquals(SetupStep.SELECT, SetupFlow.reconcile(SetupStep.SELECT, enabled))
        assertEquals(SetupStep.TRY, SetupFlow.reconcile(SetupStep.SELECT, ready))
    }

    @Test fun `already finished system steps are skipped`() {
        assertEquals(SetupStep.SELECT, SetupFlow.next(SetupStep.WELCOME, enabled))
        assertEquals(SetupStep.TRY, SetupFlow.next(SetupStep.WELCOME, ready))
        assertEquals(SetupStep.TRY, SetupFlow.reconcile(SetupStep.ENABLE, ready))
    }

    @Test fun `role loss recovers and cannot finish with another keyboard`() {
        for (step in listOf(SetupStep.SELECT, SetupStep.TRY, SetupStep.TOUR, SetupStep.CONTROLS)) {
            assertEquals(SetupStep.ENABLE, SetupFlow.reconcile(step, disabled))
            assertEquals(SetupStep.ENABLE, SetupFlow.next(step, disabled))
        }
        for (step in listOf(SetupStep.TRY, SetupStep.TOUR, SetupStep.CONTROLS)) {
            assertEquals(SetupStep.SELECT, SetupFlow.reconcile(step, enabled))
            assertEquals(SetupStep.SELECT, SetupFlow.next(step, enabled))
        }
        assertFalse(SetupStatus(false, true).ready)
        assertEquals(SetupStep.ENABLE, SetupFlow.next(SetupStep.CONTROLS, SetupStatus(false, true)))
    }

    @Test fun `try and controls complete only when verified ready`() {
        assertEquals(SetupStep.TOUR, SetupFlow.next(SetupStep.TRY, ready))
        assertEquals(SetupStep.CONTROLS, SetupFlow.next(SetupStep.TOUR, ready))
        assertEquals(SetupStep.TOUR, SetupFlow.reconcile(SetupStep.TOUR, ready))
        assertEquals(SetupStep.TOUR, SetupFlow.restore("TOUR"))
        assertNull(SetupFlow.next(SetupStep.CONTROLS, ready))
        assertEquals(SetupStep.CONTROLS, SetupFlow.reconcile(SetupStep.CONTROLS, ready))
    }

    @Test fun `back reaches the beginning without marking completion`() {
        assertNull(SetupFlow.previous(SetupStep.WELCOME))
        assertEquals(SetupStep.WELCOME, SetupFlow.previous(SetupStep.ENABLE))
        assertEquals(SetupStep.TOUR, SetupFlow.previous(SetupStep.CONTROLS))
        assertEquals(SetupStep.TRY, SetupFlow.previous(SetupStep.TOUR))
    }
}
