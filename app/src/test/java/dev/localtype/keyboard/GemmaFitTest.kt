package dev.localtype.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaFitTest {
    private val gemma = 2_588_147_712L
    private fun gib(value: Double) = (value * 1024 * 1024 * 1024).toLong()

    @Test fun aFourGigabytePhoneCannotRunTheDownload() {
        assertFalse(GemmaFit.fits(gemma, gib(3.6), lowRamDevice = false))
        assertFalse(GemmaFit.fits(gemma, gib(5.6), lowRamDevice = false))
    }

    @Test fun anEightGigabytePhoneCan() {
        assertTrue(GemmaFit.fits(gemma, gib(7.3), lowRamDevice = false))
        assertTrue(GemmaFit.fits(gemma, gib(11.2), lowRamDevice = false))
    }

    @Test fun aLowRamPhoneNeverFits() = assertFalse(GemmaFit.fits(gemma, gib(15.0), lowRamDevice = true))

    @Test fun sizesAreTheOnesPhonesAreSoldAs() {
        assertEquals(4, GemmaFit.phoneGb(gib(3.6)))
        assertEquals(6, GemmaFit.phoneGb(gib(5.6)))
        assertEquals(8, GemmaFit.phoneGb(gib(7.3)))
        assertEquals(8, GemmaFit.neededGb(gemma))
    }

    @Test fun theNeededSizeFitsAndTheOneBelowDoesNot() {
        // What a phone sold at the needed size reports must pass the same rule the download applies.
        assertTrue(GemmaFit.fits(gemma, gib(GemmaFit.neededGb(gemma) * 0.9), lowRamDevice = false))
        assertFalse(GemmaFit.fits(gemma, gib(6 * 0.95), lowRamDevice = false))
    }

    @Test fun theMessageNamesBothSizesAndWhatStillWorks() {
        assertEquals(
            "Gemma 4 E2B needs a phone with 8 GB of memory, and this one has 4 GB, so it can't be downloaded here. " +
                "Typing, word predictions and corrections don't use it.",
            GemmaFit.tooSmall("Gemma 4 E2B", gemma, gib(3.6)),
        )
    }
}
