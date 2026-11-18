package dev.localtype.keyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The settings reorganization moved every control; this keeps any setting from being left without one. */
class SettingsCoverageTest {
    /** Setters the keyboard, setup or the update check use, not a person on the settings screen. */
    private val internal = setOf(
        "setLanguage", "setSetupComplete", "setSetupStep", "setTourSeen", "setChecked", "setUpdateDismissedCode",
    )

    @Test
    fun `every setting has a control on the settings screen`() {
        val settings = File("src/main/java/dev/localtype/keyboard/KeyboardSettings.kt").readText()
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        // Public setters only: a private helper such as setOffered is how the others store their value.
        val setters = Regex("""^\s*fun (set[A-Z]\w*)\(""", RegexOption.MULTILINE).findAll(settings).map { it.groupValues[1] }.toSet() - internal
        assertTrue("found ${setters.size} setters", setters.size >= 25)
        val missing = setters.filter { "KeyboardSettings.$it(" !in screen }
        assertTrue("no control for $missing", missing.isEmpty())
    }

    @Test
    fun `web links open only through the guarded helper`() {
        val screen = File("src/main/java/dev/localtype/keyboard/SettingsActivity.kt").readText()
        // openInBrowser catches a phone with no browser; a second ACTION_VIEW would bypass that.
        val views = Regex("""Intent\(Intent\.ACTION_VIEW""").findAll(screen).count()
        assertTrue("found $views ACTION_VIEW intents", views == 1)
    }
}
