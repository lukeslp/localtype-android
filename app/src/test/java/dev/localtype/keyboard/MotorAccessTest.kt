package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class MotorAccessTest {
    @Test
    fun `everything is off by default`() {
        val off = MotorSettings.OFF
        assertFalse(off.any)
        assertFalse(off.timed)
        assertFalse(off.oneFinger)
        assertFalse(off.gliding)
        assertTrue(MotorRules.typesOnLift(off, ready = false))
    }

    @Test
    fun `a saved value that is not offered is off`() {
        assertEquals(300, MotorSettings.choice(300, MotorSettings.REPEAT_CHOICES))
        assertEquals(0, MotorSettings.choice(250, MotorSettings.REPEAT_CHOICES))
        assertEquals(0, MotorSettings.choice(-1, MotorSettings.DWELL_CHOICES))
        listOf(MotorSettings.REPEAT_CHOICES, MotorSettings.HOLD_CHOICES, MotorSettings.DWELL_CHOICES).forEach {
            assertEquals(0, it.first())
            assertEquals(it.sorted(), it)
        }
    }

    @Test
    fun `a touch inside the window after a lift is a repeat`() {
        assertTrue(MotorRules.ignoresRepeat(300, lastLift = 1000, downTime = 1299))
        assertFalse(MotorRules.ignoresRepeat(300, lastLift = 1000, downTime = 1300))
        assertFalse(MotorRules.ignoresRepeat(0, lastLift = 1000, downTime = 1001))
        assertFalse(MotorRules.ignoresRepeat(300, lastLift = null, downTime = 1001))
        // A clock that runs backwards never swallows touches.
        assertFalse(MotorRules.ignoresRepeat(300, lastLift = 1000, downTime = 900))
    }

    @Test
    fun `a hold time types on lift only once ready, and dwelling never types on lift`() {
        val hold = MotorSettings(holdMs = 200)
        assertFalse(MotorRules.typesOnLift(hold, ready = false))
        assertTrue(MotorRules.typesOnLift(hold, ready = true))
        val dwell = MotorSettings(holdMs = 200, dwellMs = 600)
        assertFalse(MotorRules.typesOnLift(dwell, ready = true))
        assertEquals(0, dwell.effectiveHoldMs)
        assertTrue(dwell.gliding && dwell.oneFinger && dwell.timed)
    }

    @Test
    fun `holds come after the hold time, and only shift holds while dwelling`() {
        assertEquals(400L, MotorRules.holdDelay(MotorSettings.OFF, 400, shift = false))
        assertEquals(600L, MotorRules.holdDelay(MotorSettings(holdMs = 200), 400, shift = false))
        val dwell = MotorSettings(dwellMs = 600)
        assertNull(MotorRules.holdDelay(dwell, 400, shift = false))
        assertEquals(1200L, MotorRules.holdDelay(dwell, 400, shift = true))
        assertFalse(MotorRules.shiftHoldLocks(MotorSettings.OFF))
        assertFalse(MotorRules.shiftHoldLocks(MotorSettings(steadyKeys = true)))
        assertTrue(MotorRules.shiftHoldLocks(MotorSettings(ignoreRepeatMs = 150)))
        assertTrue(MotorRules.shiftHoldLocks(MotorSettings(holdMs = 100)))
        assertTrue(MotorRules.shiftHoldLocks(dwell))
    }

    @Test
    fun `progress fills from zero to one`() {
        assertEquals(0f, MotorRules.progress(1000, 1000, 400), 0f)
        assertEquals(.5f, MotorRules.progress(1000, 1200, 400), 1e-6f)
        assertEquals(1f, MotorRules.progress(1000, 9000, 400), 0f)
        assertEquals(0f, MotorRules.progress(1000, 900, 400), 0f)
        assertEquals(1f, MotorRules.progress(1000, 1000, 0), 0f)
    }

    @Test
    fun `a steady finger keeps its key within the margin`() {
        val cell = Box(100f, 0f, 200f, 100f)
        assertTrue(MotorRules.staysOn(cell, 150f, 50f, 100f))
        assertTrue(MotorRules.staysOn(cell, 234f, 50f, 100f))
        assertFalse(MotorRules.staysOn(cell, 236f, 50f, 100f))
        assertTrue(MotorRules.staysOn(cell, 150f, -34f, 100f))
        assertFalse(MotorRules.staysOn(cell, 150f, 136f, 100f))
    }

    @Test
    fun `a learned shift needs enough samples and is capped`() {
        assertNull(MotorRules.shift(MotorRules.MIN_AIM_SAMPLES - 1, .2f, .1f))
        assertEquals(AimShift(.2f, .1f), MotorRules.shift(MotorRules.MIN_AIM_SAMPLES, .2f, .1f))
        assertEquals(AimShift(MotorRules.MAX_AIM_SHIFT, -MotorRules.MAX_AIM_SHIFT), MotorRules.shift(500, 3f, -3f))
        assertNull(MotorRules.shift(500, Float.NaN, 0f))
    }

    /** Three keys of 100 px in a row, as g h j. */
    private fun row(vararg shifts: AimShift?) = shifts.mapIndexed { i, s -> AimTarget(Box(i * 100f, 0f, i * 100f + 100f, 100f), 100f, s) }

    @Test
    fun `with no shifts the drawn key wins`() {
        val targets = row(null, null, null)
        assertEquals(1, MotorRules.aim(105f, 50f, targets, raw = 1))
        assertEquals(1, MotorRules.aim(195f, 50f, targets, raw = 1))
    }

    @Test
    fun `a letter whose lifts land right claims touches over its right neighbour's edge`() {
        val targets = row(AimShift(.3f, 0f), null, null)
        // Lifts on g land 0.3 of a key right of its center: its cell now overlaps h's by 30 px, and in the overlap the
        // nearer center wins, so g (center 80) takes touches up to halfway to h's center (150).
        assertEquals(0, MotorRules.aim(110f, 50f, targets, raw = 1))
        assertEquals(1, MotorRules.aim(120f, 50f, targets, raw = 1))
        // Its own left edge left a gap that still goes to g, the nearest moved cell.
        assertEquals(0, MotorRules.aim(10f, 50f, targets, raw = 0))
        // Far from the moved cell, the other keys keep their own.
        assertEquals(2, MotorRules.aim(250f, 50f, targets, raw = 2))
    }

    @Test
    fun `vertical shifts move a letter's target down`() {
        val top = AimTarget(Box(0f, 0f, 100f, 100f), 100f, AimShift(0f, .3f))
        val bottom = AimTarget(Box(0f, 100f, 100f, 200f), 100f, null)
        // 10 px into the lower row is still the upper key's when its lifts land low; past halfway to the lower
        // key's center it is the lower key's again.
        assertEquals(0, MotorRules.aim(50f, 110f, listOf(top, bottom), raw = 1))
        assertEquals(1, MotorRules.aim(50f, 120f, listOf(top, bottom), raw = 1))
    }

    @Test
    fun `an unknown raw key is returned as it is`() {
        assertEquals(-1, MotorRules.aim(50f, 50f, row(AimShift(.3f, 0f)), raw = -1))
    }

    @Test
    fun `rows near each other trade letters, rows apart do not`() {
        val a = Box(0f, 0f, 100f, 100f)
        assertTrue(MotorRules.nearRows(a, Box(0f, 100f, 100f, 200f)))
        assertFalse(MotorRules.nearRows(a, Box(0f, 200f, 100f, 300f)))
    }

    @Test
    fun `the editor fixture compiles the keyboard view with its rules`() {
        val fixture = File("../verification/editor-fixture/app/build.gradle.kts")
        // The reviewed public snapshot deliberately excludes the private device fixture.
        // A development checkout must still fail if its fixture disappears.
        if (File("../SOURCE-MANIFEST.json").isFile) {
            assumeTrue("The public snapshot excludes the editor fixture", fixture.isFile)
        }
        val build = fixture.readText()
        assertTrue(build.contains("\"MotorAccess.kt\""))
    }
}
