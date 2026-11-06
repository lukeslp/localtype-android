package dev.localtype.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputTest {
    @Test
    fun onDeviceRecognizerWinsWhereverThePhoneHasOne() {
        assertEquals(VoiceRoute.ON_DEVICE, VoiceInputRules.route(31, true, "voice/ime"))
        assertEquals(VoiceRoute.ON_DEVICE, VoiceInputRules.route(36, true, null))
    }

    @Test
    fun withoutOneTheKeyHandsOffToAnEnabledVoiceInputMethod() {
        assertEquals(VoiceRoute.HANDOFF, VoiceInputRules.route(35, false, "voice/ime"))
        // Android 11 and older have no on-device recognizer API, whatever the phone reports.
        assertEquals(VoiceRoute.HANDOFF, VoiceInputRules.route(30, true, "voice/ime"))
        assertEquals(VoiceRoute.HANDOFF, VoiceInputRules.route(28, false, "voice/ime"))
    }

    @Test
    fun withNeitherThereIsNoVoiceInput() {
        assertEquals(VoiceRoute.NONE, VoiceInputRules.route(35, false, null))
        assertEquals(VoiceRoute.NONE, VoiceInputRules.route(35, false, ""))
        // Android 8 cannot switch input methods from the keyboard.
        assertEquals(VoiceRoute.NONE, VoiceInputRules.route(27, false, "voice/ime"))
    }

    @Test
    fun micKeyShowsOnlyInOrdinaryTextFields() {
        fun offered(enabled: Boolean = true, route: VoiceRoute = VoiceRoute.ON_DEVICE, sensitive: Boolean = false,
                    rawKeys: Boolean = false, letters: Boolean = true) =
            VoiceInputRules.offered(enabled, route, sensitive, rawKeys, letters)
        assertTrue(offered())
        assertTrue(offered(route = VoiceRoute.HANDOFF))
        assertFalse(offered(enabled = false))
        assertFalse(offered(route = VoiceRoute.NONE))
        assertFalse(offered(sensitive = true))
        assertFalse(offered(rawKeys = true))
        assertFalse(offered(letters = false))
    }

    @Test
    fun dictatedTextGetsASpaceAfterAWordButNotAfterWhitespaceOrAnOpeningMark() {
        assertEquals(" see you there", DictationText.insertion("OK", "see you there", formatted = false))
        assertEquals("see you there", DictationText.insertion("OK ", "see you there", formatted = false))
        assertEquals("see you there", DictationText.insertion("He said (", "see you there", formatted = false))
        assertEquals(" see you there", DictationText.insertion("Right,", "see you there", formatted = false))
        assertEquals("", DictationText.insertion("OK", "   ", formatted = false))
    }

    @Test
    fun rawTextIsCapitalizedOnlyAtASentenceStart() {
        assertEquals("See you there", DictationText.insertion("", "see you there", formatted = false))
        assertEquals("See you there", DictationText.insertion("Done. ", "see you there", formatted = false))
        assertEquals("See you there", DictationText.insertion("Done.\n", "see you there", formatted = false))
        assertEquals("See you there", DictationText.insertion("He said \"go.\" ", "see you there", formatted = false))
        assertEquals("see you there", DictationText.insertion("and then ", "see you there", formatted = false))
        // A sentence end with nothing after it is still the same sentence's punctuation until a space follows.
        assertEquals(" see you there", DictationText.insertion("Done.", "see you there", formatted = false))
    }

    @Test
    fun formattedTextLosesItsCapitalMidSentenceExceptForIAndAcronyms() {
        assertEquals("see you there.", DictationText.insertion("and then ", "See you there.", formatted = true))
        assertEquals(" see you there.", DictationText.insertion("and then", "See you there.", formatted = true))
        assertEquals("I think so.", DictationText.insertion("and then ", "I think so.", formatted = true))
        assertEquals("I'm late.", DictationText.insertion("and then ", "I'm late.", formatted = true))
        assertEquals("USB cable.", DictationText.insertion("plug in the ", "USB cable.", formatted = true))
        assertEquals("See you there.", DictationText.insertion("Done. ", "See you there.", formatted = true))
        assertEquals("See you there.", DictationText.insertion("", "See you there.", formatted = true))
    }

    @Test
    fun aResultWithWordsIsInsertedAndTheSessionKeepsListening() {
        val session = DictationSession()
        assertTrue(session.start())
        assertFalse(session.start())
        session.partial(" see you ")
        assertEquals("see you", session.partial)
        assertEquals(DictationSession.Step.Insert("see you there"), session.result(" see you there "))
        assertTrue(session.listening)
        assertEquals("", session.partial)
        assertEquals(1, session.inserted)
    }

    @Test
    fun silenceEndsTheSession() {
        for (step in listOf<(DictationSession) -> DictationSession.Step>(
            { it.result("  ") }, { it.error(DictationSession.ERROR_NO_MATCH) }, { it.error(DictationSession.ERROR_SPEECH_TIMEOUT) })) {
            val session = DictationSession().apply { start() }
            val outcome = step(session) as DictationSession.Step.Stop
            assertEquals(DictationSession.End.SILENCE, outcome.reason)
            assertFalse(session.listening)
        }
    }

    @Test
    fun errorsSayWhatWentWrong() {
        fun end(code: Int) = (DictationSession().apply { start() }.error(code) as DictationSession.Step.Stop)
        assertEquals(DictationSession.End.PERMISSION, end(DictationSession.ERROR_INSUFFICIENT_PERMISSIONS).reason)
        assertEquals(DictationSession.End.LANGUAGE, end(DictationSession.ERROR_LANGUAGE_NOT_SUPPORTED).reason)
        assertEquals(DictationSession.End.LANGUAGE, end(DictationSession.ERROR_LANGUAGE_UNAVAILABLE).reason)
        assertEquals(DictationSession.End.BUSY, end(DictationSession.ERROR_RECOGNIZER_BUSY).reason)
        assertEquals(DictationSession.End.FAILED, end(2).reason)
        assertEquals(2, end(2).code)
    }

    @Test
    fun stoppingHandsBackTheUncommittedPartialAndLaterCallbacksAreIgnored() {
        val session = DictationSession().apply { start() }
        session.partial("see you")
        assertEquals("see you", session.stop())
        assertFalse(session.listening)
        assertEquals(DictationSession.Step.Ignore, session.result("see you there"))
        assertEquals(DictationSession.Step.Ignore, session.error(DictationSession.ERROR_NO_MATCH))
        session.partial("late")
        assertEquals("", session.partial)
        assertEquals("", session.stop())
    }

    /** The roadmap's rule: only the on-device recognizer, because the default one may send audio to a server. */
    @Test
    fun onlyTheOnDeviceRecognizerIsEverCreated() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        val code = sources.associateWith { file -> file.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n") }
        val default = Regex("""\bcreateSpeechRecognizer\s*\(""")
        val offenders = code.filterValues { default.containsMatchIn(it) }.keys.map { it.name }
        assertEquals("the default speech recognizer may stream audio; use createOnDeviceSpeechRecognizer", emptyList<String>(), offenders)
        assertTrue(code.values.any { "createOnDeviceSpeechRecognizer(" in it })
        // Dictation goes in through the unlearned, unlogged insertion path and nowhere else.
        val service = code.entries.single { it.key.name == "LocalTypeInputMethodService.kt" }.value
        val commit = service.substringAfter("private fun commitDictated(").substringBefore("\n    }")
        assertTrue(commit.contains("insertFromPanel("))
        assertFalse(commit.contains("commitText("))
    }
}
