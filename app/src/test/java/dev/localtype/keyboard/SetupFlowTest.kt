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

    @Test fun `the last step asks about Nano and Gemma only in a build that has one of them`() {
        assertFalse(SetupFlow.offersModelOnTap(includesNano = false, includesGemma = false))
        assertTrue(SetupFlow.offersModelOnTap(includesNano = true, includesGemma = false))
        assertTrue(SetupFlow.offersModelOnTap(includesNano = false, includesGemma = true))
        // The switch and its sentence are drawn only behind that test, so the catalog build's last step has neither.
        val setup = java.io.File("src/main/java/dev/localtype/keyboard/SetupActivity.kt").readText()
        val gate = setup.indexOf("if (SetupFlow.offersModelOnTap()) {")
        assertTrue("SetupActivity no longer asks SetupFlow.offersModelOnTap()", gate >= 0)
        val block = setup.substring(gate, setup.indexOf("\n            }\n", gate))
        for (name in listOf("R.string.model_on_tap_title", "R.string.model_on_tap_body", "KeyboardSettings.setModelOnTap")) {
            assertEquals(name, 1, Regex(Regex.escape(name)).findAll(setup).count())
            assertTrue("$name is outside the gate", name in block)
        }
    }
}
