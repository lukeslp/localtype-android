package dev.localtype.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SparkleRulesTest {
    @Test
    fun `a model is set up when Nano is ready or arriving, Gemma is on the phone, or LocalLauncher lends it`() {
        assertTrue(SparkleRules.modelSetUp(FoundationModelStatus.READY, gemmaInstalled = false, borrowed = false))
        assertTrue(SparkleRules.modelSetUp(FoundationModelStatus.INITIALIZING, gemmaInstalled = false, borrowed = false))
        assertTrue(SparkleRules.modelSetUp(FoundationModelStatus.NOT_SUPPORTED, gemmaInstalled = true, borrowed = false))
        assertTrue(SparkleRules.modelSetUp(null, gemmaInstalled = false, borrowed = true))
    }

    @Test
    fun `an offered, missing or unsupported model is not set up`() {
        for (nano in listOf(null, FoundationModelStatus.SYSTEM_MODEL_DOWNLOADABLE, FoundationModelStatus.MODEL_FILE_MISSING,
            FoundationModelStatus.UNAVAILABLE, FoundationModelStatus.NOT_SUPPORTED)) {
            assertFalse("$nano", SparkleRules.modelSetUp(nano, gemmaInstalled = false, borrowed = false))
        }
    }

    @Test
    fun `the sparkle needs both the switch and a model`() {
        assertTrue(SparkleRules.shows(wanted = true, modelSetUp = true))
        assertFalse(SparkleRules.shows(wanted = false, modelSetUp = true))
        assertFalse(SparkleRules.shows(wanted = true, modelSetUp = false))
        assertFalse(SparkleRules.shows(wanted = false, modelSetUp = false))
    }

    @Test
    fun `the models are asked again after a minute, and at once when never asked`() {
        assertTrue(SparkleRules.due(null, now = 5))
        assertFalse(SparkleRules.due(1_000, now = 1_000 + SparkleRules.RECHECK_MS - 1))
        assertTrue(SparkleRules.due(1_000, now = 1_000 + SparkleRules.RECHECK_MS))
    }
}
