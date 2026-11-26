package dev.localtype.keyboard

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Hide LocalType from screen capture: the flag rule, and that every window LocalType opens is marked. */
class ScreenCaptureTest {
    private val secure = WindowManager.LayoutParams.FLAG_SECURE
    /** Every source set there is: the public source carries fewer of them. */
    private val sources = (File("src").listFiles().orEmpty().map { File(it, "java") })
        .filter { it.isDirectory && !it.parentFile.name.startsWith("test") && it.parentFile.name != "androidTest" }
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun `only the secure bit changes`() {
        val others = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        assertEquals(others or secure, ScreenCapture.flags(others, hide = true))
        assertEquals(others, ScreenCapture.flags(others or secure, hide = false))
        assertEquals(others, ScreenCapture.flags(others, hide = false))
        assertEquals(others or secure, ScreenCapture.flags(others or secure, hide = true))
        assertEquals(0, ScreenCapture.flags(0, hide = false))
    }

    /** A window's flags as `Window.setFlags` changes them, counting the calls. */
    private class WindowFlags(var value: Int) {
        var sets = 0
        fun set(flags: Int, mask: Int) {
            sets++
            value = (value and mask.inv()) or (flags and mask)
        }
    }

    @Test
    fun `a window already in the wanted state is not set again`() {
        val others = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val window = WindowFlags(others)
        // The switch off, as it starts: asked at every field and every return, nothing is set.
        repeat(3) { assertFalse(ScreenCapture.mark(window.value, hide = false, window::set)) }
        assertEquals(0, window.sets)
        // Turned on: one call, and the fields and returns after it add none.
        assertTrue(ScreenCapture.mark(window.value, hide = true, window::set))
        assertEquals(others or secure, window.value)
        repeat(3) { assertFalse(ScreenCapture.mark(window.value, hide = true, window::set)) }
        assertEquals(1, window.sets)
        // Turned off again: one more call, and only the secure bit went.
        assertTrue(ScreenCapture.mark(window.value, hide = false, window::set))
        assertEquals(others, window.value)
        repeat(3) { assertFalse(ScreenCapture.mark(window.value, hide = false, window::set)) }
        assertEquals(2, window.sets)
        assertTrue(ScreenCapture.changes(others, hide = true))
        assertFalse(ScreenCapture.changes(others or secure, hide = true))
        assertTrue(ScreenCapture.changes(others or secure, hide = false))
        assertFalse(ScreenCapture.changes(others, hide = false))
    }

    @Test
    fun `the setting is off until the person turns it on and travels in a copy`() {
        val settings = File("src/main/java/dev/localtype/keyboard/KeyboardSettings.kt").readText()
        assertTrue("fun hideFromCapture(context: Context) = prefs(context).getBoolean(HIDE_FROM_CAPTURE, false)" in settings)
        assertTrue(KeyboardSettings.HIDE_FROM_CAPTURE in KeyboardSettings.portableKeys)
    }

    @Test
    fun `every screen is a covered one, marked when it is made and when it comes back`() {
        val screens = sources.filter { Regex("""class \w+ : (Covered)?Activity\(\)""").containsMatchIn(it.readText()) }
        // Only three build on Android's own class: the two see-through permission screens, which draw nothing of
        // their own, and the covered screen itself. A private build's screen is held to the same rule.
        val plain = screens.filter { Regex("""class \w+ : Activity\(\)""").containsMatchIn(it.readText()) }.map { it.name }.sorted()
        assertEquals(listOf("ContactsPermissionActivity.kt", "CoveredActivity.kt", "VoicePermissionActivity.kt"), plain)
        val covered = screens.map { it.name } - plain.toSet()
        assertTrue("found $covered", covered.containsAll(listOf("LicensesActivity.kt", "ModelChatActivity.kt", "SettingsActivity.kt", "SetupActivity.kt")))
        val screen = File("src/main/java/dev/localtype/keyboard/CoveredActivity.kt").readText()
        val cover = Regex.escape("ScreenCapture.cover(window, KeyboardSettings.hideFromCapture(this))")
        // The first thing after super, before a screen sets or shows any content of its own.
        assertTrue(Regex("""override fun onCreate\(savedInstanceState: Bundle\?\) \{\s*super\.onCreate\(savedInstanceState\)\s*$cover""").containsMatchIn(screen))
        assertTrue(Regex("""override fun onResume\(\) \{\s*super\.onResume\(\)\s*$cover""").containsMatchIn(screen))
        // A dialog left open while the switch changed is marked again when its screen comes back.
        assertTrue("dialogs.keys.filter { it.isShowing }.forEach(::cover)" in screen.substringAfter("override fun onResume()").substringBefore("override fun onPause()"))
    }

