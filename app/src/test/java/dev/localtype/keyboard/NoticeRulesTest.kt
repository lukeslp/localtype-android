package dev.localtype.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeRulesTest {
    @Test
    fun onlyAnInsertionThatWentInSaysNothing() {
        assertNull(NoticeRules.message(VerifiedWordTransaction.Insertion.APPLIED))
        assertEquals(NoticeRules.Message.REFUSED, NoticeRules.message(VerifiedWordTransaction.Insertion.REJECTED))
        // Accepted with nothing to show for it, or accepted where the text can't be read back: either may be in the field.
        assertEquals(NoticeRules.Message.UNVERIFIED, NoticeRules.message(VerifiedWordTransaction.Insertion.UNCHANGED))
        assertEquals(NoticeRules.Message.UNVERIFIED, NoticeRules.message(VerifiedWordTransaction.Insertion.UNVERIFIED))
    }

    @Test
    fun eachPanelHasAPlaceForIt() {
        assertEquals(NoticeRules.Place.STATUS_ROW, NoticeRules.place(null))
        assertEquals(NoticeRules.Place.SAVED_TITLE, NoticeRules.place(KeyboardPanel.SAVED))
        assertEquals(NoticeRules.Place.OVER_HEADER, NoticeRules.place(KeyboardPanel.EMOJI))
        assertEquals(NoticeRules.Place.OVER_HEADER, NoticeRules.place(KeyboardPanel.PASSWORDS))
    }

    @Test
    fun twoLinesKeepThePersonsTextSizeWhileTheyFitTheStrip() {
        // 14sp on a 420 dpi phone: 36.75 px, two lines 92 px, in a 126 px strip.
        assertEquals(36.75f, NoticeRules.twoLineSize(36.75f, 92f, 126f), 0f)
        assertEquals(36.75f, NoticeRules.twoLineSize(36.75f, 126f, 126f), 0f)
        // Nothing measured yet: the size stands.
        assertEquals(36.75f, NoticeRules.twoLineSize(36.75f, 0f, 126f), 0f)
    }

    @Test
    fun largerTextStopsWhereTwoLinesStillFit() {
        // Twice the size needs 184 px. The answer's two lines, at the same height per pixel of size, fit 126.
        val fitted = NoticeRules.twoLineSize(73.5f, 184f, 126f)
        assertEquals(50f, fitted, 0f)
        assertTrue(fitted * 184f / 73.5f <= 126f)
        // Still larger than the size the strip uses at Android's default.
        assertTrue(fitted > 36.75f)
    }

    /**
     * Android 13 and later drop a toast from an app that is not in the foreground unless it may post notifications,
     * and a keyboard never is, so nothing but an activity may show one: the service and the panels draw their own.
     */
    @Test
    fun onlyAnActivityShowsAToast() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.any { it.name == "LocalTypeInputMethodService.kt" })
        val toast = Regex("""\bToast\s*\.\s*makeText\s*\(|\bToast\s*\(""")
        val offenders = sources.filterNot { it.name.endsWith("Activity.kt") }.filter { file ->
            val code = file.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            code.any(toast::containsMatchIn)
        }.map { it.name }
        assertEquals("a keyboard's toasts are dropped; use showNotice", emptyList<String>(), offenders)
    }
}
