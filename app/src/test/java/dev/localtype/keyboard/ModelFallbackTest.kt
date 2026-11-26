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

class ModelFallbackTest {
    /**
     * The keyboard's composite in miniature, deciding through [ModelFallback] as the real one does: Nano first while it
     * may be asked, Gemma's readiness where it may not, and Gemma after any Nano reply that isn't an answer.
     */
    private class Phone(val fallback: ModelFallback = ModelFallback()) {
        var nanoStatus = READY
        /** What Nano does with a request from another app; LocalType's own screens always get an answer. */
        var nanoRefusesOtherApps = true
        /** Gemma's statuses, one per status call; the last repeats. Asking a Gemma that isn't loaded starts the load. */
        var gemmaStatuses = mutableListOf(INITIALIZING, INITIALIZING, INITIALIZING, READY)
        var gemmaAnswer: String? = "Gemma's fix"
        var nanoAsked = 0
        var gemmaAsked = 0

        private fun gemmaStatus(): FoundationModelStatus =
            (if (gemmaStatuses.size > 1) gemmaStatuses.removeAt(0) else gemmaStatuses[0]).also { fallback.gemmaStatus = it }

        fun status(): FoundationModelStatus {
            val nano = if (fallback.asksNano()) nanoStatus else null
            if (nano == READY) {
                fallback.gemmaStatus = null
                return READY
            }
            return ModelFallback.combine(nano, gemmaStatus())
        }

        fun proofread(): String? {
            if (fallback.asksNano() && nanoStatus == READY) {
                nanoAsked++
                val reply = if (nanoRefusesOtherApps && !fallback.servingOwnApp) ModelFallback.Reply.Refused
                else ModelFallback.Reply.Answered("Nano's fix")
                fallback.record(reply)
                if (!ModelFallback.asksGemmaAfter(reply)) return (reply as ModelFallback.Reply.Answered).value
            }
            if (gemmaStatus() != READY) return null
            gemmaAsked++
            return gemmaAnswer
        }
    }

    private class Run(val phone: Phone) {
        var now = 0L
        val loading = mutableListOf<Boolean>()
        var loaded = 0

        fun proofread(waitMs: Long = 60_000L): ModelFallback.Outcome<String> = runBlocking {
            phone.fallback.request(
                status = { phone.status() },
                ask = { phone.proofread() },
                loadWaitMs = waitMs,
                clock = { now },
                pause = { now += it },
                onLoading = { loading += it },
                onLoaded = { loaded++ },
            )
        }
    }

    @Test fun `Nano refused in another app goes to Gemma and waits for it to load instead of failing`() {
        val phone = Phone()
        val run = Run(phone)
        val outcome = run.proofread()
        assertEquals("Gemma's fix", outcome.answer)
        assertNull(outcome.failure)
        assertEquals(1, phone.nanoAsked)
        assertEquals(1, phone.gemmaAsked)
        // The wait said Gemma was loading, once, and said when it had loaded.
        assertEquals(listOf(true), run.loading)
        assertEquals(1, run.loaded)
        assertEquals(1, phone.fallback.refusals)
    }

    @Test fun `after a refusal the keyboard's readiness is Gemma's, so later requests skip Nano and wait on Gemma`() {
        val phone = Phone()
        Run(phone).proofread()
        // Gemma unloads when idle: the next request finds it loading again.
        phone.gemmaStatuses = mutableListOf(INITIALIZING, INITIALIZING, READY)
        assertFalse(phone.fallback.asksNano())
        assertEquals(INITIALIZING, phone.status())
        val run = Run(phone)
        val outcome = run.proofread()
        assertEquals("Gemma's fix", outcome.answer)
        assertEquals("Nano isn't asked again in this process", 1, phone.nanoAsked)
        assertEquals(listOf(true), run.loading)
    }

    @Test fun `Nano stays first in LocalType's own screens, even after a refusal elsewhere`() {
        val phone = Phone()
        Run(phone).proofread()
        phone.fallback.servingOwnApp = true
        assertTrue(phone.fallback.asksNano())
        assertEquals(READY, phone.status())
        val outcome = Run(phone).proofread()
        assertEquals("Nano's fix", outcome.answer)
        assertEquals(1, phone.gemmaAsked)
    }

    @Test fun `Gemma still loading when the wait runs out says so`() {
        val phone = Phone().apply { gemmaStatuses = mutableListOf(INITIALIZING) }
        val run = Run(phone)
        val outcome = run.proofread(waitMs = 5_000L)
        assertNull(outcome.answer)
        assertEquals(ModelFallback.Failure.GEMMA_STILL_LOADING, outcome.failure)
        assertEquals(0, run.loaded)
        assertTrue("the wait stopped near its limit", run.now in 5_000L..6_000L)
    }

    @Test fun `Gemma that can't load after a refusal says Gemma failed, not set up`() {
        val phone = Phone().apply { gemmaStatuses = mutableListOf(UNAVAILABLE) }
        val outcome = Run(phone).proofread()
        assertNull(outcome.answer)
        assertEquals(ModelFallback.Failure.GEMMA_FAILED, outcome.failure)
    }

    @Test fun `Nano refused with no Gemma on the phone asks for Gemma`() {
        val phone = Phone().apply { gemmaStatuses = mutableListOf(MODEL_FILE_MISSING) }
        val outcome = Run(phone).proofread()
        assertEquals(ModelFallback.Failure.NANO_REFUSED_NO_GEMMA, outcome.failure)
        assertEquals(MODEL_FILE_MISSING, phone.status())
    }