    @Test
    fun `the keyboard is put away when a marked screen is left and kept when it is rebuilt`() {
        assertTrue(ScreenCapture.dropsKeyboard(hide = true, rebuilding = false))
        // Turning the phone rebuilds the screen: the keyboard stays where the person was typing.
        assertFalse(ScreenCapture.dropsKeyboard(hide = true, rebuilding = true))
        // With the switch off the keyboard is never touched.
        assertFalse(ScreenCapture.dropsKeyboard(hide = false, rebuilding = false))
        assertFalse(ScreenCapture.dropsKeyboard(hide = false, rebuilding = true))
        // The covered screen asks that rule with the setting, before the screen is paused.
        val screen = File("src/main/java/dev/localtype/keyboard/CoveredActivity.kt").readText()
        val paused = screen.substringAfter("override fun onPause() {").substringBefore("super.onPause()")
        assertTrue("if (ScreenCapture.dropsKeyboard(KeyboardSettings.hideFromCapture(this), isChangingConfigurations)) dropKeyboard()" in paused)
    }

    @Test
    fun `no dialog on the settings screen is shown unmarked`() {
        val lines = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readLines()
        val builders = lines.count { "AlertDialog.Builder(" in it }
        assertTrue("found $builders dialogs", builders >= 15)
        // A dialog is a window of its own. A builder chain ends in showCovered(); the helper's declaration is not a call.
        val covered = lines.count { ".showCovered()" in it && "fun android.app.AlertDialog.Builder.showCovered()" !in it }
        // A dialog shown from a variable is marked on the line before by the covered screen, which reads the setting
        // and remembers the dialog: once in the helper, and once for each dialog built in steps.
        val shown = lines.indices.filter { Regex("""^\s*\w+\.show\(\)\s*$""").matches(lines[it]) }
        shown.forEach { at ->
            assertTrue("line ${at + 1} shows a dialog marked on the line before",
                Regex("""^\s*cover\(\w+\)\s*$""").matches(lines[at - 1]))
        }
        assertEquals("every dialog is shown covered or marked by hand", builders, covered + shown.size - 1)
        // Nothing ends a chain or a create() in a bare show().
        val bare = lines.count { Regex("""^\s*\.show\(\)|\}\.show\(\)|\.create\(\)\.show\(\)""").containsMatchIn(it) }
        assertEquals("bare show() on a dialog", 0, bare)
    }

    @Test
    fun `every popup window is marked where it is shown`() {
        val popups = sources.filter { "PopupWindow(" in it.readText() && it.name != "ScreenCapture.kt" }
        assertEquals(listOf("KeyboardView.kt", "SuggestionPopup.kt"), popups.map { it.name }.sorted())
        // Marked with the setting itself, never a constant.
        val mark = Regex("""ScreenCapture\.cover\(\w+, (hideFromCapture|KeyboardSettings\.hideFromCapture\(context\))\)""")
        popups.forEach { file ->
            val lines = file.readLines()
            val shows = lines.indices.filter { ".showAtLocation(" in lines[it] }
            assertTrue("${file.name} shows a popup", shows.isNotEmpty())
            shows.forEach { at ->
                assertTrue("${file.name}:${at + 1} marks the popup right after showing it",
                    lines.subList(at + 1, minOf(lines.size, at + 4)).any(mark::containsMatchIn))
            }
        }
        // showAsDropDown would be a second way in; nothing uses it.
        assertFalse(sources.any { ".showAsDropDown(" in it.readText() })
    }

    @Test
    fun `the keyboard marks its window before it is first shown and when the setting changes`() {
        val service = File("src/main/java/dev/localtype/keyboard/LocalTypeInputMethodService.kt").readText()
        assertTrue("ScreenCapture.cover(window?.window, hide)" in service)
        assertTrue("if (key == KeyboardSettings.HIDE_FROM_CAPTURE) handler.post { if (!destroyed) applyScreenCapture() }" in service)
        val created = service.substringAfter("override fun onCreate() {").substringBefore("themeMode = ThemeMode.of(this)")
        assertTrue("applyScreenCapture()" in created)
        // The one platform popup the keyboard opens lists action names only; a second one would need a look.
        assertEquals(1, Regex("""PopupMenu\(""").findAll(service).count())
        // Android previews a copy in its own window, so the one clip the keyboard writes is flagged while hidden.
        assertEquals(1, Regex("""setPrimaryClip\(""").findAll(service).count())
        assertTrue("if (hiddenFromCapture) clip.description.extras" in service)
    }

    @Test
    fun `the editor fixture copies the helper with the keys`() {
        val fixture = File("../verification/editor-fixture/app/build.gradle.kts")
        // The public snapshot leaves the device fixture out; a development checkout must still fail without it.
        if (File("../SOURCE-MANIFEST.json").isFile) assumeTrue("The public snapshot excludes the editor fixture", fixture.isFile)
        assertTrue("\"ScreenCapture.kt\"" in fixture.readText())
    }
}