    @Test fun `Gemma ready with no answer after a refusal says Gemma, and isn't asked a third time`() {
        val phone = Phone().apply { gemmaAnswer = null }
        val outcome = Run(phone).proofread()
        assertEquals(ModelFallback.Failure.GEMMA_NO_ANSWER, outcome.failure)
        assertEquals(1, phone.nanoAsked)
        assertEquals(1, phone.gemmaAsked)
    }

    @Test fun `an empty answer without a refusal isn't retried`() {
        val fallback = ModelFallback()
        var asked = 0
        val outcome = runBlocking {
            fallback.request(status = { READY }, ask = { asked++; null as String? }, loadWaitMs = 60_000L, clock = { 0L }, pause = {})
        }
        assertEquals(1, asked)
        assertEquals(ModelFallback.Failure.NO_ANSWER, outcome.failure)
        assertTrue(fallback.asksNano())
    }

    @Test fun `a phone with only Gemma says Gemma is loading while it loads`() {
        val phone = Phone().apply { nanoStatus = NOT_SUPPORTED }
        val run = Run(phone)
        assertEquals("Gemma's fix", run.proofread().answer)
        assertEquals(listOf(true), run.loading)
        assertEquals(0, phone.fallback.refusals)
    }

    @Test fun `the first refusal is noted once, and an answer in another app clears the note`() {
        val fallback = ModelFallback()
        assertEquals(ModelFallback.Note.FIRST_REFUSAL, fallback.record(ModelFallback.Reply.Refused))
        assertEquals(ModelFallback.Note.NONE, fallback.record(ModelFallback.Reply.Refused))
        assertEquals(ModelFallback.Note.NONE, fallback.record(ModelFallback.Reply.Failed))
        assertEquals(2, fallback.refusals)
        assertTrue(fallback.refusedSeen)
        fallback.servingOwnApp = true
        assertEquals(ModelFallback.Note.NONE, fallback.record(ModelFallback.Reply.Answered("x")))
        fallback.servingOwnApp = false
        assertEquals(ModelFallback.Note.ANSWERED_IN_OTHER_APP, fallback.record(ModelFallback.Reply.Answered("x")))
    }

    @Test fun `Gemma is asked after any Nano reply that isn't an answer`() {
        assertTrue(ModelFallback.asksGemmaAfter(ModelFallback.Reply.Refused))
        assertTrue(ModelFallback.asksGemmaAfter(ModelFallback.Reply.Failed))
        assertFalse(ModelFallback.asksGemmaAfter(ModelFallback.Reply.Answered("x")))
    }

    @Test fun `only a refusal is retried, and only once`() {
        assertTrue(ModelFallback.retries(refusedDuring = true, tries = 1))
        assertFalse(ModelFallback.retries(refusedDuring = true, tries = 2))
        assertFalse(ModelFallback.retries(refusedDuring = false, tries = 1))
    }

    @Test fun `readiness without a ready Nano keeps the old order`() {
        assertEquals(INITIALIZING, ModelFallback.combine(null, INITIALIZING))
        assertEquals(READY, ModelFallback.combine(null, READY))
        assertEquals(MODEL_FILE_MISSING, ModelFallback.combine(null, MODEL_FILE_MISSING))
        assertEquals(INITIALIZING, ModelFallback.combine(INITIALIZING, MODEL_FILE_MISSING))
        assertEquals(SYSTEM_MODEL_DOWNLOADABLE, ModelFallback.combine(SYSTEM_MODEL_DOWNLOADABLE, MODEL_FILE_MISSING))
        assertEquals(MODEL_FILE_MISSING, ModelFallback.combine(NOT_SUPPORTED, MODEL_FILE_MISSING))
        assertEquals(UNAVAILABLE, ModelFallback.combine(NOT_SUPPORTED, UNAVAILABLE))
    }

    @Test fun `failures say why where it is known`() {
        assertEquals(ModelFallback.Failure.STILL_LOADING, ModelFallback.failure(INITIALIZING, null, nanoRefusedHere = false))
        assertEquals(ModelFallback.Failure.GEMMA_STILL_LOADING, ModelFallback.failure(INITIALIZING, INITIALIZING, nanoRefusedHere = false))
        assertEquals(ModelFallback.Failure.NO_ANSWER, ModelFallback.failure(READY, null, nanoRefusedHere = false))
        assertEquals(ModelFallback.Failure.GEMMA_NO_ANSWER, ModelFallback.failure(READY, READY, nanoRefusedHere = true))
        assertEquals(ModelFallback.Failure.GEMMA_FAILED, ModelFallback.failure(UNAVAILABLE, UNAVAILABLE, nanoRefusedHere = false))
        assertEquals(ModelFallback.Failure.NANO_REFUSED_NO_GEMMA, ModelFallback.failure(MODEL_FILE_MISSING, MODEL_FILE_MISSING, nanoRefusedHere = true))
        assertEquals(ModelFallback.Failure.SET_UP, ModelFallback.failure(MODEL_FILE_MISSING, MODEL_FILE_MISSING, nanoRefusedHere = false))
        // The F-Droid build has no model at all: the same setup message as before.
        assertEquals(ModelFallback.Failure.SET_UP, ModelFallback.failure(NOT_SUPPORTED, null, nanoRefusedHere = false))
    }
}
